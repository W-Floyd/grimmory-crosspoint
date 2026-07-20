import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { TranslocoService } from '@jsverse/transloco';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { OverDriveService, OverDriveAuditEntry, OverDriveCard, OverDriveCatalogItem, OverDriveCreator, OverDriveHold, OverDriveLibrary, OverDriveLibraryAvailability, OverDriveLoan, OverDriveSearchFilter, OverDriveSyncResult } from '../../core/services/overdrive.service';

import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { SelectModule } from 'primeng/select';
import { MultiSelectModule } from 'primeng/multiselect';
import { CheckboxModule } from 'primeng/checkbox';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { TooltipModule } from 'primeng/tooltip';
import { InputTextModule } from 'primeng/inputtext';
import { TabsModule } from 'primeng/tabs';
import { LibraryService } from '../../features/book/service/library.service';
import { OverdriveTitleCellComponent } from './overdrive-title-cell.component';
import { OverdriveCoverComponent } from './overdrive-cover.component';

@Component({
  selector: 'app-overdrive-catalog',
  standalone: true,
  imports: [
    RouterLink,
    OverdriveTitleCellComponent,
    OverdriveCoverComponent,
    FormsModule,
    ButtonModule,
    MessageModule,
    CardModule,
    TableModule,
    SelectModule,
    MultiSelectModule,
    CheckboxModule,
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
  private readonly transloco = inject(TranslocoService);

  /** The user's active language (2-letter code), used to flag foreign-language search results. */
  private readonly userLanguage = toSignal(this.transloco.langChanges$, { initialValue: this.transloco.getActiveLang() });

   // State
  loading = signal(false);
  error = signal<string | null>(null);
  // Loans/holds aggregated across the selected cards; each row is tagged with its source cardId.
  loans = signal<OverDriveLoan[]>([]);
  holds = signal<OverDriveHold[]>([]);
  libraries = signal<OverDriveLibrary[]>([]);
  // Time of the last successful loans/holds sync, so the user knows how current the data is.
  lastSynced = signal<Date | null>(null);
  // Active tab on the catalog (search / loans / holds / history).
  activeTab = signal<string | number>('search');
  // OverDrive activity history (newest first), loaded when the History tab is opened.
  history = signal<OverDriveAuditEntry[]>([]);
  loadingHistory = signal(false);
  // Whether the Library Cards + capacity section is folded away to give the loans/holds tables room.
  // Remembered across sessions (localStorage); with no saved preference, defaults to folded on short
  // viewports (e.g. mobile) where the tall card would otherwise squeeze the tab tables to nothing.
  private static readonly CARDS_COLLAPSED_KEY = 'overdrive.cardsCollapsed';
  cardsCollapsed = signal(OverdriveCatalogComponent.readCardsCollapsedPref());

  private static readCardsCollapsedPref(): boolean {
    try {
      const v = localStorage.getItem(OverdriveCatalogComponent.CARDS_COLLAPSED_KEY);
      if (v !== null) return v === 'true';
    } catch { /* localStorage unavailable */ }
    return typeof window !== 'undefined' && window.innerHeight < 720;
  }

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
  importingTitleId = signal<string | null>(null);
  // Result window: fetch this many merged results at first, growing by the same step on "load more".
  readonly SEARCH_PAGE_SIZE = 60;
  searchLimit = signal(this.SEARCH_PAGE_SIZE);
  loadingMore = signal(false);

  // Client-side facet filters over the fetched search results (applied by filteredResults).
  filterFormat = signal<'all' | 'ebook' | 'audiobook'>('all');
  filterAvailableNow = signal(false);
  filterMyLanguage = signal(false);
  filterHideAbridged = signal(false);
  // When on, the format/available/language facets are pushed into the search query server-side so a
  // broad query's capped page is narrowed before it returns (abridged has no server facet — always
  // client-side). Whether a prior search has run, so the auto-refetch effect stays quiet until then.
  serverSideFilter = signal(false);
  private searched = false;
  private prevServerSideFilter = false;
  readonly formatFilterOptions = [
    { label: 'All formats', value: 'all' },
    { label: 'Ebooks', value: 'ebook' },
    { label: 'Audiobooks', value: 'audiobook' },
  ];

  // Column sort over the search results (null field = keep the source/relevance order from OverDrive).
  sortField = signal<string | null>(null);
  sortOrder = signal<1 | -1>(1);

  /** Search results with the active facet filters and column sort applied. */
  readonly filteredResults = computed(() => {
    const format = this.filterFormat();
    const availableOnly = this.filterAvailableNow();
    const myLanguageOnly = this.filterMyLanguage();
    const hideAbridged = this.filterHideAbridged();
    const filtered = this.results().filter(item => {
      if (format === 'audiobook' && !item.audiobook) return false;
      if (format === 'ebook' && item.audiobook) return false;
      if (availableOnly && !this.borrowableNow(item)) return false;
      if (myLanguageOnly && this.isForeignLanguage(item)) return false;
      if (hideAbridged && this.isAbridged(item)) return false;
      return true;
    });
    return this.applySort(filtered, this.sortField(), this.sortOrder(), (f, x) => this.resultSortKey(f, x));
  });

  /** Capture a header-click sort from the (custom-sorted) results table into the sort signals. */
  onSortResults(event: { field?: string | string[]; order?: number }): void {
    this.applySortEvent(event, this.sortField, this.sortOrder);
  }

  /** Sort key for a result column: strings for title/author, an availability rank (lower = sooner). */
  private resultSortKey(field: string, item: OverDriveCatalogItem): string | number {
    switch (field) {
      case 'title': return (item.title ?? '').toLowerCase();
      case 'author': return (item.author ?? '').toLowerCase();
      case 'availability': return this.availabilityRank(item);
      default: return '';
    }
  }

  /** Availability ordering: borrowable now first, then shortest hold wait, then holdable, then the rest. */
  private availabilityRank(item: OverDriveCatalogItem): number {
    if (this.borrowableNow(item)) return -1;
    if (item.preRelease) return 3e9;
    if (item.holdable) return item.estimatedWaitDays ?? 1e6;
    return 2e9;
  }

  /** True when at least one facet filter is narrowing the results. */
  filtersActive(): boolean {
    return this.filterFormat() !== 'all' || this.filterAvailableNow()
      || this.filterMyLanguage() || this.filterHideAbridged();
  }

  /** Clear every facet filter. */
  clearFilters(): void {
    this.filterFormat.set('all');
    this.filterAvailableNow.set(false);
    this.filterMyLanguage.set(false);
    this.filterHideAbridged.set(false);
  }

  // ── Loans / Holds tab: per-card filter + column sort ──────────────────────────────────────────
  loanFilterCard = signal<string>('all');
  loanFilterFormat = signal<'all' | 'ebook' | 'audiobook'>('all');
  loanFilterImported = signal<'all' | 'imported' | 'unimported'>('all');
  loanSortField = signal<string | null>(null);
  loanSortOrder = signal<1 | -1>(1);
  readonly importFilterOptions = [
    { label: 'All loans', value: 'all' },
    { label: 'Imported', value: 'imported' },
    { label: 'Not imported', value: 'unimported' },
  ];
  holdFilterCard = signal<string>('all');
  holdFilterReady = signal(false);
  holdSortField = signal<string | null>(null);
  holdSortOrder = signal<1 | -1>(1);

  /** Card options for the loans/holds card filter (only worth showing with >1 selected card). */
  readonly cardFilterOptions = computed(() => [
    { label: 'All cards', value: 'all' },
    ...this.selectedCards().map(c => ({ label: this.shortCardLabel(c.cardId), value: c.cardId })),
  ]);

  /** Loans with the loans-tab card/format filters and column sort applied. */
  readonly filteredLoans = computed(() => {
    const cardId = this.loanFilterCard();
    const format = this.loanFilterFormat();
    const imported = this.loanFilterImported();
    const rows = this.loans().filter(l => {
      if (cardId !== 'all' && l.cardId !== cardId) return false;
      if (format === 'audiobook' && !this.isAudiobookLoan(l)) return false;
      if (format === 'ebook' && this.isAudiobookLoan(l)) return false;
      if (imported === 'imported' && l.bookId == null) return false;
      if (imported === 'unimported' && l.bookId != null) return false;
      return true;
    });
    return this.applySort(rows, this.loanSortField(), this.loanSortOrder(), (f, x) => this.loanSortKey(f, x));
  });

  /** Holds with the holds-tab card/ready filters and column sort applied. */
  readonly filteredHolds = computed(() => {
    const cardId = this.holdFilterCard();
    const readyOnly = this.holdFilterReady();
    const rows = this.holds().filter(h => {
      if (cardId !== 'all' && h.cardId !== cardId) return false;
      if (readyOnly && !h.ready) return false;
      return true;
    });
    return this.applySort(rows, this.holdSortField(), this.holdSortOrder(), (f, x) => this.holdSortKey(f, x));
  });

  onSortLoans(event: { field?: string | string[]; order?: number }): void {
    this.applySortEvent(event, this.loanSortField, this.loanSortOrder);
  }

  onSortHolds(event: { field?: string | string[]; order?: number }): void {
    this.applySortEvent(event, this.holdSortField, this.holdSortOrder);
  }

  loanFiltersActive(): boolean {
    return this.loanFilterCard() !== 'all' || this.loanFilterFormat() !== 'all'
      || this.loanFilterImported() !== 'all';
  }

  holdFiltersActive(): boolean {
    return this.holdFilterCard() !== 'all' || this.holdFilterReady();
  }

  clearLoanFilters(): void {
    this.loanFilterCard.set('all');
    this.loanFilterFormat.set('all');
    this.loanFilterImported.set('all');
  }

  clearHoldFilters(): void {
    this.holdFilterCard.set('all');
    this.holdFilterReady.set(false);
  }

  /** True when a loan is an audiobook (its format is an audiobook-* format). */
  isAudiobookLoan(loan: OverDriveLoan): boolean {
    const id = loan.formatId ?? loan.formats?.[0]?.id ?? '';
    return id.startsWith('audiobook-');
  }

  private loanSortKey(field: string, loan: OverDriveLoan): string | number {
    switch (field) {
      case 'title': return (loan.title ?? '').toLowerCase();
      case 'author': return this.creatorName(loan).toLowerCase();
      case 'card': return this.shortCardLabel(loan.cardId).toLowerCase();
      case 'format': return (this.loanFormat(loan) ?? '').toLowerCase();
      case 'borrowed': return this.dateEpoch(loan.checkoutDate);
      case 'expires': return this.dateEpoch(loan.expireDate);
      default: return '';
    }
  }

  private holdSortKey(field: string, hold: OverDriveHold): string | number {
    switch (field) {
      case 'title': return (hold.title ?? '').toLowerCase();
      case 'author': return this.creatorName(hold).toLowerCase();
      case 'card': return this.shortCardLabel(hold.cardId).toLowerCase();
      case 'placed': return this.dateEpoch(hold.placedDate);
      case 'wait': return hold.ready ? -1 : Number(hold.estimatedWaitDays ?? 1e6);
      default: return '';
    }
  }

  /** Parse an ISO date to epoch ms; missing/invalid sorts last (ascending). */
  private dateEpoch(value: string | null | undefined): number {
    if (!value) return Number.POSITIVE_INFINITY;
    const t = Date.parse(value);
    return Number.isNaN(t) ? Number.POSITIVE_INFINITY : t;
  }

  /** Mirror a PrimeNG custom-sort event into the given field/order signals. */
  private applySortEvent(
    event: { field?: string | string[]; order?: number },
    fieldSignal: { set(v: string | null): void },
    orderSignal: { set(v: 1 | -1): void },
  ): void {
    const field = Array.isArray(event.field) ? event.field[0] : event.field;
    fieldSignal.set(field ?? null);
    orderSignal.set(event.order === -1 ? -1 : 1);
  }

  /** Stable sort of a copy of {@code rows} by a column key; a null field keeps the source order. */
  private applySort<T>(
    rows: T[],
    field: string | null,
    order: 1 | -1,
    key: (field: string, row: T) => string | number,
  ): T[] {
    if (!field) {
      return rows;
    }
    return [...rows].sort((a, b) => {
      const av = key(field, a);
      const bv = key(field, b);
      const cmp = typeof av === 'number' && typeof bv === 'number'
        ? av - bv
        : String(av ?? '').localeCompare(String(bv ?? ''));
      return order * cmp;
    });
  }
  // Per-title chosen download format (titleId → formatId); defaults to the title's top preference.
  selectedFormats = signal<Record<string, string>>({});
  // Per-title chosen card to borrow/hold with (titleId → cardId); defaults to the eligible card with
  // the fewest current loans (borrow) / holds (hold).
  selectedActionCard = signal<Record<string, string>>({});
  // Title ids the user has explicitly chosen to re-borrow despite already being in the library.
  reborrowOverrides = signal<Set<string>>(new Set());
  // Per-row outcome of the last borrow/import/hold action (keyed by titleId / loanId / holdId).
  actionOutcome = signal<Record<string, 'success' | 'error'>>({});
  // Optional per-row success label overriding the table's default word (e.g. "Hold moved" vs "Borrowed").
  actionOutcomeLabel = signal<Record<string, string>>({});
  // Whether an ACSM handler is configured (enables importing Adobe-DRM formats). Shapes the
  // "No supported format" tooltip: no point suggesting ACSM setup when it's already enabled.
  acsmConfigured = signal(false);
  // Whether an audiobook handler is configured server-side (enables audiobook borrows).
  audiobookConfigured = signal(false);

   constructor() {
     // Remember the folded/expanded state of the Library Cards section across sessions.
     effect(() => {
       const collapsed = this.cardsCollapsed();
       try {
         localStorage.setItem(OverdriveCatalogComponent.CARDS_COLLAPSED_KEY, String(collapsed));
       } catch { /* localStorage unavailable */ }
     });
     // While server-side filtering is on, a facet change (or toggling the mode) needs a fresh fetch so
     // the narrowed page reflects it; toggling it back off refetches once to restore the full set.
     effect(() => {
       const on = this.serverSideFilter();
       this.filterFormat();
       this.filterAvailableNow();
       this.filterMyLanguage();
       const toggledOff = this.prevServerSideFilter && !on;
       this.prevServerSideFilter = on;
       if (!this.searched || !(on || toggledOff)) {
         return;
       }
       untracked(() => {
         if (this.searchQuery().trim() && this.selectedCards().length > 0) {
           this.onSearch();
         }
       });
     });
     this.loadCards();
     this.overdriveService.capabilities().subscribe({
       next: (c) => {
         this.acsmConfigured.set(!!c?.acsmHandlerConfigured);
         this.audiobookConfigured.set(!!c?.audiobookHandlerConfigured);
       },
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

   /**
    * Record a row's borrow/import/hold outcome for its inline status indicator. An optional label
    * overrides the table's default success word (e.g. "Hold moved" rather than "Borrowed") so the pill
    * describes what actually happened.
    */
   private setOutcome(id: string, outcome: 'success' | 'error', label?: string): void {
     this.actionOutcome.update((m) => ({ ...m, [id]: outcome }));
     if (label) {
       this.actionOutcomeLabel.update((m) => ({ ...m, [id]: label }));
     }
   }

   /** The success-outcome label for a row, or the given default when none was set. */
   outcomeLabel(id: string, fallback: string): string {
     return this.actionOutcomeLabel()[id] ?? fallback;
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

   /** Loan capacity for a card's at-a-glance bar: count, limit, fill % and whether it's at the limit. */
   loanBar(cardId: string): { count: number | null; limit: number | null; pct: number; atLimit: boolean } {
     const count = this.loanCountFor(cardId);
     const limit = this.loanLimitFor(cardId);
     const pct = count != null && limit != null && limit > 0 ? Math.min(100, (count / limit) * 100) : 0;
     return { count, limit, pct, atLimit: this.atLoanLimitFor(cardId) };
   }

   /** Hold capacity for a card's at-a-glance bar. `canHold` is false when the card can't place holds. */
   holdBar(cardId: string): { count: number | null; limit: number | null; pct: number; atLimit: boolean; canHold: boolean } {
     const count = this.holdCountFor(cardId);
     const limit = this.holdLimitFor(cardId);
     const pct = count != null && limit != null && limit > 0 ? Math.min(100, (count / limit) * 100) : 0;
     return { count, limit, pct, atLimit: this.atHoldLimitFor(cardId), canHold: this.canPlaceHoldsFor(cardId) };
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

   /** Compact "used/limit" (or bare "used") for the folded Library Cards summary, e.g. "20/75". */
   loansSummary(): string {
     const count = this.totalCount(s => s.loanCount, this.loans().length);
     const limit = this.totalLimit(s => s.loanLimit);
     return limit !== null ? `${count}/${limit}` : `${count}`;
   }

   holdsSummary(): string {
     const count = this.totalCount(s => s.holdCount, this.holds().length);
     const limit = this.totalLimit(s => s.holdLimit);
     return limit !== null ? `${count}/${limit}` : `${count}`;
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
     this.syncSelectedCards();
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
     // Keep each still-present hold's "available elsewhere" result so acting on one row doesn't force
     // the user to re-check every other row (and re-hammer the availability endpoint). Only drop entries
     // for holds that are gone (borrowed/cancelled); the acted-upon row clears its own entry explicitly.
     const holdIds = new Set(holds.map(h => h.id));
     this.holdAvailability.update(m => {
       const next: Record<string, OverDriveLibraryAvailability[]> = {};
       for (const [id, avail] of Object.entries(m)) {
         if (holdIds.has(id)) {
           next[id] = avail;
         }
       }
       return next;
     });
   }

   /** Forget one hold's cached "available elsewhere" result so its row re-checks on demand. */
   private clearHoldAvailability(holdId: string): void {
     this.holdAvailability.update(m => {
       if (!(holdId in m)) return m;
       const next = { ...m };
       delete next[holdId];
       return next;
     });
   }

   onSearch(): void {
     // A fresh search resets the result window to the first page size.
     this.performSearch(this.SEARCH_PAGE_SIZE);
   }

   /** Grow the result window by one page and re-fetch (re-merges the larger set across libraries). */
   loadMore(): void {
     this.performSearch(this.searchLimit() + this.SEARCH_PAGE_SIZE, true);
   }

   /** True when the last fetch filled the requested window, so there may be more to load. */
   canLoadMore(): boolean {
     return this.results().length >= this.searchLimit();
   }

   private performSearch(limit: number, isLoadMore = false): void {
     const query = this.searchQuery().trim();
     if (!query) {
       this.error.set('Enter a search term');
       return;
       }
     if (this.selectedCards().length === 0) {
       this.error.set('Select at least one card to search');
       return;
       }
     if (isLoadMore) {
       this.loadingMore.set(true);
     } else {
       this.searching.set(true);
     }
     this.error.set(null);
     this.searchLimit.set(limit);
     const filter = this.serverSideFilter() ? this.serverFilter() : undefined;
     this.overdriveService.search(query, this.selectedCards().map(c => c.cardId), filter, limit).subscribe({
       next: (items) => {
         this.results.set(items ?? []);
         if (!isLoadMore) {
           this.selectedActionCard.set({}); // reset per-title card choices to fresh defaults
         }
         this.searching.set(false);
         this.loadingMore.set(false);
         this.searched = true;
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, isLoadMore ? 'Load more failed' : 'Search failed'));
         this.searching.set(false);
         this.loadingMore.set(false);
         }
       });
     }

   /** The current facet filters expressed as server-side search params (used when serverSideFilter is on). */
   private serverFilter(): OverDriveSearchFilter {
     const filter: OverDriveSearchFilter = {};
     if (this.filterFormat() !== 'all') {
       filter.mediaTypes = this.filterFormat();
     }
     if (this.filterAvailableNow()) {
       filter.availableOnly = true;
     }
     if (this.filterMyLanguage()) {
       const lang = this.userLanguage();
       if (lang) {
         filter.language = lang;
       }
     }
     return filter;
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

   /** True when the result's language differs from the user's active language (both known). */
   isForeignLanguage(item: OverDriveCatalogItem): boolean {
     const lang = item.language?.trim().toLowerCase();
     const user = this.userLanguage()?.trim().toLowerCase();
     return !!lang && !!user && lang !== user;
   }

   /** The result's language code, uppercased for the badge (e.g. "ES"). */
   foreignLanguageLabel(item: OverDriveCatalogItem): string {
     return (item.language ?? '').trim().toUpperCase();
   }

   /**
    * Whether a result looks abridged, matched from its raw OverDrive edition label: the edition mentions
    * "abridged" but not "unabridged". The backend surfaces the edition as-is; this is where we interpret it.
    */
   isAbridged(item: { edition?: string | null }): boolean {
     const edition = item.edition?.toLowerCase() ?? '';
     return edition.includes('abridged') && !edition.includes('unabridged');
   }

   /**
    * Stable row identity for the search/loans/holds tables. Without it, PrimeNG recreates a row's DOM on
    * re-render, which restarts (and visually freezes) the in-row action button's loading spinner while an
    * import/borrow is in flight. Keyed by the title/loan/hold id.
    */
   trackRow = (_: number, row: { titleId?: string; id?: string }): string => row.titleId ?? row.id ?? '';

   /** Narrator name(s) to show after a title, or null when none (shared by search, loans and holds). */
   narratorLabel(item: { narrator?: string | null }): string | null {
     const n = item.narrator?.trim();
     return n ? n : null;
   }

   /** An audiobook's playback length as a compact "Xh Ym" (or "Ym"), from the raw "HH:MM:SS", or null. */
   durationLabel(item: { duration?: string | null }): string | null {
     const raw = item.duration?.trim();
     if (!raw) return null;
     const parts = raw.split(':').map(n => parseInt(n, 10));
     if (parts.some(isNaN)) return null;
     let hours: number;
     let minutes: number;
     if (parts.length === 3) {
       [hours, minutes] = parts;
     } else if (parts.length === 2) {
       [hours, minutes] = [0, parts[0]];
     } else {
       return null;
     }
     if (hours > 0 && minutes > 0) return `${hours}h ${minutes}m`;
     if (hours > 0) return `${hours}h`;
     return `${minutes}m`;
   }

   /** The user's active language code, uppercased. */
   userLanguageLabel(): string {
     return (this.userLanguage() ?? '').trim().toUpperCase();
   }

   /** The per-library availability with the shortest estimated wait for this title (the best case). */
   private shortestWaitAvailability(item: OverDriveCatalogItem): OverDriveLibraryAvailability | null {
     const waited = (item.availability ?? []).filter(a => a.estimatedWaitDays != null);
     if (waited.length === 0) return null;
     return waited.reduce((best, a) => (a.estimatedWaitDays! < best.estimatedWaitDays! ? a : best));
   }

   /** Shortest estimated wait (days) across the title's libraries, for the aggregate availability cell. */
   shortestWaitDays(item: OverDriveCatalogItem): number | null {
     return this.shortestWaitAvailability(item)?.estimatedWaitDays ?? item.estimatedWaitDays ?? null;
   }

   /** Holds count at the shortest-wait library (so the wait and holds describe the same library). */
   shortestWaitHoldsCount(item: OverDriveCatalogItem): number | null {
     const entry = this.shortestWaitAvailability(item);
     return (entry ? entry.holdsCount : item.holdsCount) ?? null;
   }

   /** Per-library availability breakdown (one line each) for the Availability cell's hover tooltip. */
   availabilityBreakdown(item: OverDriveCatalogItem): string {
     return (item.availability ?? [])
       .map(a => `${this.libraryLabelForKey(a.libraryKey)}: ${this.availabilityLineFor(a)}`)
       .join('\n');
   }

   /** Friendly label for a library key: the matching selected card's name, else the key itself. */
   private libraryLabelForKey(libraryKey: string): string {
     const card = this.selectedCards().find(c => c.libraryKey === libraryKey);
     return card ? (card.name || card.cardId) : libraryKey;
   }

   /** One-line availability summary for a single library. */
   private availabilityLineFor(a: OverDriveLibraryAvailability): string {
     if (a.available) {
       const copies = a.availableCopies != null
         ? ` (${a.availableCopies} ${a.availableCopies === 1 ? 'copy' : 'copies'})`
         : '';
       const lucky = (a.luckyDayAvailableCopies ?? 0) > 0 ? ' · Lucky Day' : '';
       return `Available${copies}${lucky}`;
     }
     if ((a.luckyDayAvailableCopies ?? 0) > 0) {
       return 'Lucky Day copy available';
     }
     if (a.holdable) {
       let s = 'Wait list';
       if (a.estimatedWaitDays != null) s += ` · ~${a.estimatedWaitDays} day wait`;
       if (a.holdsCount != null) s += ` · ${a.holdsCount} holds`;
       return s;
     }
     return 'Unavailable';
   }

   /**
    * Cards (among the selected set) whose library has this title borrowable now — a regular available
    * copy or a Lucky Day copy — and that aren't at their loan limit.
    */
   borrowEligibleCards(item: OverDriveCatalogItem): OverDriveCard[] {
     const keys = new Set((item.availability ?? []).filter(a => this.borrowableAt(a)).map(a => a.libraryKey));
     return this.selectedCards()
       .filter(c => c.libraryKey != null && keys.has(c.libraryKey) && !this.atLoanLimitFor(c.cardId))
       // Most remaining loan capacity first (limit - count), so cards with different limits compare
       // fairly — a 14/50 card (36 left) beats a 13/15 card (2 left).
       .sort((a, b) => this.loanRemainingFor(b.cardId) - this.loanRemainingFor(a.cardId));
   }

   /** Remaining loan capacity for a card (limit − count); an unreported limit is treated as unlimited. */
   private loanRemainingFor(cardId: string): number {
     const count = this.loanCountFor(cardId);
     const limit = this.loanLimitFor(cardId);
     return count != null && limit != null ? limit - count : Number.POSITIVE_INFINITY;
   }

   /**
    * Cards (among the selected set) where the user can still place a hold on this title: the library
    * allows holds, the card isn't at its hold limit, and it doesn't already hold the title (so an
    * existing hold at one library doesn't block offering a hold at the others).
    */
   holdEligibleCards(item: OverDriveCatalogItem): OverDriveCard[] {
     const keys = new Set((item.availability ?? []).filter(a => a.holdable).map(a => a.libraryKey));
     const held = this.heldCardIds(item);
     return this.selectedCards()
       .filter(c => c.libraryKey != null && keys.has(c.libraryKey) && !held.has(c.cardId) && !this.atHoldLimitFor(c.cardId))
       .sort((a, b) => this.compareHoldPreference(item, a, b));
   }

   /**
    * Order two eligible cards by which is likely to come available soonest for this title: shortest
    * estimated wait first, then — since the estimate is coarse and ties are common — the library with
    * more owned copies (bigger pool churns faster and gains more from holds ahead lapsing), and finally
    * the card carrying fewest of the user's own holds so no single card fills its hold slots.
    */
   private compareHoldPreference(item: OverDriveCatalogItem, a: OverDriveCard, b: OverDriveCard): number {
     return (this.holdWaitForCard(item, a) - this.holdWaitForCard(item, b))
       || (this.copiesForCard(item, b) - this.copiesForCard(item, a))
       || ((this.holdCountFor(a.cardId) ?? 0) - (this.holdCountFor(b.cardId) ?? 0));
   }

   /** Estimated hold wait (days) at a card's library for this title; unknown waits sort last. */
   private holdWaitForCard(item: OverDriveCatalogItem, card: OverDriveCard): number {
     return this.availabilityForCard(item, card)?.estimatedWaitDays ?? Number.POSITIVE_INFINITY;
   }

   /** Copies the card's library owns of this title; unknown counts sort last (treated as zero). */
   private copiesForCard(item: OverDriveCatalogItem, card: OverDriveCard): number {
     return this.availabilityForCard(item, card)?.ownedCopies ?? 0;
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

   /**
    * The default card for a title: the first eligible card, which is already ordered best-first — most
    * remaining loan capacity for a borrow, shortest estimated wait for a hold.
    */
   private defaultActionCard(item: OverDriveCatalogItem): OverDriveCard | null {
     return this.eligibleCards(item)[0] ?? null;
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

   /** Selected cards whose library has this title borrowable now (regular or Lucky Day), ignoring loan limit. */
   private borrowableCardsIgnoringLimit(item: OverDriveCatalogItem): OverDriveCard[] {
     const keys = new Set((item.availability ?? []).filter(a => this.borrowableAt(a)).map(a => a.libraryKey));
     return this.selectedCards().filter(c => c.libraryKey != null && keys.has(c.libraryKey));
   }

   /** Selected cards whose library allows a hold on this title (not already held there), ignoring hold limit. */
   private holdableCardsIgnoringLimit(item: OverDriveCatalogItem): OverDriveCard[] {
     const keys = new Set((item.availability ?? []).filter(a => a.holdable).map(a => a.libraryKey));
     const held = this.heldCardIds(item);
     return this.selectedCards().filter(c => c.libraryKey != null && keys.has(c.libraryKey) && !held.has(c.cardId));
   }

   /** True when this title is borrowable at a selected library, but every such card is at its loan limit. */
   borrowBlockedByLimit(item: OverDriveCatalogItem): boolean {
     return this.borrowEligibleCards(item).length === 0 && this.borrowableCardsIgnoringLimit(item).length > 0;
   }

   /** True when this title is only holdable, and every card that could hold it is at its hold limit. */
   holdBlockedByLimit(item: OverDriveCatalogItem): boolean {
     return !this.borrowableNow(item) && !this.isOnHold(item)
       && this.holdEligibleCards(item).length === 0 && this.holdableCardsIgnoringLimit(item).length > 0;
   }

   /** Label of a library where this title is borrowable/holdable but blocked by your loan/hold limit. */
   limitBlockedLibraryLabel(item: OverDriveCatalogItem): string {
     const card = this.borrowableCardsIgnoringLimit(item)[0] ?? this.holdableCardsIgnoringLimit(item)[0];
     return card ? this.shortCardLabel(card.cardId) : '';
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
       libraryId: null,
       pathId: null,
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

   /** Whether an unsupported title is an audiobook (its only formats are audiobook-*). */
   private isAudiobookTitle(item: OverDriveCatalogItem): boolean {
     const f = item.formatId ?? item.formats?.[0] ?? null;
     return !!f && f.startsWith('audiobook-');
   }

   /**
    * Why a title has no importable format. Points at the audiobook handler for audiobook titles, and
    * at the ACSM handler for Adobe-DRM ebooks — but only when that handler isn't already configured.
    */
   unsupportedFormatTooltip(item: OverDriveCatalogItem): string {
     if (this.isAudiobookTitle(item)) {
       return 'This is an audiobook. Configure an audiobook handler on the server to borrow and import audiobooks.';
     }
     return this.acsmConfigured()
       ? 'This title isn\'t offered in a format Grimmory can import.'
       : 'This title isn\'t offered in a DRM-free format. Configure an ACSM handler to also import Adobe-DRM formats.';
     }

   /** Warning shown on the borrow button for a title with no importable format. */
   unsupportedBorrowTooltip(item: OverDriveCatalogItem): string {
     return this.unsupportedFormatTooltip(item)
       + ' Borrowing won\'t import it here, but it still places the loan on your Libby account for use in the Libby app.';
     }

   /** Warning shown on the hold button for a title with no importable format. */
   unsupportedHoldTooltip(item: OverDriveCatalogItem): string {
     return this.unsupportedFormatTooltip(item)
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
       'audiobook-mp3': 'Audiobook (MP3)',
       'audiobook-overdrive': 'Audiobook',
       };
     if (labels[formatId]) return labels[formatId];
     if (formatId.startsWith('audiobook-')) return 'Audiobook';
     return formatId;
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

   /** Whether the user already has this search-result title out on loan (on any selected card). */
   isOnLoan(item: OverDriveCatalogItem): boolean {
     return this.loans().some(l => l.id === item.titleId);
     }

   /** Label of the library/card this title is already borrowed on, or ''. */
   loanLibraryLabel(item: OverDriveCatalogItem): string {
     const cardId = this.loans().find(l => l.id === item.titleId)?.cardId;
     return cardId ? this.shortCardLabel(cardId) : '';
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
     this.importingTitleId.set(loan.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(cardId, {
       titleId: loan.id,
       libraryId: null,
       pathId: null,
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
         if (book?.id != null) {
           // Stamp the new book id onto the loan so the title cell shows the "In your library"
           // link and the Import button flips to "Import again".
           this.loans.update((rows) => rows.map((r) => r === loan ? { ...r, bookId: book.id } : r));
         }
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
     this.importingTitleId.set(hold.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(cardId, {
       titleId: hold.id,
       libraryId: null,
       pathId: null,
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

   /** Borrow a ready hold into Libby as a loan without importing (it will appear on the Loans tab). */
   onBorrowHoldOnly(hold: OverDriveHold): void {
     const cardId = hold.cardId;
     if (!cardId) return;
     this.importingTitleId.set(hold.id);
     this.error.set(null);
     this.overdriveService.borrow(cardId, hold.id).subscribe({
       next: () => {
         this.messageService.add({ severity: 'success', summary: 'Borrowed',
           detail: `"${hold.title}" borrowed to Libby (not imported)` });
         this.setOutcome(hold.id, 'success', 'Borrowed');
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

   /** True while the bulk "check all" sweep is in flight. */
   checkingAll = signal(false);

   /** Waiting holds that still have an unchecked other-library lookup available. */
   uncheckedHolds(): OverDriveHold[] {
     return this.holds().filter(h => !h.ready && this.canCheckOtherLibraries(h) && !this.holdChecked(h));
   }

   /**
    * Check every waiting hold against the user's other libraries in one batched request — one call per
    * library covering all titles, rather than a lookup per hold × library. Each hold's result excludes
    * its own library.
    */
   onCheckAllOtherLibraries(): void {
     const pending = this.uncheckedHolds();
     if (pending.length === 0) {
       return;
     }
     this.checkingAll.set(true);
     this.error.set(null);
     const titleIds = [...new Set(pending.map(h => h.id))];
     const cardIds = this.selectedCards().map(c => c.cardId);
     this.overdriveService.titleAvailabilityBatch(titleIds, cardIds).subscribe({
       next: (byTitle) => {
         this.holdAvailability.update(m => {
           const next = { ...m };
           for (const h of pending) {
             const ownKey = this.cardLibraryKey(h.cardId);
             next[h.id] = (byTitle[h.id] ?? []).filter(a => a.libraryKey !== ownKey);
           }
           return next;
         });
         this.checkingAll.set(false);
       },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Availability check failed'));
         this.checkingAll.set(false);
       }
     });
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
     // A Lucky Day copy skips the hold queue but is still a loan — so it, like a regular copy, is only
     // borrowable on a card that isn't already at its loan limit.
     const availableKeys = new Set(avails.filter(a => a.available || (a.luckyDayAvailableCopies ?? 0) > 0).map(a => a.libraryKey));
     return this.otherCardsForHold(hold).find(c =>
       c.libraryKey != null && availableKeys.has(c.libraryKey) && !this.atLoanLimitFor(c.cardId)) ?? null;
   }

   /**
    * The best other library where this held title is holdable with a shorter estimated wait than the
    * user's current hold, or null. Lets the Holds tab offer moving the hold to a sooner queue.
    */
   holdableSoonerElsewhere(hold: OverDriveHold): { card: OverDriveCard; waitDays: number } | null {
     const currentWait = Number(hold.estimatedWaitDays);
     if (!Number.isFinite(currentWait)) return null;
     const avails = this.holdAvailability()[hold.id] ?? [];
     let best: { card: OverDriveCard; waitDays: number; copies: number } | null = null;
     for (const a of avails) {
       if (!a.holdable || a.estimatedWaitDays == null || a.estimatedWaitDays >= currentWait) continue;
       const card = this.otherCardsForHold(hold).find(c => c.libraryKey === a.libraryKey);
       if (!card) continue;
       // Shortest estimated wait wins; equal-wait ties go to the library with more owned copies (it
       // churns faster and gains more from holds ahead lapsing), then to the card with fewest of the
       // user's own holds.
       const copies = a.ownedCopies ?? 0;
       const better = best === null
         || a.estimatedWaitDays < best.waitDays
         || (a.estimatedWaitDays === best.waitDays && copies > best.copies)
         || (a.estimatedWaitDays === best.waitDays && copies === best.copies
             && (this.holdCountFor(card.cardId) ?? 0) < (this.holdCountFor(best.card.cardId) ?? 0));
       if (better) {
         best = { card, waitDays: a.estimatedWaitDays, copies };
       }
     }
     return best && { card: best.card, waitDays: best.waitDays };
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
             this.setOutcome(hold.id, 'success', 'Hold moved');
             this.clearHoldAvailability(hold.id); // its wait changed — re-check just this row, keep others
             this.importingTitleId.set(null);
             this.syncSelectedCards();
           },
           error: () => {
             this.messageService.add({ severity: 'warn', summary: 'Hold placed — original not cancelled',
               detail: `New hold placed at ${this.shortCardLabel(target.card.cardId)} but couldn't cancel the original; cancel it manually.` });
             this.setOutcome(hold.id, 'success', 'Hold placed');
             this.clearHoldAvailability(hold.id);
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
     this.importingTitleId.set(hold.id);
     this.error.set(null);
     this.overdriveService.borrowAndImport(card.cardId, {
       titleId: hold.id,
       libraryId: null,
       pathId: null,
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

   /** Download an audiobook loan as a file via the external handler (the audiobook analogue of ACSM). */
   onDownloadAudiobook(loan: OverDriveLoan): void {
     const cardId = loan.cardId;
     if (!cardId) return;

     this.importingTitleId.set(loan.id);
     this.error.set(null);
     this.overdriveService.downloadAudiobook(cardId, loan.id, loan.formatId).subscribe({
       next: (response) => {
         const blob = response.body;
         if (!blob) {
           this.error.set('Audiobook download returned no data');
           this.importingTitleId.set(null);
           return;
         }
         const url = URL.createObjectURL(blob);
         const a = document.createElement('a');
         a.href = url;
         a.download = this.downloadFilename(response, `${loan.title || loan.id}`);
         a.click();
         URL.revokeObjectURL(url);
         this.messageService.add({ severity: 'success', summary: 'Downloaded', detail: 'Audiobook downloaded' });
         this.importingTitleId.set(null);
       },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Audiobook download failed'));
         this.importingTitleId.set(null);
       },
     });
   }

   /** Filename for a blob download: the server's Content-Disposition name, else a sensible fallback. */
   private downloadFilename(response: HttpResponse<Blob>, fallback: string): string {
     const disposition = response.headers.get('Content-Disposition') ?? '';
     const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
     return match ? decodeURIComponent(match[1]) : fallback;
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

   /** Date + time, for the History tab (actions need finer granularity than a bare date). */
   formatDateTime(dateStr: string | null | undefined): string {
     if (!dateStr) return '—';
     try {
       return new Date(dateStr).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
     } catch {
       return dateStr;
     }
   }

   /** Switch tabs; lazy-load the history the first time the History tab is opened. */
   onTabChange(tab: string | number | undefined): void {
     const next = tab ?? 'search';
     this.activeTab.set(next);
     if (next === 'history') {
       this.loadHistory();
     }
   }

   /** Load the current user's OverDrive activity history. */
   loadHistory(): void {
     this.loadingHistory.set(true);
     this.overdriveService.history().subscribe({
       next: (entries) => { this.history.set(entries ?? []); this.loadingHistory.set(false); },
       error: () => { this.loadingHistory.set(false); }
     });
   }

   private static readonly ACTION_LABELS: Record<string, string> = {
     BORROW: 'Borrowed',
     BORROW_AND_IMPORT: 'Borrowed & imported',
     IMPORT: 'Imported',
     RETURN: 'Returned',
     HOLD_PLACED: 'Hold placed',
     HOLD_CANCELLED: 'Hold cancelled',
     DOWNLOAD: 'Downloaded',
     CARD_LINKED: 'Card linked',
     CARD_UNLINKED: 'Card unlinked',
     CARD_RELABELED: 'Card renamed',
     CARD_REFRESHED: 'Card refreshed',
     SHARE_UPDATED: 'Sharing updated'
   };

   /** Friendly label for a history action code. */
   actionLabel(action: string): string {
     return OverdriveCatalogComponent.ACTION_LABELS[action] ?? action;
   }

   /** CSS modifier class for a history action (colour grouping). */
   actionClass(action: string): string {
     switch (action) {
       case 'BORROW':
       case 'BORROW_AND_IMPORT':
       case 'IMPORT':
       case 'DOWNLOAD':
         return 'borrow';
       case 'RETURN':
       case 'HOLD_CANCELLED':
       case 'CARD_UNLINKED':
         return 'return';
       case 'HOLD_PLACED':
       case 'CARD_LINKED':
       case 'CARD_REFRESHED':
       case 'SHARE_UPDATED':
       case 'CARD_RELABELED':
         return 'neutral';
       default:
         return 'neutral';
     }
   }
}
