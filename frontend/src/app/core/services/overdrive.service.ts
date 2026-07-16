import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { API_CONFIG } from '../../core/config/api-config';
import { Observable } from 'rxjs';

export interface OverDriveChipResult {
  identity: string;
  token: string;
}

export interface OverDriveSetupCodeRequest {
  code: string;
}

export interface OverDriveSetupResult {
  identity: string;
  token: string;
}

export interface OverDriveLoan {
  id: string;
  title: string;
  expireDate: string;
  creators?: OverDriveCreator[];
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

export interface OverDriveTokenListResult {
  identities: string[];
}

export interface OverDriveCapabilities {
  acsmHandlerConfigured: boolean;
}

export interface OverDriveCatalogItem {
  titleId: string;
  formatId: string;
  title: string;
  author?: string | null;
  coverUrl?: string | null;
  isbn?: string | null;
}

export interface OverDriveBorrowImportRequest {
  titleId: string;
  libraryId: number;
  pathId: number;
  title?: string | null;
  author?: string | null;
  coverUrl?: string | null;
  isbn?: string | null;
}

/** Minimal shape of the imported book returned by borrow-and-import. */
export interface OverDriveImportedBook {
  id: number;
  title?: string;
}

@Injectable({
  providedIn: 'root'
})
export class OverDriveService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${API_CONFIG.BASE_URL}/api/overdrive`;

  // Chip / Auth

  /** Obtain a new chip identity from OverDrive. */
  postChip(): Observable<OverDriveChipResult> {
    return this.http.post<OverDriveChipResult>(`${this.baseUrl}/chip`, {});
  }

  /** Redeem a Libby 8-digit setup code to link a card. */
  redeemSetupCode(code: string): Observable<OverDriveSetupResult> {
    return this.http.post<OverDriveSetupResult>(`${this.baseUrl}/setup-code`, { code });
  }

  /** Store a token for later use. */
  storeToken(identity: string, token: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/token`, {}, {
      params: { identity, token }
    });
  }

  /** Remove a stored token. */
  removeToken(identity: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/token`, {
      params: { identity }
    });
  }

  /** List stored token identities. */
  listTokens(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/tokens`);
  }

  // Capabilities

  /** Report which OverDrive features are available (e.g. whether an ACSM handler is configured). */
  capabilities(): Observable<OverDriveCapabilities> {
    return this.http.get<OverDriveCapabilities>(`${this.baseUrl}/capabilities`);
  }

  // Catalog

  /** Search the OverDrive catalog for borrowable titles. */
  search(query: string): Observable<OverDriveCatalogItem[]> {
    return this.http.get<OverDriveCatalogItem[]>(`${this.baseUrl}/search`, {
      params: { query }
    });
  }

  /** Borrow a title and import the fulfilled EPUB into a library. */
  borrowAndImport(identity: string, token: string, request: OverDriveBorrowImportRequest): Observable<OverDriveImportedBook> {
    return this.http.post<OverDriveImportedBook>(`${this.baseUrl}/${identity}/borrow-and-import`,
      request,
      { params: { token } }
    );
  }

  // Sync

  /** Sync loans and holds from OverDrive. */
  sync(identity: string, token: string): Observable<OverDriveSyncResult> {
    return this.http.get<OverDriveSyncResult>(`${this.baseUrl}/sync`, {
      params: { identity, token }
    });
  }

  // Loans

  /** Borrow a title from OverDrive by its title id. */
  borrow(identity: string, token: string, titleId: string): Observable<{ loanId: string }> {
    return this.http.post<{ loanId: string }>(`${this.baseUrl}/${identity}/borrow`,
      { titleId },
      { params: { token } }
    );
  }

  /** Fulfill a loan to download its ACSM fulfillment token. */
  fulfill(identity: string, token: string, loanId: string): Observable<OverDriveFulfillResult> {
    return this.http.post<OverDriveFulfillResult>(`${this.baseUrl}/${identity}/fulfill/${loanId}`,
      null,
      { params: { token } }
    );
  }

  /** Return a borrowed book. */
  returnBook(identity: string, token: string, loanId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${identity}/return/${loanId}`,
      null,
      { params: { token } }
    );
  }

  // Holds

  /** Place a hold on a book. */
  placeHold(identity: string, token: string, formatId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${identity}/hold/${formatId}`,
      null,
      { params: { token } }
    );
  }

  /** Cancel a hold. */
  cancelHold(identity: string, token: string, formatId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${identity}/hold/${formatId}`, {
      params: { token }
    });
  }
}