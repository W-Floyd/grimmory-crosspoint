import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_CONFIG } from '../../core/config/api-config';
import { Observable } from 'rxjs';

/** A linked Libby library card. */
export interface OverDriveCard {
  cardId: string;
  name?: string | null;
  libraryKey?: string | null;
  /** True only for card+PIN links with a credential key set — those can be refreshed/re-linked. */
  credentialsStored?: boolean;
}

export interface OverDriveCover {
  cover150Wide?: { href?: string };
  cover300Wide?: { href?: string };
  cover510Wide?: { href?: string };
}

export interface OverDriveLoan {
  id: string;
  title: string;
  expireDate: string;
  /** Flat primary-author name from sync (sync loans omit the `creators` array). */
  firstCreatorName?: string;
  creators?: OverDriveCreator[];
  covers?: OverDriveCover;
  formatId?: string;
  formats?: OverDriveFormat[];
}

export interface OverDriveHold {
  id: string;
  title: string;
  creators?: OverDriveCreator[];
  estimatedWaitDays?: string;
}

export interface OverDriveCreator {
  name: string;
  role: string;
}

export interface OverDriveFormat {
  id: string;
  isbn?: string;
}

export interface OverDriveSyncResult {
  loans: OverDriveLoan[];
  holds: OverDriveHold[];
  libraries: OverDriveLibrary[];
}

export interface OverDriveLibrary {
  preferredKey: string;
  name: string;
  website?: string;
}

export interface OverDriveFulfillResult {
  acsmBase64: string | null;
}

export interface OverDriveCapabilities {
  acsmHandlerConfigured: boolean;
  /** Whether a credential key is configured, enabling encrypted card storage + auto-relink. */
  credentialStorageEnabled: boolean;
}

/** Result of validating/resolving an OverDrive library key against the Thunder directory. */
export interface OverDriveLibraryResolution {
  valid: boolean;
  libraryKey: string;
  name?: string | null;
}

export interface OverDriveCatalogItem {
  titleId: string;
  formatId: string;
  title: string;
  /** OverDrive often puts the real book name here (title is the series/franchise). */
  subtitle?: string | null;
  author?: string | null;
  coverUrl?: string | null;
  isbn?: string | null;
  /** Borrowable right now (has an available copy). */
  available: boolean;
  /** Can be placed on hold when not available. */
  holdable: boolean;
  availableCopies?: number | null;
  ownedCopies?: number | null;
  holdsCount?: number | null;
  estimatedWaitDays?: number | null;
  /** Importable formats this title offers, in the operator's preference order; formatId is the default (first). */
  formats?: string[];
  /** Not yet released; neither borrowable nor holdable. */
  preRelease: boolean;
}

export interface OverDriveBorrowImportRequest {
  titleId: string;
  libraryId: number;
  pathId: number;
  title?: string | null;
  author?: string | null;
  coverUrl?: string | null;
  isbn?: string | null;
  /** Optional format to borrow (e.g. ebook-epub-adobe); honored if the loan offers it, else preference decides. */
  formatId?: string | null;
}

/** Minimal shape of the imported book returned by borrow-and-import. */
export interface OverDriveImportedBook {
  id: number;
  title?: string;
}

/**
 * OverDrive/Libby API client. Auth tokens are stored server-side per (user, card); callers pass a
 * card id and the backend resolves the token, so the client never handles tokens directly.
 */
@Injectable({
  providedIn: 'root'
})
export class OverDriveService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/overdrive`;

  // Account / cards

  /** Redeem a Libby 8-digit setup code; links all cards on that account and returns them. */
  redeemSetupCode(code: string): Observable<OverDriveCard[]> {
    return this.http.post<OverDriveCard[]>(`${this.baseUrl}/setup-code`, { code });
  }

  /**
   * Link a library card by number + PIN. Produces a fulfillment-capable (primary) card, unlike a
   * setup code (browse-only). Returns the linked cards.
   */
  linkCard(libraryKey: string, cardNumber: string, pin: string): Observable<OverDriveCard[]> {
    return this.http.post<OverDriveCard[]>(`${this.baseUrl}/link-card`, { libraryKey, cardNumber, pin });
  }

  /**
   * Link by pasting a Libby identity token from a signed-in browser (primary chip → can download
   * Adobe-DRM titles). Returns the linked cards.
   */
  linkToken(token: string): Observable<OverDriveCard[]> {
    return this.http.post<OverDriveCard[]>(`${this.baseUrl}/link-token`, { token });
  }

  /** The current user's linked library cards. */
  cards(): Observable<OverDriveCard[]> {
    return this.http.get<OverDriveCard[]>(`${this.baseUrl}/cards`);
  }

  /** Unlink a card (clear its stored token/credentials). */
  removeCard(cardId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/token`, { params: { identity: cardId } });
  }

  /** Refresh a card+PIN card's token by re-linking from its stored credentials. */
  refreshCard(cardId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${cardId}/refresh`, null);
  }

  /** Report which OverDrive features are available (e.g. whether an ACSM handler is configured). */
  capabilities(): Observable<OverDriveCapabilities> {
    return this.http.get<OverDriveCapabilities>(`${this.baseUrl}/capabilities`);
  }

  /** Validate an OverDrive library key and resolve its display name via the Thunder directory. */
  resolveLibrary(key: string): Observable<OverDriveLibraryResolution> {
    return this.http.get<OverDriveLibraryResolution>(`${this.baseUrl}/resolve-library`, {
      params: { key }
    });
  }

  /**
   * Fetch a passive, read-only diagnostics snapshot (config, linked cards, recorded loans). Makes no
   * live OverDrive calls and takes no input.
   */
  diagnostics(): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(`${this.baseUrl}/diagnostics`);
  }

  // Catalog

  /** Search the OverDrive catalog for borrowable titles. */
  search(query: string): Observable<OverDriveCatalogItem[]> {
    return this.http.get<OverDriveCatalogItem[]>(`${this.baseUrl}/search`, {
      params: { query }
    });
  }

  /** Borrow a title on the given card and import the fulfilled book into a library. */
  borrowAndImport(cardId: string, request: OverDriveBorrowImportRequest): Observable<OverDriveImportedBook> {
    return this.http.post<OverDriveImportedBook>(`${this.baseUrl}/${cardId}/borrow-and-import`, request);
  }

  // Sync / loans

  /** Sync loans and holds for a card. */
  sync(cardId: string): Observable<OverDriveSyncResult> {
    return this.http.get<OverDriveSyncResult>(`${this.baseUrl}/sync`, {
      params: { identity: cardId }
    });
  }

  /** Borrow a title on a card by its title id. */
  borrow(cardId: string, titleId: string): Observable<{ loanId: string }> {
    return this.http.post<{ loanId: string }>(`${this.baseUrl}/${cardId}/borrow`, { titleId });
  }

  /** Fulfill a loan to download its ACSM fulfillment token. */
  fulfill(cardId: string, loanId: string): Observable<OverDriveFulfillResult> {
    return this.http.post<OverDriveFulfillResult>(`${this.baseUrl}/${cardId}/fulfill/${loanId}`, null);
  }

  /** Return a borrowed book. */
  returnBook(cardId: string, loanId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${cardId}/return/${loanId}`, null);
  }

  // Holds

  /** Place a hold on a title. */
  placeHold(cardId: string, titleId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${cardId}/hold/${titleId}`, null);
  }

  /** Cancel a hold on a title. */
  cancelHold(cardId: string, titleId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${cardId}/hold/${titleId}`);
  }
}
