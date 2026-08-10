import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpResponse } from '@angular/common/http';
import { API_CONFIG } from '../../core/config/api-config';
import { Observable } from 'rxjs';

/** A linked Libby library card. */
export interface OverDriveCard {
  cardId: string;
  name?: string | null;
  libraryKey?: string | null;
  /** True only for card+PIN links with a credential key set — those can be refreshed/re-linked. */
  credentialsStored?: boolean;
  /** Remembered default destination library for borrow & import on this card (null → Bookdrop). */
  defaultLibraryId?: number | null;
  /** Remembered default destination library path within defaultLibraryId. */
  defaultPathId?: number | null;
  /** False when another user shared this card with you: borrow/hold works, but management is hidden. */
  owned?: boolean;
  /** Display name of the user who shared this card with you (only set when owned === false). */
  ownerName?: string | null;
  /** How many other users you've shared this card with (only meaningful on cards you own). */
  sharedWithCount?: number;
  /** Whether this card can silently re-link its token on expiry (card+PIN on file + storage enabled). */
  canAutoRenew?: boolean;
  /** Epoch-seconds expiry of the current Libby token (non-sensitive), or null. */
  tokenExpiresAt?: number | null;
}

/**
 * A linked card as seen by a cross-user card manager: the same shape as {@link OverDriveCard} minus the
 * viewer-relative fields, plus its owner. `ownerUserId` is what management calls pass back to name the
 * target row — the same `cardId` can appear once per user who linked it.
 */
export interface OverDriveManagedCard {
  cardId: string;
  name?: string | null;
  libraryKey?: string | null;
  credentialsStored?: boolean;
  defaultLibraryId?: number | null;
  defaultPathId?: number | null;
  sharedWithCount?: number;
  canAutoRenew?: boolean;
  tokenExpiresAt?: number | null;
  ownerUserId: number;
  ownerName: string;
}

/** A user for the card-sharing picker / share list. */
export interface OverDriveShareUser {
  userId: number;
  username: string;
  name?: string | null;
}

/** A user's per-document-type OverDrive import destinations (null = unset → Bookdrop). */
export interface OverDriveImportDestinations {
  ebookLibraryId?: number | null;
  ebookPathId?: number | null;
  audiobookLibraryId?: number | null;
  audiobookPathId?: number | null;
  magazineLibraryId?: number | null;
  magazinePathId?: number | null;
}

/** One entry in a user's OverDrive activity history. */
export interface OverDriveAuditEntry {
  id: number;
  /** Action name, e.g. BORROW, RETURN, HOLD_PLACED, CARD_LINKED, SHARE_UPDATED. */
  action: string;
  identity?: string | null;
  libraryKey?: string | null;
  cardName?: string | null;
  titleId?: string | null;
  loanId?: string | null;
  bookId?: number | null;
  title?: string | null;
  detail?: string | null;
  success: boolean;
  /** ISO-8601 timestamp of when the action happened. */
  createdAt?: string | null;
}

export interface OverDriveLoan {
  id: string;
  title: string;
  /** OverDrive often puts the real book name here (title is the series/franchise). */
  subtitle?: string | null;
  expireDate: string;
  /** When the loan was checked out (ISO-8601 from Libby sync). */
  checkoutDate?: string | null;
  /** Flat primary-author name from sync (sync loans omit the `creators` array). */
  firstCreatorName?: string;
  /** Cover thumbnail URL derived from the sync loan's covers. */
  coverUrl?: string;
  creators?: OverDriveCreator[];
  formatId?: string;
  formats?: OverDriveFormat[];
  /** Id of the existing library book this loan is linked to (by prior import or ISBN), if any. */
  bookId?: number | null;
  /**
   * The card this loan sits on, as reported by the sync feed itself — chip syncs cover every card on the
   * chip, so this (not "which card we asked for") is what attributes a loan.
   */
  cardId?: string;
  /** Catalog-enriched: narrator name(s), raw edition label, audiobook duration ("HH:MM:SS"), and media type. */
  narrator?: string | null;
  edition?: string | null;
  duration?: string | null;
  audiobook?: boolean;
  magazine?: boolean;
  /** Copy/queue counts at the loan's own library, carried by the sync feed — no extra lookup needed. */
  availableCopies?: number | null;
  ownedCopies?: number | null;
  holdsCount?: number | null;
  luckyDayAvailableCopies?: number | null;
}

export interface OverDriveHold {
  id: string;
  title: string;
  /** OverDrive often puts the real book name here (title is the series/franchise). */
  subtitle?: string | null;
  /** Flat primary-author name from sync (sync omits the `creators` array). */
  firstCreatorName?: string;
  /** Cover thumbnail URL derived from the sync hold's covers. */
  coverUrl?: string;
  creators?: OverDriveCreator[];
  estimatedWaitDays?: string;
  /** True when the hold is ready to borrow now (a copy is reserved for you). */
  ready?: boolean;
  /** For a ready hold, the deadline to borrow it before the hold is released. */
  expireDate?: string;
  /** When the hold was placed (ISO-8601 from Libby sync). */
  placedDate?: string | null;
  /**
   * The card this hold sits on, as reported by the sync feed itself — chip syncs cover every card on the
   * chip, so this (not "which card we asked for") is what attributes a hold.
   */
  cardId?: string;
  /** Catalog-enriched: narrator name(s), raw edition label, audiobook duration ("HH:MM:SS"), and media type. */
  narrator?: string | null;
  edition?: string | null;
  duration?: string | null;
  audiobook?: boolean;
  magazine?: boolean;
  /**
   * Queue position and copy counts at the hold's own library, carried by the sync feed. The Holds tab
   * therefore only needs to call out for the user's *other* libraries.
   */
  holdListPosition?: number | null;
  holdsCount?: number | null;
  availableCopies?: number | null;
  ownedCopies?: number | null;
  luckyDayAvailableCopies?: number | null;
  holdable?: boolean | null;
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
  /** Active card's loan/hold usage vs. limits (null when the sync didn't report them). */
  loanCount?: number | null;
  loanLimit?: number | null;
  holdCount?: number | null;
  holdLimit?: number | null;
  canPlaceHolds?: boolean;
}

export interface OverDriveLibrary {
  preferredKey: string;
  name: string;
  website?: string;
}

export interface OverDriveFulfillResult {
  acsmBase64: string | null;
}

/**
 * A structured progress event streamed from an external handler (go-od progress protocol).
 * See docs/progress-protocol.md in the handler repo.
 */
export interface OverDriveToolEvent {
  type: 'progress' | 'log' | 'result';
  phase?: string;
  message?: string;
  level?: 'debug' | 'info' | 'warn' | 'error';
  current?: number;
  total?: number;
  pct?: number;
  ok?: boolean;
  file?: string;
}

/** One frame on the tool-log websocket: either a raw text line or a structured event. */
export interface OverDriveToolLogFrame {
  titleId?: string;
  line?: string;
  event?: OverDriveToolEvent;
}

export interface OverDriveCapabilities {
  acsmHandlerConfigured: boolean;
  /** Whether a credential key is configured, enabling encrypted card storage + auto-relink. */
  credentialStorageEnabled: boolean;
  /** Whether an external audiobook handler is configured, enabling audiobook borrows. */
  audiobookHandlerConfigured: boolean;
  /** Whether an external magazine handler is configured, enabling magazine borrows. */
  magazineHandlerConfigured: boolean;
  /**
   * Whether an external ebook handler is configured, enabling titles offered only in Libby's
   * read-in-browser ("ebook-overdrive") format, which has no downloadable file and no ACSM.
   */
  ebookHandlerConfigured: boolean;
}

/** Result of validating/resolving an OverDrive library key against the Thunder directory. */
export interface OverDriveLibraryResolution {
  valid: boolean;
  libraryKey: string;
  name?: string | null;
}

/** Availability of a catalog title at one specific library (advantage key). */
export interface OverDriveLibraryAvailability {
  libraryKey: string;
  available: boolean;
  holdable: boolean;
  availableCopies?: number | null;
  ownedCopies?: number | null;
  holdsCount?: number | null;
  estimatedWaitDays?: number | null;
  /** "Lucky Day" copies at this library, borrowable now without a hold. */
  luckyDayAvailableCopies?: number | null;
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
  /** "Lucky Day" copies available now (aggregate across libraries), borrowable without a hold. */
  luckyDayAvailableCopies?: number | null;
  /** Importable formats this title offers, in the operator's preference order; formatId is the default (first). */
  formats?: string[];
  /** Not yet released; neither borrowable nor holdable. */
  preRelease: boolean;
  /** Per-library availability (one entry per library the title surfaced from). */
  availability?: OverDriveLibraryAvailability[];
  /** Id of an existing library book this title matches (by ISBN), or null if not in the library. */
  bookId?: number | null;
  /** Normalized primary language code (e.g. "en", "es"), or null. */
  language?: string | null;
  /** Raw OverDrive edition label (e.g. "Unabridged"/"Abridged"), surfaced as-is; null/absent otherwise. */
  edition?: string | null;
  /** True when the title is an audiobook (offers an audiobook format), false for an ebook. */
  audiobook?: boolean;
  /** Narrator name(s) for an audiobook (comma-joined), or null. */
  narrator?: string | null;
  /** Audiobook playback length ("HH:MM:SS"), or null. */
  duration?: string | null;
  /** True when the title is a magazine (offers a magazine format). */
  magazine?: boolean;
}

/** Facet filters pushed into the catalog search server-side (see {@link OverDriveService.search}). */
export interface OverDriveSearchFilter {
  /** Restrict the medium: "ebook" or "audiobook" (omit for both). */
  mediaTypes?: string;
  /** Return only titles borrowable now. */
  availableOnly?: boolean;
  /** Restrict to an ISO language code (e.g. "en"). */
  language?: string;
}

export interface OverDriveBorrowImportRequest {
  titleId: string;
  /** Destination library; null/omitted drops the fulfilled book into Bookdrop instead. */
  libraryId?: number | null;
  /** Destination library path; null/omitted drops the fulfilled book into Bookdrop instead. */
  pathId?: number | null;
  title?: string | null;
  author?: string | null;
  coverUrl?: string | null;
  isbn?: string | null;
  /** Optional format to borrow (e.g. ebook-epub-adobe); honored if the loan offers it, else preference decides. */
  formatId?: string | null;
  /** Media-type hint ("audiobook"/"ebook") so the borrow's title_format matches the title. */
  titleFormat?: string | null;
  /**
   * Id of an already-imported book this import *replaces*: the server deletes it once the new file has
   * been fulfilled, so the re-import lands in its place instead of adding a second copy. Omit to keep
   * the existing copy.
   */
  replaceBookId?: number | null;
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
  redeemSetupCode(code: string, ownerUserId?: number): Observable<OverDriveCard[]> {
    // userId redeems the code for another user; the code is theirs to pass on, like a card number+PIN.
    return this.http.post<OverDriveCard[]>(`${this.baseUrl}/setup-code`,
      ownerUserId == null ? { code } : { code, userId: ownerUserId });
  }

  /**
   * Link a library card by number + PIN. Produces a fulfillment-capable (primary) card, unlike a
   * setup code (browse-only). Returns the linked cards.
   */
  linkCard(libraryKey: string, cardNumber: string, pin: string, ownerUserId?: number): Observable<OverDriveCard[]> {
    // userId links the card for another user — the only link flow that can be delegated, since a setup
    // code or identity token comes from that user's own Libby app or browser session.
    return this.http.post<OverDriveCard[]>(`${this.baseUrl}/link-card`,
      ownerUserId == null ? { libraryKey, cardNumber, pin } : { libraryKey, cardNumber, pin, userId: ownerUserId });
  }

  /**
   * Link by pasting a Libby identity token from a signed-in browser (primary chip → can download
   * Adobe-DRM titles). Returns the linked cards.
   */
  linkToken(token: string, ownerUserId?: number): Observable<OverDriveCard[]> {
    return this.http.post<OverDriveCard[]>(`${this.baseUrl}/link-token`,
      ownerUserId == null ? { token } : { token, userId: ownerUserId });
  }

  /** The current user's linked library cards. */
  cards(): Observable<OverDriveCard[]> {
    return this.http.get<OverDriveCard[]>(`${this.baseUrl}/cards`);
  }

  /**
   * Every user's linked cards, for a cross-user card manager. Requires permission to manage any user's
   * OverDrive cards; 403 otherwise.
   */
  allCards(): Observable<OverDriveManagedCard[]> {
    return this.http.get<OverDriveManagedCard[]>(`${this.baseUrl}/cards/all`);
  }

  /**
   * Unlink a card (clear its stored token/credentials). Pass `ownerUserId` to unlink another user's
   * card — that requires permission to manage any user's cards.
   */
  removeCard(cardId: string, ownerUserId?: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/token`, { params: this.ownerParams({ identity: cardId }, ownerUserId) });
  }

  /** Refresh a card+PIN card's token by re-linking from its stored credentials. */
  refreshCard(cardId: string, ownerUserId?: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${cardId}/refresh`, null, { params: this.ownerParams({}, ownerUserId) });
  }

  /** Set a friendly display label for a card (blank clears it back to the default name). */
  setCardLabel(cardId: string, name: string, ownerUserId?: number): Observable<void> {
    const params: Record<string, string> = {};
    if (name && name.trim()) {
      params['name'] = name.trim();
    }
    return this.http.put<void>(`${this.baseUrl}/${cardId}/label`, null, { params: this.ownerParams(params, ownerUserId) });
  }

  /** Add the card owner to a query when acting on someone else's card; omitted means "mine". */
  private ownerParams(params: Record<string, string>, ownerUserId?: number): Record<string, string> {
    return ownerUserId == null ? params : { ...params, userId: String(ownerUserId) };
  }

  /** Remember a card's default destination library + path (omit both to clear → Bookdrop). */
  setDefaultLibrary(cardId: string, libraryId: number | null, pathId: number | null): Observable<void> {
    const params: Record<string, string> = {};
    if (libraryId != null) {
      params['libraryId'] = String(libraryId);
    }
    if (pathId != null) {
      params['pathId'] = String(pathId);
    }
    return this.http.put<void>(`${this.baseUrl}/${cardId}/default-library`, null, { params });
  }

  /** Candidate users to share a card with (everyone but you). */
  shareableUsers(ownerUserId?: number): Observable<OverDriveShareUser[]> {
    return this.http.get<OverDriveShareUser[]>(`${this.baseUrl}/shareable-users`, { params: this.ownerParams({}, ownerUserId) });
  }

  /** Users a card is currently shared with (its owner, or a cross-user share manager). */
  listShares(cardId: string, ownerUserId?: number): Observable<OverDriveShareUser[]> {
    return this.http.get<OverDriveShareUser[]>(`${this.baseUrl}/${cardId}/shares`, { params: this.ownerParams({}, ownerUserId) });
  }

  /** Replace the set of users a card is shared with (its owner, or a cross-user share manager). */
  setShares(cardId: string, userIds: number[], ownerUserId?: number): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/${cardId}/shares`, { userIds }, { params: this.ownerParams({}, ownerUserId) });
  }

  /** The current user's recent OverDrive activity history (newest first). */
  history(): Observable<OverDriveAuditEntry[]> {
    return this.http.get<OverDriveAuditEntry[]>(`${this.baseUrl}/history`);
  }

  /** The current user's per-document-type import destinations. */
  importDestinations(): Observable<OverDriveImportDestinations> {
    return this.http.get<OverDriveImportDestinations>(`${this.baseUrl}/import-destinations`);
  }

  /** Save the current user's per-document-type import destinations. */
  setImportDestinations(destinations: OverDriveImportDestinations): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/import-destinations`, destinations);
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

  /**
   * Search the OverDrive catalog for borrowable titles. When {@code cardIds} is given, the search is
   * scoped to those cards' libraries; otherwise all of the user's libraries are searched. An optional
   * {@code filter} pushes facets into the query server-side (so a broad query's capped page is narrowed
   * before it returns): {@code mediaTypes} ("ebook"/"audiobook"), {@code availableOnly}, {@code language}.
   */
  search(query: string, cardIds?: string[], filter?: OverDriveSearchFilter, limit?: number): Observable<OverDriveCatalogItem[]> {
    const params: Record<string, string | string[]> = { query };
    if (cardIds && cardIds.length > 0) {
      params['cards'] = cardIds;
    }
    if (filter?.mediaTypes) {
      params['mediaTypes'] = filter.mediaTypes;
    }
    if (filter?.availableOnly) {
      params['availableOnly'] = 'true';
    }
    if (filter?.language) {
      params['language'] = filter.language;
    }
    if (limit && limit > 0) {
      params['limit'] = String(limit);
    }
    return this.http.get<OverDriveCatalogItem[]>(`${this.baseUrl}/search`, { params });
  }

  /**
   * Check a title's availability across the given cards' libraries (used by the Holds tab to surface
   * whether a held title is borrowable now at another of the user's libraries).
   */
  titleAvailability(titleId: string, cardIds: string[]): Observable<OverDriveLibraryAvailability[]> {
    const params: Record<string, string | string[]> = {};
    if (cardIds.length > 0) {
      params['cards'] = cardIds;
    }
    return this.http.get<OverDriveLibraryAvailability[]>(`${this.baseUrl}/title/${titleId}/availability`, { params });
  }

  /**
   * Batch availability for many titles across the given cards' libraries in one request, keyed by title
   * id. Backs the Holds tab's "check all other libraries" so a whole tab of holds costs one call per
   * library rather than one full lookup per title × library.
   */
  titleAvailabilityBatch(titleIds: string[], cardIds: string[]): Observable<Record<string, OverDriveLibraryAvailability[]>> {
    return this.http.post<Record<string, OverDriveLibraryAvailability[]>>(
      `${this.baseUrl}/titles/availability`, { titleIds, cards: cardIds });
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

  /**
   * Sync several cards in one request, keyed by card id. Libby's sync is chip-scoped, so the backend
   * collapses cards sharing a chip into a single upstream call — always prefer this over looping
   * {@link sync} per card. Cards whose sync failed are simply absent from the result.
   */
  syncAll(cardIds: string[]): Observable<Record<string, OverDriveSyncResult>> {
    return this.http.get<Record<string, OverDriveSyncResult>>(`${this.baseUrl}/sync-all`, {
      params: { identities: cardIds.join(',') }
    });
  }

  /** Borrow a title on a card by its title id. {@code titleFormat} ("audiobook"/"ebook") shapes the loan. */
  borrow(cardId: string, titleId: string, titleFormat?: string): Observable<{ loanId: string }> {
    return this.http.post<{ loanId: string }>(`${this.baseUrl}/${cardId}/borrow`, { titleId, titleFormat });
  }

  /** Fulfill a loan to download its ACSM fulfillment token. */
  fulfill(cardId: string, loanId: string): Observable<OverDriveFulfillResult> {
    return this.http.post<OverDriveFulfillResult>(`${this.baseUrl}/${cardId}/fulfill/${loanId}`, null);
  }

  /**
   * Download an audiobook loan as an assembled file via the external audiobook handler. Returns the raw
   * response so the caller can read the tool-chosen filename from Content-Disposition and save the blob.
   */
  downloadAudiobook(cardId: string, loanId: string, formatId?: string): Observable<HttpResponse<Blob>> {
    const params: Record<string, string> = {};
    if (formatId) {
      params['formatId'] = formatId;
    }
    return this.http.post(`${this.baseUrl}/${cardId}/fulfill/${loanId}/download-audiobook`, null, {
      params,
      observe: 'response',
      responseType: 'blob',
    });
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
