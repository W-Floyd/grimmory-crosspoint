import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { OverDriveService, OverDriveCard, OverDriveCatalogItem, OverDriveCreator, OverDriveHold, OverDriveLibrary, OverDriveLibraryAvailability, OverDriveLoan, OverDriveSyncResult } from '../../core/services/overdrive.service';

import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { SelectModule } from 'primeng/select';
import { MultiSelectModule } from 'primeng/multiselect';
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
    MultiSelectModule,
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
  // Loans/holds aggregated across the selected cards; each row is tagged with its source cardId.
  loans = signal<OverDriveLoan[]>([]);
  holds = signal<OverDriveHold[]>([]);
  libraries = signal<OverDriveLibrary[]>([]);
  // Time of the last successful loans/holds sync, so the user knows how current the data is.
  lastSynced = signal<Date | null>(null);
  // Active tab on the catalog (search / loans / holds).
  activeTab = signal<string | number>('search');

   // Linked Libby cards (per user). The checkbox-selected cards form the set of libraries to search
   // and the pool of eligible cards to borrow/hold with. Linking/unlinking and diagnostics live on the
   // OverDrive settings page — this page is browse/borrow only.
  cards = signal<OverDriveCard[]>([]);
  selectedCards = signal<OverDriveCard[]>([]);
  // Per-card sync result (loans/holds + loan/hold counts vs. limits), keyed by cardId.
  cardSyncs = signal<Map<string, OverDriveSyncResult>>(new Map());
  // Holds tab: per-hold availability at the user's OTHER libraries (keyed by hold/title id), populated
  // on demand via the "check other libraries" button. Absent key = not checked yet.
  holdAvailability = signal<Record<string, OverDriveLibraryAvailability[]>>({});
  checkingHoldId = signal<string | null>(null);

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
  // Per-title chosen card to borrow/hold with (titleId → cardId); defaults to the eligible card with
  // the fewest current loans (borrow) / holds (hold).
  selectedActionCard = signal<Record<string, string>>({});
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

   /** Short card label (name or id) for compact per-row dropdowns / table cells. */
   shortCardLabel(cardId: string | null | undefined): string {
     if (!cardId) return '';
     const card = this.cards().find(c => c.cardId === cardId);
     return card?.name || cardId;
   }

   /** Deep link to a title on libbyapp.com for a given library key, or null. */
   libbyUrl(titleId: string, libraryKey: string | null | undefined): string | null {
     return libraryKey && titleId ? `https://libbyapp.com/library/${libraryKey}/everything/page-1/${titleId}` : null;
   }

   cardLibraryKey(cardId: string | null | undefined): string | null {
     if (!cardId) return null;
     return this.cards().find(c => c.cardId === cardId)?.libraryKey ?? null;
   }

   /** Record a row's borrow/import/hold outcome for its inline status indicator. */
   private setOutcome(id: string, outcome: 'success' | 'error'): void {
     this.actionOutcome.update((m) => ({ ...m, [id]: outcome }));
   }

   // --- Per-card counts (from each selected card's sync) ---

   loanCountFor(cardId: string): number | null { return this.cardSyncs().get(cardId)?.loanCount ?? null; }
   holdCountFor(cardId: string): number | null { return this.cardSyncs().get(cardId)?.holdCount ?? null; }
   private loanLimitFor(cardId: string): number | null { return this.cardSyncs().get(cardId)?.loanLimit ?? null; }
   private holdLimitFor(cardId: string): number | null { return this.cardSyncs().get(cardId)?.holdLimit ?? null; }
   private canPlaceHoldsFor(cardId: string): boolean { return this.cardSyncs().get(cardId)?.canPlaceHolds ?? true; }

   /** True when a card has reached its loan limit — borrowing on it is blocked until a loan is returned. */
   atLoanLimitFor(cardId: string): boolean {
     const c = this.loanCountFor(cardId);
     const l = this.loanLimitFor(cardId);
     return c !== null && l !== null && c >= l;
   }

   /** True when a card can't place more holds (at its hold limit, or holds are disabled). */
   atHoldLimitFor(cardId: string): boolean {
     if (!this.canPlaceHoldsFor(cardId)) return true;
     const c = this.holdCountFor(cardId);
     const l = this.holdLimitFor(cardId);
     return c !== null && l !== null && c >= l;
   }

   /** True when every selected card is at its loan limit (nothing can be borrowed). */
   allAtLoanLimit(): boolean {
     const cards = this.selectedCards();
     return cards.length > 0 && cards.every(c => this.atLoanLimitFor(c.cardId));
   }

   /** True when every selected card is at its hold limit / can't hold. */
   allAtHoldLimit(): boolean {
     const cards = this.selectedCards();
     return cards.length > 0 && cards.every(c => this.atHoldLimitFor(c.cardId));
   }

   // --- Aggregate tab counts across selected cards ---

   private totalCount(pick: (s: OverDriveSyncResult) => number | null | undefined, fallback: number): number {
     const syncs = this.cardSyncs();
     let total = 0; let any = false;
     for (const card of this.selectedCards()) {
       const v = pick(syncs.get(card.cardId) ?? {} as OverDriveSyncResult);
       if (v != null) { total += v; any = true; }
     }
     return any ? total : fallback;
   }

   private totalLimit(pick: (s: OverDriveSyncResult) => number | null | undefined): number | null {
     const syncs = this.cardSyncs();
     let total = 0; let allKnown = this.selectedCards().length > 0;
     for (const card of this.selectedCards()) {
       const v = pick(syncs.get(card.cardId) ?? {} as OverDriveSyncResult);
       if (v == null) { allKnown = false; break; }
       total += v;
     }
     return allKnown ? total : null;
   }

   /** Tab label with combined usage vs. combined limit when known, e.g. "Loans (5 / 20)". */
   loansTabLabel(): string {
     const count = this.totalCount(s => s.loanCount, this.loans().length);
     const limit = this.totalLimit(s => s.loanLimit);
     return limit !== null ? `Loans (${count} / ${limit})` : `Loans (${this.loans().length})`;
   }

   holdsTabLabel(): string {
     const count = this.totalCount(s => s.holdCount, this.holds().length);
     const limit = this.totalLimit(s => s.holdLimit);
     return limit !== null ? `Holds (${count} / ${limit})` : `Holds (${this.holds().length})`;
   }

   /** "Last synced" label: time only when it was today, otherwise date + time to avoid ambiguity. */
   syncedLabel(): string | null {
     const d = this.lastSynced();
     if (!d) return null;
     const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
     const isToday = d.toDateString() === new Date().toDateString();
     return isToday ? time : `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })}, ${time}`;
   }

   /** Load the user's linked cards; select all by default so search works out of the box. */
   private loadCards(): void {
     this.overdriveService.cards().subscribe({
       next: (cards) => {
         this.cards.set(cards ?? []);
         if (cards && cards.length > 0) {
           this.selectedCards.set([...cards]);
           this.applyDestinationDefault();
           this.syncSelectedCards();
         } else {
           this.selectedCards.set([]);
           this.cardSyncs.set(new Map());
         }
       },
       error: () => { /* not connected yet; leave cards empty */ }
     });
   }

   onCardsChange(cards: OverDriveCard[]): void {
     this.selectedCards.set(cards ?? []);
     this.applyDestinationDefault();
     this.syncSelectedCards();
   }

   /** The primary (first selected) card — drives the remembered destination default. */
   private primaryCard(): OverDriveCard | null {
     return this.selectedCards()[0] ?? null;
   }

   /** Pre-select the destination from the primary card's remembered default (resolved against known libraries). */
   private applyDestinationDefault(): void {
     const card = this.primaryCard();
     const libs = this.grimmoryLibraries();
     const library = card?.defaultLibraryId != null ? libs.find(l => l.id === card.defaultLibraryId) ?? null : null;
     this.selectedLibrary.set(library);
     const path = (library && card?.defaultPathId != null)
       ? library.paths?.find(p => p.id === card.defaultPathId) ?? null
       : null;
     this.selectedPath.set(path);
   }

   /** Persist the current destination as the primary card's default (fire-and-forget) and keep it locally. */
   private persistDestinationDefault(): void {
     const card = this.primaryCard();
     if (!card) return;
     const libraryId = this.selectedLibrary()?.id ?? null;
     const pathId = this.selectedPath()?.id ?? null;
     this.overdriveService.setDefaultLibrary(card.cardId, libraryId, pathId).subscribe({
       error: (err: unknown) => this.error.set(this.errorMessage(err, 'Failed to save default library'))
     });
     const updated = { ...card, defaultLibraryId: libraryId, defaultPathId: pathId };
     this.cards.update(cs => cs.map(c => c.cardId === card.cardId ? updated : c));
     this.selectedCards.update(cs => cs.map(c => c.cardId === card.cardId ? updated : c));
   }

   /** Sync every selected card (upfront) so per-card counts, loans and holds are ready. */
   syncSelectedCards(): void {
     const cards = this.selectedCards();
     if (cards.length === 0) {
       this.cardSyncs.set(new Map());
       this.loans.set([]);
       this.holds.set([]);
       this.libraries.set([]);
       return;
     }
     this.loading.set(true);
     this.error.set(null);
     forkJoin(
       cards.map(card => this.overdriveService.sync(card.cardId).pipe(
         catchError(() => of(null))
       ))
     ).subscribe({
       next: (syncs) => {
         const map = new Map<string, OverDriveSyncResult>();
         cards.forEach((card, i) => {
           const s = syncs[i];
           if (s) map.set(card.cardId, s);
         });
         this.cardSyncs.set(map);
         this.rebuildAggregates();
         this.lastSynced.set(new Date());
         this.loading.set(false);
         if (map.size === 0) {
           this.error.set('Failed to sync the selected cards');
         }
       },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Failed to sync'));
         this.loading.set(false);
       }
     });
   }

   /** Rebuild the aggregated loans/holds/libraries from the per-card syncs, tagging rows with their card. */
   private rebuildAggregates(): void {
     const map = this.cardSyncs();
     const loans: OverDriveLoan[] = [];
     const holds: OverDriveHold[] = [];
     const libraries = new Map<string, OverDriveLibrary>();
     for (const card of this.selectedCards()) {
       const s = map.get(card.cardId);
       if (!s) continue;
       for (const l of s.loans ?? []) loans.push({ ...l, cardId: card.cardId });
       for (const h of s.holds ?? []) holds.push({ ...h, cardId: card.cardId });
       for (const lib of s.libraries ?? []) libraries.set(lib.preferredKey, lib);
     }
     this.loans.set(loans);
     this.holds.set(holds);
     this.libraries.set([...libraries.values()]);
     // Holds changed — drop any stale "available elsewhere" results so they're re-checked on demand.
     this.holdAvailability.set({});
   }

   onLibraryChange(library: Library | null): void {
     this.selectedLibrary.set(library);
     // Auto-select the only path, otherwise clear.
     this.selectedPath.set(library?.paths?.length === 1 ? library.paths[0] : null);
     this.persistDestinationDefault();
   }

   onPathChange(path: LibraryPath | null): void {
     this.selectedPath.set(path);
     this.persistDestinationDefault();
   }

   onSearch(): void {
     const query = this.searchQuery().trim();
     if (!query) {
       this.error.set('Enter a search term');
       return;
       }
     if (this.selectedCards().length === 0) {
       this.error.set('Select at least one card to search');
       return;
       }
     this.searching.set(true);
     this.error.set(null);
     this.overdriveService.search(query, this.selectedCards().map(c => c.cardId)).subscribe({
       next: (items) => {
         this.results.set(items ?? []);
         this.selectedActionCard.set({}); // reset per-title card choices to fresh defaults
         this.searching.set(false);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Search failed'));
         this.searching.set(false);
         }
       });
     }

   // --- Eligible cards + default selection ---

   /** True when a per-library availability entry offers a borrow now — a regular copy or a Lucky Day copy. */
   private borrowableAt(a: OverDriveLibraryAvailability): boolean {
     return a.available || (a.luckyDayAvailableCopies ?? 0) > 0;
   }

   /** True when this title has a "Lucky Day" (skip-the-line) copy available now at some selected library. */
   hasLuckyDay(item: OverDriveCatalogItem): boolean {
     return (item.luckyDayAvailableCopies ?? 0) > 0;
   }

   /**
    * Cards (among the selected set) whose library has this title borrowable now — a regular available
    * copy or a Lucky Day copy — and that aren't at their loan limit.
    */
   borrowEligibleCards(item: OverDriveCatalogItem): OverDriveCard[] {
     const keys = new Set((item.availability ?? []).filter(a => this.borrowableAt(a)).map(a => a.libraryKey));
     return this.selectedCards().filter(c => c.libraryKey != null && keys.has(c.libraryKey) && !this.atLoanLimitFor(c.cardId));
   }

   /**
    * Cards (among the selected set) where the user can still place a hold on this title: the library
    * allows holds, the card isn't at its hold limit, and it doesn't already hold the title (so an
    * existing hold at one library doesn't block offering a hold at the others).
    */
   holdEligibleCards(item: OverDriveCatalogItem): OverDriveCard[] {
     const keys = new Set((item.availability ?? []).filter(a => a.holdable).map(a => a.libraryKey));
     const held = this.heldCardIds(item);
     return this.selectedCards().filter(c =>
       c.libraryKey != null && keys.has(c.libraryKey) && !held.has(c.cardId) && !this.atHoldLimitFor(c.cardId));
   }

   /** Card ids (among selected) that already hold this title. */
   private heldCardIds(item: OverDriveCatalogItem): Set<string> {
     return new Set(this.holds().filter(h => h.id === item.titleId && h.cardId).map(h => h.cardId!));
   }

   /** Per-library availability entry for a card's library, if the title carries one. */
   private availabilityForCard(item: OverDriveCatalogItem, card: OverDriveCard | null): OverDriveLibraryAvailability | undefined {
     return card?.libraryKey ? (item.availability ?? []).find(a => a.libraryKey === card.libraryKey) : undefined;
   }

   /** Short label of the library the user currently holds this title at. */
   heldLibraryLabel(item: OverDriveCatalogItem): string {
     const cardId = this.holds().find(h => h.id === item.titleId)?.cardId;
     return cardId ? this.shortCardLabel(cardId) : '';
   }

   /** Estimated wait (days) at the currently-chosen hold library for this title, or null. */
   chosenHoldWaitDays(item: OverDriveCatalogItem): number | null {
     return this.availabilityForCard(item, this.chosenCard(item))?.estimatedWaitDays ?? null;
   }

   /**
    * True when the chosen other library's estimated wait is shorter than the user's existing hold — so
    * placing a hold there (or borrowing) would likely come through sooner.
    */
   soonerElsewhere(item: OverDriveCatalogItem): boolean {
     if (!this.isOnHold(item)) return false;
     const current = Number(this.holds().find(h => h.id === item.titleId)?.estimatedWaitDays);
     const other = this.chosenHoldWaitDays(item);
     return Number.isFinite(current) && other != null && other < current;
   }

   /** The eligible cards for a title given its current action (borrow when available now, else hold). */
   eligibleCards(item: OverDriveCatalogItem): OverDriveCard[] {
     return this.borrowableNow(item) ? this.borrowEligibleCards(item) : this.holdEligibleCards(item);
   }

   /** {label,value} options for a title's eligible-card dropdown (value = cardId). */
   eligibleCardOptions(item: OverDriveCatalogItem): { label: string; value: string }[] {
     return this.eligibleCards(item).map(c => ({ label: c.name || c.cardId, value: c.cardId }));
   }

   /** The default card for a title: fewest loans (borrow) / fewest holds (hold), stable by selection order. */
   private defaultActionCard(item: OverDriveCatalogItem): OverDriveCard | null {
     const borrow = this.borrowableNow(item);
     const eligible = borrow ? this.borrowEligibleCards(item) : this.holdEligibleCards(item);
     if (eligible.length === 0) return null;
     return eligible.reduce((best, c) => {
       const bestCount = (borrow ? this.loanCountFor(best.cardId) : this.holdCountFor(best.cardId)) ?? 0;
       const cCount = (borrow ? this.loanCountFor(c.cardId) : this.holdCountFor(c.cardId)) ?? 0;
       return cCount < bestCount ? c : best;
     });
   }

   /** The chosen card for a title: the user's per-title override if still eligible, else the default. */
   chosenCard(item: OverDriveCatalogItem): OverDriveCard | null {
     const eligible = this.eligibleCards(item);
     const overrideId = this.selectedActionCard()[item.titleId];
     const override = overrideId ? eligible.find(c => c.cardId === overrideId) : undefined;
     return override ?? this.defaultActionCard(item);
   }

   chosenCardId(item: OverDriveCatalogItem): string | null {
     return this.chosenCard(item)?.cardId ?? null;
   }

   setActionCard(titleId: string, cardId: string): void {
     this.selectedActionCard.update(m => ({ ...m, [titleId]: cardId }));
   }

   /** True when a title has no eligible card to borrow/hold (e.g. only available at an unselected library). */
   noEligibleCard(item: OverDriveCatalogItem): boolean {
     return this.eligibleCards(item).length === 0;
   }

   /**
    * Whether an actionable borrow/hold button is shown for this title, so a card must be picked. True
    * when it's borrowable now (available at some selected library, or a ready hold) or holdable and not
    * already on hold. A title can be on hold at one library yet borrowable at another, so this is not
    * suppressed just because the user holds it somewhere.
    */
   needsActionCard(item: OverDriveCatalogItem): boolean {
     return this.borrowableNow(item) || this.holdEligibleCards(item).length > 0;
   }

   onBorrowImport(item: OverDriveCatalogItem): void {
     const card = this.chosenCard(item);
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     if (!card) {
       this.error.set('No eligible card for this title — select a card whose library has it');
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
         this.syncSelectedCards();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow & import failed'));
         this.setOutcome(item.titleId, 'error');
         this.importingTitleId.set(null);
         // The borrow may have placed the loan even when the import step failed (e.g. no importable
         // format) — resync so the placed loan appears in Your Loans (usable in the Libby app).
         this.syncSelectedCards();
         }
       });
     }

   /** Combined title for display/import: "Series: Book" when OverDrive splits the name into a subtitle. */
   fullTitle(item: OverDriveCatalogItem): string {
     return item.subtitle ? `${item.title}: ${item.subtitle}` : item.title;
     }

   /** Borrow a title onto the Libby account without importing it into grimmory. */
   onBorrowOnly(item: OverDriveCatalogItem): void {
     const card = this.chosenCard(item);
     if (!card || !item.titleId) return;
     this.importingTitleId.set(item.titleId);
     this.error.set(null);
     this.overdriveService.borrow(card.cardId, item.titleId).subscribe({
       next: () => {
         this.messageService.add({ severity: 'success', summary: 'Borrowed',
           detail: `"${this.fullTitle(item)}" borrowed to Libby (not imported)` });
         this.setOutcome(item.titleId, 'success');
         this.importingTitleId.set(null);
         this.syncSelectedCards();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow failed'));
         this.setOutcome(item.titleId, 'error');
         this.importingTitleId.set(null);
         this.syncSelectedCards();
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

   /** Borrowable right now: catalog-available, a Lucky Day copy, or a ready hold reserved for the user. */
   borrowableNow(item: OverDriveCatalogItem): boolean {
     return item.available || this.hasLuckyDay(item) || this.isHoldReady(item);
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
     const cardId = loan.cardId;
     if (!cardId) return;
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     this.importingTitleId.set(loan.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(cardId, {
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
     const cardId = hold.cardId;
     if (!cardId) return;
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     this.importingTitleId.set(hold.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(cardId, {
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
         this.syncSelectedCards();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow failed'));
         this.setOutcome(hold.id, 'error');
         this.importingTitleId.set(null);
         this.syncSelectedCards();
         }
       });
     }

   // --- Holds tab: is this held title available at another of my libraries? ---

   /** Other selected cards (libraries) besides the one holding this title. */
   private otherCardsForHold(hold: OverDriveHold): OverDriveCard[] {
     return this.selectedCards().filter(c => c.cardId !== hold.cardId);
   }

   /** True when there are other selected libraries worth checking for this hold. */
   canCheckOtherLibraries(hold: OverDriveHold): boolean {
     return this.otherCardsForHold(hold).length > 0;
   }

   /** True once this hold's other-library availability has been fetched. */
   holdChecked(hold: OverDriveHold): boolean {
     return hold.id in this.holdAvailability();
   }

   /** Query the user's other libraries for this held title's availability. */
   onCheckOtherLibraries(hold: OverDriveHold): void {
     const others = this.otherCardsForHold(hold);
     if (others.length === 0) {
       this.holdAvailability.update(m => ({ ...m, [hold.id]: [] }));
       return;
     }
     this.checkingHoldId.set(hold.id);
     this.error.set(null);
     this.overdriveService.titleAvailability(hold.id, others.map(c => c.cardId)).subscribe({
       next: (avail) => {
         this.holdAvailability.update(m => ({ ...m, [hold.id]: avail ?? [] }));
         this.checkingHoldId.set(null);
       },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Availability check failed'));
         this.checkingHoldId.set(null);
       }
     });
   }

   /**
    * The card (among the user's other selected libraries) where this held title is available to borrow
    * now, or null. Prefers the first available library in selection order.
    */
   availableElsewhere(hold: OverDriveHold): OverDriveCard | null {
     const avails = this.holdAvailability()[hold.id] ?? [];
     const availableKeys = new Set(avails.filter(a => a.available || (a.luckyDayAvailableCopies ?? 0) > 0).map(a => a.libraryKey));
     return this.otherCardsForHold(hold).find(c => c.libraryKey != null && availableKeys.has(c.libraryKey)) ?? null;
   }

   /**
    * The best other library where this held title is holdable with a shorter estimated wait than the
    * user's current hold, or null. Lets the Holds tab offer moving the hold to a sooner queue.
    */
   holdableSoonerElsewhere(hold: OverDriveHold): { card: OverDriveCard; waitDays: number } | null {
     const currentWait = Number(hold.estimatedWaitDays);
     if (!Number.isFinite(currentWait)) return null;
     const avails = this.holdAvailability()[hold.id] ?? [];
     let best: { card: OverDriveCard; waitDays: number } | null = null;
     for (const a of avails) {
       if (!a.holdable || a.estimatedWaitDays == null || a.estimatedWaitDays >= currentWait) continue;
       const card = this.otherCardsForHold(hold).find(c => c.libraryKey === a.libraryKey);
       if (card && (best === null || a.estimatedWaitDays < best.waitDays)) {
         best = { card, waitDays: a.estimatedWaitDays };
       }
     }
     return best;
   }

   /**
    * Move this hold to another library with a shorter estimated wait: place the new hold first, and only
    * cancel the original once the new one succeeds — so a failed placement never loses the current spot.
    */
   onHoldElsewhereAndCancel(hold: OverDriveHold): void {
     const target = this.holdableSoonerElsewhere(hold);
     if (!target || !hold.cardId) return;
     this.importingTitleId.set(hold.id);
     this.error.set(null);
     this.overdriveService.placeHold(target.card.cardId, hold.id).subscribe({
       next: () => {
         this.overdriveService.cancelHold(hold.cardId!, hold.id).subscribe({
           next: () => {
             this.messageService.add({ severity: 'success', summary: 'Hold moved',
               detail: `Placed a hold at ${this.shortCardLabel(target.card.cardId)} (~${target.waitDays}d) and cancelled the original` });
             this.setOutcome(hold.id, 'success');
             this.importingTitleId.set(null);
             this.syncSelectedCards();
           },
           error: () => {
             this.messageService.add({ severity: 'warn', summary: 'Hold placed — original not cancelled',
               detail: `New hold placed at ${this.shortCardLabel(target.card.cardId)} but couldn't cancel the original; cancel it manually.` });
             this.setOutcome(hold.id, 'success');
             this.importingTitleId.set(null);
             this.syncSelectedCards();
           }
         });
       },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Could not place the new hold — original hold kept'));
         this.setOutcome(hold.id, 'error');
         this.importingTitleId.set(null);
       }
     });
   }

   /**
    * Borrow this title from the other library where it's available, and cancel the original hold only
    * after the borrow succeeds — so a failed borrow never loses the user's place in the wait list.
    */
   onBorrowElsewhereAndCancel(hold: OverDriveHold): void {
     const card = this.availableElsewhere(hold);
     if (!card || !hold.cardId) return;
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
         // Borrow succeeded — now cancel the original hold on its own card.
         this.overdriveService.cancelHold(hold.cardId!, hold.id).subscribe({
           next: () => {
             const where = this.shortCardLabel(card.cardId);
             const detail = book?.id != null
               ? `Borrowed "${hold.title}" from ${where} and cancelled the hold`
               : `Borrowed "${hold.title}" from ${where} (sent to Bookdrop) and cancelled the hold`;
             this.messageService.add({ severity: 'success', summary: 'Borrowed', detail });
             this.setOutcome(hold.id, 'success');
             this.importingTitleId.set(null);
             this.syncSelectedCards();
           },
           error: () => {
             // Borrow placed the loan but the hold cancel failed — keep the loan, warn to cancel manually.
             this.messageService.add({ severity: 'warn', summary: 'Borrowed — hold not cancelled',
               detail: `Borrowed "${hold.title}" but couldn't cancel the original hold; cancel it manually.` });
             this.setOutcome(hold.id, 'success');
             this.importingTitleId.set(null);
             this.syncSelectedCards();
           }
         });
       },
       error: (err: unknown) => {
         // Borrow failed → the hold is untouched, so the user keeps their place in line.
         this.error.set(this.errorMessage(err, 'Borrow failed — hold kept'));
         this.setOutcome(hold.id, 'error');
         this.importingTitleId.set(null);
       }
     });
   }

   onPlaceHold(item: OverDriveCatalogItem): void {
     const card = this.chosenCard(item);
     if (!card) {
       this.error.set('No eligible card to hold this title');
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
         this.syncSelectedCards();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Place hold failed'));
         }
      });
     }

   onCancelHold(hold: OverDriveHold): void {
     const cardId = hold.cardId;
     if (!cardId) return;

     this.overdriveService.cancelHold(cardId, hold.id).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Hold cancelled',
           detail: `Cancelled the hold on "${hold.title}"`
          });
         this.syncSelectedCards();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Cancel hold failed'));
         }
      });
     }

   onReturn(loan: OverDriveLoan): void {
     const cardId = loan.cardId;
     if (!cardId) return;

     this.overdriveService.returnBook(cardId, loan.id).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Returned',
           detail: 'Book returned successfully'
          });
         this.syncSelectedCards();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Return failed'));
         }
      });
     }

   onFulfill(loan: OverDriveLoan): void {
     const cardId = loan.cardId;
     if (!cardId) return;

     this.overdriveService.fulfill(cardId, loan.id).subscribe({
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
           a.download = `${loan.id}.acsm`;
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
