import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OverDriveService, OverDriveCard, OverDriveCatalogItem, OverDriveCreator, OverDriveHold, OverDriveLibrary, OverDriveLoan, OverDriveSyncResult } from '../../core/services/overdrive.service';

import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { SelectModule } from 'primeng/select';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { TooltipModule } from 'primeng/tooltip';
import { InputTextModule } from 'primeng/inputtext';
import { TabsModule } from 'primeng/tabs';
import { LibraryService } from '../../features/book/service/library.service';
import { Library, LibraryPath } from '../../features/book/model/library.model';
import { OverdriveTitleCellComponent } from './overdrive-title-cell.component';
import { OverdriveCoverComponent } from './overdrive-cover.component';

@Component({
  selector: 'app-overdrive-catalog',
  standalone: true,
  imports: [
    OverdriveTitleCellComponent,
    OverdriveCoverComponent,
    FormsModule,
    ButtonModule,
    MessageModule,
    CardModule,
    TableModule,
    SelectModule,
    ToastModule,
    TooltipModule,
    InputTextModule,
    TabsModule
],
  templateUrl: './overdrive-catalog.component.html',
  styleUrl: './overdrive-catalog.component.scss',
  providers: [MessageService]
})
export class OverdriveCatalogComponent {
  private readonly overdriveService = inject(OverDriveService);
  private readonly messageService = inject(MessageService);
  private readonly libraryService = inject(LibraryService);

   // State
  loading = signal(false);
  error = signal<string | null>(null);
  loans = signal<OverDriveLoan[]>([]);
  holds = signal<OverDriveHold[]>([]);
  libraries = signal<OverDriveLibrary[]>([]);
  // Time of the last successful loans/holds sync, so the user knows how current the data is.
  lastSynced = signal<Date | null>(null);
  // Active tab on the catalog (search / loans / holds).
  activeTab = signal<string | number>('search');
  // Active card's loan/hold usage vs. limits (null when the sync didn't report them).
  loanCount = signal<number | null>(null);
  loanLimit = signal<number | null>(null);
  holdCount = signal<number | null>(null);
  holdLimit = signal<number | null>(null);
  canPlaceHolds = signal(true);

   // Linked Libby cards (per user); the selected card drives sync/borrow/return. Linking/unlinking and
   // diagnostics live on the OverDrive settings page — this page is browse/borrow only.
  cards = signal<OverDriveCard[]>([]);
  selectedCard = signal<OverDriveCard | null>(null);

   // Catalog search + import
  readonly grimmoryLibraries = this.libraryService.libraries;
  searchQuery = signal('');
  searching = signal(false);
  results = signal<OverDriveCatalogItem[]>([]);
  selectedLibrary = signal<Library | null>(null);
  selectedPath = signal<LibraryPath | null>(null);
  importingTitleId = signal<string | null>(null);
  // Per-title chosen download format (titleId → formatId); defaults to the title's top preference.
  selectedFormats = signal<Record<string, string>>({});
  // Title ids the user has explicitly chosen to re-borrow despite already being in the library.
  reborrowOverrides = signal<Set<string>>(new Set());
  // Per-row outcome of the last borrow/import/hold action (keyed by titleId / loanId / holdId).
  actionOutcome = signal<Record<string, 'success' | 'error'>>({});
  // Whether an ACSM handler is configured (enables importing Adobe-DRM formats). Shapes the
  // "No supported format" tooltip: no point suggesting ACSM setup when it's already enabled.
  acsmConfigured = signal(false);

   constructor() {
     this.loadCards();
     this.overdriveService.capabilities().subscribe({
       next: (c) => this.acsmConfigured.set(!!c?.acsmHandlerConfigured),
       error: () => { /* leave default (assume not configured) */ }
     });
   }

   cardLabel(card: OverDriveCard | null): string {
     if (!card) return '';
     return card.name ? `${card.name} (${card.cardId})` : card.cardId;
   }

   /** Record a row's borrow/import/hold outcome for its inline status indicator. */
   private setOutcome(id: string, outcome: 'success' | 'error'): void {
     this.actionOutcome.update((m) => ({ ...m, [id]: outcome }));
   }

   /** True when the card has reached its loan limit — borrowing is blocked until a loan is returned. */
   atLoanLimit(): boolean {
     const c = this.loanCount();
     const l = this.loanLimit();
     return c !== null && l !== null && c >= l;
     }

   /** True when the card can't place more holds (at its hold limit, or holds are disabled). */
   atHoldLimit(): boolean {
     if (!this.canPlaceHolds()) return true;
     const c = this.holdCount();
     const l = this.holdLimit();
     return c !== null && l !== null && c >= l;
     }

   /** Tab label with usage vs. limit when known, e.g. "Loans (5 / 10)". */
   loansTabLabel(): string {
     const count = this.loanCount() ?? this.loans().length;
     return this.loanLimit() !== null ? `Loans (${count} / ${this.loanLimit()})` : `Loans (${this.loans().length})`;
     }

   holdsTabLabel(): string {
     const count = this.holdCount() ?? this.holds().length;
     return this.holdLimit() !== null ? `Holds (${count} / ${this.holdLimit()})` : `Holds (${this.holds().length})`;
     }

   /** "Last synced" label: time only when it was today, otherwise date + time to avoid ambiguity. */
   syncedLabel(): string | null {
     const d = this.lastSynced();
     if (!d) return null;
     const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
     const isToday = d.toDateString() === new Date().toDateString();
     return isToday ? time : `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })}, ${time}`;
   }

   /** Load the user's linked cards; optionally select a specific one, else keep/first. */
   private loadCards(preferCardId?: string): void {
     this.overdriveService.cards().subscribe({
       next: (cards) => {
         this.cards.set(cards ?? []);
         if (cards && cards.length > 0) {
           const preferred = preferCardId ? cards.find(c => c.cardId === preferCardId) : undefined;
           const current = this.selectedCard();
           const stillPresent = current ? cards.find(c => c.cardId === current.cardId) : undefined;
           const selected = preferred ?? stillPresent ?? cards[0];
           this.selectedCard.set(selected);
           this.applyCardDefault(selected);
           this.onLoadLoans();
         } else {
           this.selectedCard.set(null);
         }
       },
       error: () => { /* not connected yet; leave cards empty */ }
     });
   }

   onCardChange(card: OverDriveCard | null): void {
     this.selectedCard.set(card);
     if (card) {
       this.applyCardDefault(card);
       this.onLoadLoans();
     }
   }

   /** Pre-select the destination from the card's remembered default (resolved against known libraries). */
   private applyCardDefault(card: OverDriveCard): void {
     const libs = this.grimmoryLibraries();
     const library = card.defaultLibraryId != null ? libs.find(l => l.id === card.defaultLibraryId) ?? null : null;
     this.selectedLibrary.set(library);
     const path = (library && card.defaultPathId != null)
       ? library.paths?.find(p => p.id === card.defaultPathId) ?? null
       : null;
     this.selectedPath.set(path);
   }

   /** Persist the current destination as the selected card's default (fire-and-forget) and keep it locally. */
   private persistCardDefault(): void {
     const card = this.selectedCard();
     if (!card) return;
     const libraryId = this.selectedLibrary()?.id ?? null;
     const pathId = this.selectedPath()?.id ?? null;
     this.overdriveService.setDefaultLibrary(card.cardId, libraryId, pathId).subscribe({
       error: (err: unknown) => this.error.set(this.errorMessage(err, 'Failed to save default library'))
     });
     const updated = { ...card, defaultLibraryId: libraryId, defaultPathId: pathId };
     this.selectedCard.set(updated);
     this.cards.update(cs => cs.map(c => c.cardId === card.cardId ? updated : c));
   }

   onLoadLoans(): void {
     const card = this.selectedCard();
     if (!card) {
       this.error.set('Connect a Libby account and select a card first');
       return;
       }

     this.loading.set(true);
     this.error.set(null);

     this.overdriveService.sync(card.cardId).subscribe({
       next: (result: OverDriveSyncResult) => {
         this.loans.set(result.loans ?? []);
         this.holds.set(result.holds ?? []);
         this.libraries.set(result.libraries ?? []);
         this.loanCount.set(result.loanCount ?? null);
         this.loanLimit.set(result.loanLimit ?? null);
         this.holdCount.set(result.holdCount ?? null);
         this.holdLimit.set(result.holdLimit ?? null);
         this.canPlaceHolds.set(result.canPlaceHolds ?? true);
         this.lastSynced.set(new Date());
         this.loading.set(false);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Failed to sync'));
         this.loading.set(false);
         }
       });
     }

   onLibraryChange(library: Library | null): void {
     this.selectedLibrary.set(library);
     // Auto-select the only path, otherwise clear.
     this.selectedPath.set(library?.paths?.length === 1 ? library.paths[0] : null);
     this.persistCardDefault();
   }

   onPathChange(path: LibraryPath | null): void {
     this.selectedPath.set(path);
     this.persistCardDefault();
   }

   onSearch(): void {
     const query = this.searchQuery().trim();
     if (!query) {
       this.error.set('Enter a search term');
       return;
       }
     this.searching.set(true);
     this.error.set(null);
     this.overdriveService.search(query).subscribe({
       next: (items) => {
         this.results.set(items ?? []);
         this.searching.set(false);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Search failed'));
         this.searching.set(false);
         }
       });
     }

   onBorrowImport(item: OverDriveCatalogItem): void {
     const card = this.selectedCard();
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     if (!card) {
       this.error.set('Connect a Libby account and select a card first');
       return;
       }
     if (!item.titleId) {
       this.error.set('This title is not borrowable');
       return;
       }

     this.importingTitleId.set(item.titleId);
     this.error.set(null);
     this.overdriveService.borrowAndImport(card.cardId, {
       titleId: item.titleId,
       libraryId: library?.id ?? null,
       pathId: path?.id ?? null,
       title: this.fullTitle(item),
       author: item.author,
       coverUrl: item.coverUrl,
       isbn: item.isbn,
       formatId: this.chosenFormat(item)
     }).subscribe({
       next: (book) => {
         if (book?.id != null) {
           this.messageService.add({
             severity: 'success',
             summary: 'Imported',
             detail: `"${this.fullTitle(item)}" borrowed and imported (book #${book.id})`
            });
           this.markResultImported(item.titleId, book.id);
         } else {
           this.messageService.add({
             severity: 'success',
             summary: 'Sent to Bookdrop',
             detail: `"${this.fullTitle(item)}" borrowed and dropped into Bookdrop for review`
            });
         }
         this.setOutcome(item.titleId, 'success');
         this.importingTitleId.set(null);
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow & import failed'));
         this.setOutcome(item.titleId, 'error');
         this.importingTitleId.set(null);
         // The borrow may have placed the loan even when the import step failed (e.g. no importable
         // format) — resync so the placed loan appears in Your Loans (usable in the Libby app).
         this.onLoadLoans();
         }
       });
     }

   /** Combined title for display/import: "Series: Book" when OverDrive splits the name into a subtitle. */
   fullTitle(item: OverDriveCatalogItem): string {
     return item.subtitle ? `${item.title}: ${item.subtitle}` : item.title;
     }

   /** Borrow a title onto the Libby account without importing it into grimmory. */
   onBorrowOnly(item: OverDriveCatalogItem): void {
     const card = this.selectedCard();
     if (!card || !item.titleId) return;
     this.importingTitleId.set(item.titleId);
     this.error.set(null);
     this.overdriveService.borrow(card.cardId, item.titleId).subscribe({
       next: () => {
         this.messageService.add({ severity: 'success', summary: 'Borrowed',
           detail: `"${this.fullTitle(item)}" borrowed to Libby (not imported)` });
         this.setOutcome(item.titleId, 'success');
         this.importingTitleId.set(null);
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow failed'));
         this.setOutcome(item.titleId, 'error');
         this.importingTitleId.set(null);
         this.onLoadLoans();
         }
       });
     }

   /** Human label for the format a loan was taken in (the locked/chosen format), or null. */
   loanFormat(loan: OverDriveLoan): string | null {
     const id = loan.formatId ?? loan.formats?.[0]?.id ?? null;
     return id ? this.formatLabel(id) : null;
     }

   /** True when a search result already matches a book in the library (by ISBN). */
   isInLibrary(item: OverDriveCatalogItem): boolean {
     return item.bookId != null;
     }

   /**
    * Whether the title offers a format Grimmory can import (open formats, plus Adobe/ACSM formats when
    * an ACSM handler is configured). The backend leaves {@code formats} empty when none apply, so we
    * grey out Borrow & Import rather than let it fail at fulfillment.
    */
   hasImportableFormat(item: OverDriveCatalogItem): boolean {
     return (item.formats?.length ?? 0) > 0;
     }

   /** Why a title has no importable format — only mentions ACSM setup when it isn't already configured. */
   unsupportedFormatTooltip(): string {
     return this.acsmConfigured()
       ? 'This title isn\'t offered in a format Grimmory can import.'
       : 'This title isn\'t offered in a DRM-free format. Configure an ACSM handler to also import Adobe-DRM formats.';
     }

   /** Warning shown on the borrow button for a title with no importable format. */
   unsupportedBorrowTooltip(): string {
     return this.unsupportedFormatTooltip()
       + ' Borrowing won\'t import it here, but it still places the loan on your Libby account for use in the Libby app.';
     }

   /** Warning shown on the hold button for a title with no importable format. */
   unsupportedHoldTooltip(): string {
     return this.unsupportedFormatTooltip()
       + ' Placing a hold won\'t let you import it here, but it still holds the title on your Libby account for use in the Libby app.';
     }

   /**
    * Whether the Borrow & Import button should be enabled: always for titles not in the library, and for
    * in-library titles only once the user has explicitly chosen to re-borrow (override).
    */
   canBorrow(item: OverDriveCatalogItem): boolean {
     return !this.isInLibrary(item) || this.reborrowOverrides().has(item.titleId);
     }

   /** Allow re-borrowing a title that is already in the library. */
   allowReborrow(item: OverDriveCatalogItem): void {
     this.reborrowOverrides.update((s) => new Set(s).add(item.titleId));
     }

   /**
    * Reflect a just-completed import in the search results without re-querying OverDrive: link the
    * matching result to the new book and clear any re-borrow override so it shows the in-library state.
    */
   private markResultImported(titleId: string, bookId: number): void {
     this.results.update((items) => items.map((r) => (r.titleId === titleId ? { ...r, bookId } : r)));
     this.reborrowOverrides.update((s) => {
       const next = new Set(s);
       next.delete(titleId);
       return next;
       });
     }

   /** Human-friendly label for an OverDrive format id. */
   formatLabel(formatId: string): string {
     const labels: Record<string, string> = {
       'ebook-epub-open': 'EPUB',
       'ebook-epub-adobe': 'EPUB (Adobe DRM)',
       'ebook-pdf-open': 'PDF',
       'ebook-pdf-adobe': 'PDF (Adobe DRM)',
       };
     return labels[formatId] ?? formatId;
     }

   /** p-select options for a title's available formats (preference order). */
   formatOptions(item: OverDriveCatalogItem): { label: string; value: string }[] {
     return (item.formats ?? []).map((f) => ({ label: this.formatLabel(f), value: f }));
     }

   /** Comma-joined friendly labels of a title's importable formats, for read-only display. */
   formatSummary(item: OverDriveCatalogItem): string {
     return (item.formats ?? []).map((f) => this.formatLabel(f)).join(', ');
     }

   /** The currently chosen format for a title (user selection, else the default/top preference). */
   chosenFormat(item: OverDriveCatalogItem): string | null {
     return this.selectedFormats()[item.titleId] ?? item.formatId ?? item.formats?.[0] ?? null;
     }

   /** Record the user's format choice for a title. */
   setFormat(titleId: string, formatId: string): void {
     this.selectedFormats.update((m) => ({ ...m, [titleId]: formatId }));
     }

   /**
    * Best author label for a loan or hold: sync provides a flat firstCreatorName rather than a
    * creators array, so prefer that and fall back to the first creator name.
    */
   creatorName(item: { firstCreatorName?: string; creators?: OverDriveCreator[] }): string {
     return item.firstCreatorName ?? item.creators?.[0]?.name ?? '';
     }

   /**
    * Whether the user already holds this search-result title. A held title takes precedence over the
    * "not available / no copies" state — we surface the hold rather than offering to place another.
    */
   isOnHold(item: OverDriveCatalogItem): boolean {
     return this.heldHold(item) != null;
     }

   /** The user's hold on this title, if any. */
   private heldHold(item: OverDriveCatalogItem): OverDriveHold | undefined {
     return this.holds().find((h) => h.id === item.titleId);
     }

   /** True when the user's hold on this title is ready to borrow now. */
   isHoldReady(item: OverDriveCatalogItem): boolean {
     return this.heldHold(item)?.ready === true;
     }

   /** Borrowable right now: catalog-available, or a ready hold reserved for the user. */
   borrowableNow(item: OverDriveCatalogItem): boolean {
     return item.available || this.isHoldReady(item);
     }

   /** Estimated wait (days) for the user's hold on a title, or null. */
   holdWaitDays(item: OverDriveCatalogItem): string | null {
     return this.heldHold(item)?.estimatedWaitDays ?? null;
     }

   /**
    * Import an already-borrowed loan into grimmory server-side (borrow-and-import resumes the existing
    * loan). Uses the destination chosen in Search & Borrow, or drops into Bookdrop when none is set.
    */
   onImportLoan(loan: OverDriveLoan): void {
     const card = this.selectedCard();
     if (!card) return;
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     this.importingTitleId.set(loan.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(card.cardId, {
       titleId: loan.id,
       libraryId: library?.id ?? null,
       pathId: path?.id ?? null,
       title: loan.title,
       author: this.creatorName(loan) || undefined,
       coverUrl: loan.coverUrl || undefined,
       formatId: loan.formatId
     }).subscribe({
       next: (book) => {
         const detail = book?.id != null
           ? `"${loan.title}" imported (book #${book.id})`
           : `"${loan.title}" dropped into Bookdrop for review`;
         this.messageService.add({ severity: 'success', summary: book?.id != null ? 'Imported' : 'Sent to Bookdrop', detail });
         this.setOutcome(loan.id, 'success');
         this.importingTitleId.set(null);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Import failed'));
         this.setOutcome(loan.id, 'error');
         this.importingTitleId.set(null);
         }
       });
     }

   /** Borrow a ready hold: redeems it into a loan and imports it (or drops to Bookdrop). */
   onBorrowHold(hold: OverDriveHold): void {
     const card = this.selectedCard();
     if (!card) return;
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     this.importingTitleId.set(hold.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(card.cardId, {
       titleId: hold.id,
       libraryId: library?.id ?? null,
       pathId: path?.id ?? null,
       title: hold.subtitle ? `${hold.title}: ${hold.subtitle}` : hold.title,
       author: this.creatorName(hold) || undefined,
       coverUrl: hold.coverUrl || undefined
     }).subscribe({
       next: (book) => {
         const detail = book?.id != null
           ? `"${hold.title}" borrowed and imported (book #${book.id})`
           : `"${hold.title}" borrowed and dropped into Bookdrop for review`;
         this.messageService.add({ severity: 'success', summary: book?.id != null ? 'Imported' : 'Sent to Bookdrop', detail });
         this.setOutcome(hold.id, 'success');
         this.importingTitleId.set(null);
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow failed'));
         this.setOutcome(hold.id, 'error');
         this.importingTitleId.set(null);
         this.onLoadLoans();
         }
       });
     }

   onPlaceHold(item: OverDriveCatalogItem): void {
     const card = this.selectedCard();
     if (!card) {
       this.error.set('Connect a Libby account and select a card first');
       return;
       }
     if (!item.titleId) {
       return;
       }
     this.error.set(null);
     this.overdriveService.placeHold(card.cardId, item.titleId).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Hold placed',
           detail: `Placed a hold on "${item.title}"`
          });
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Place hold failed'));
         }
      });
     }

   onCancelHold(hold: OverDriveHold): void {
     const card = this.selectedCard();
     if (!card) return;

     this.overdriveService.cancelHold(card.cardId, hold.id).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Hold cancelled',
           detail: `Cancelled the hold on "${hold.title}"`
          });
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Cancel hold failed'));
         }
      });
     }

   onReturn(loanId: string): void {
     const card = this.selectedCard();
     if (!card) return;

     this.overdriveService.returnBook(card.cardId, loanId).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Returned',
           detail: 'Book returned successfully'
          });
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Return failed'));
         }
      });
     }

   onFulfill(loanId: string): void {
     const card = this.selectedCard();
     if (!card) return;

     this.overdriveService.fulfill(card.cardId, loanId).subscribe({
       next: (result) => {
         if (result.acsmBase64) {
            // Decode and download the ACSM file
           const byteCharacters = atob(result.acsmBase64);
           const byteNumbers = new Uint8Array(byteCharacters.length);
           for (let i = 0; i < byteCharacters.length; i++) {
             byteNumbers[i] = byteCharacters.charCodeAt(i);
             }
           const blob = new Blob([byteNumbers], { type: 'application/smil+xml' });
           const url = URL.createObjectURL(blob);
           const a = document.createElement('a');
           a.href = url;
           a.download = `${loanId}.acsm`;
           a.click();
           URL.revokeObjectURL(url);

           this.messageService.add({
             severity: 'success',
             summary: 'Downloaded',
             detail: 'ACSM downloaded'
            });
           }
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Fulfill failed'));
         }
      });
     }

   clearError(): void {
     this.error.set(null);
     }

   /** Prefer the backend's structured error body message, falling back to a generic string. */
   private errorMessage(err: unknown, fallback: string): string {
     if (err instanceof HttpErrorResponse) {
       return err.error?.message || err.message || fallback;
     }
     if (err instanceof Error) {
       return err.message || fallback;
     }
     return fallback;
   }

   formatDate(dateStr: string | null | undefined): string {
     if (!dateStr) return '—';
     try {
       return new Date(dateStr).toLocaleDateString();
       } catch {
       return dateStr;
       }
     }
}
