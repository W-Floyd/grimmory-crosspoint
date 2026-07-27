import {TestBed} from '@angular/core/testing';
import {beforeEach, afterEach, describe, expect, it, vi} from 'vitest';
import {of} from 'rxjs';

import {MessageService} from '@openng/optimus-ui/api';
import {OverdriveCatalogComponent, toolProgressPct} from './overdrive-catalog.component';
import {OverDriveService, OverDriveAuditEntry, OverDriveCard, OverDriveCatalogItem, OverDriveSyncResult} from '../../core/services/overdrive.service';
import {LibraryService} from '../../features/book/service/library.service';
import {TranslocoService} from '@jsverse/transloco';
import {RxStompService} from '../../shared/websocket/rx-stomp.service';

function card(cardId: string, libraryKey: string, name = cardId): OverDriveCard {
  return {cardId, libraryKey, name};
}

function item(overrides: Partial<OverDriveCatalogItem>): OverDriveCatalogItem {
  return {
    titleId: 'title-1',
    formatId: 'ebook-epub-open',
    title: 'Dune',
    available: false,
    holdable: false,
    preRelease: false,
    availability: [],
    ...overrides,
  };
}

function sync(overrides: Partial<OverDriveSyncResult>): OverDriveSyncResult {
  return {loans: [], holds: [], libraries: [], ...overrides};
}

describe('OverdriveCatalogComponent eligible-card selection', () => {
  const overdriveService = {
    cards: vi.fn(() => of([])),
    capabilities: vi.fn(() => of({acsmHandlerConfigured: false, credentialStorageEnabled: false, audiobookHandlerConfigured: false, magazineHandlerConfigured: false, ebookHandlerConfigured: false})),
    sync: vi.fn(),
    search: vi.fn(),
    borrowAndImport: vi.fn(),
    titleAvailability: vi.fn(),
    titleAvailabilityBatch: vi.fn(),
    history: vi.fn(() => of([] as OverDriveAuditEntry[])),
  };
  const libraryService = {libraries: () => []};

  let component: OverdriveCatalogComponent;

  beforeEach(() => {
    vi.clearAllMocks();
    overdriveService.cards.mockReturnValue(of([]));
    overdriveService.capabilities.mockReturnValue(of({acsmHandlerConfigured: false, credentialStorageEnabled: false, audiobookHandlerConfigured: false, magazineHandlerConfigured: false, ebookHandlerConfigured: false}));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {provide: OverDriveService, useValue: overdriveService},
        {provide: LibraryService, useValue: libraryService},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: TranslocoService, useValue: {langChanges$: of('en'), getActiveLang: () => 'en'}},
        {provide: RxStompService, useValue: {watch: () => of()}},
      ],
    });
    component = TestBed.runInInjectionContext(() => new OverdriveCatalogComponent());
  });

  afterEach(() => TestBed.resetTestingModule());

  /** Wire up two cards on two libraries with the given per-card loan/hold counts. */
  function setup(counts: {lapl?: Partial<OverDriveSyncResult>; bpl?: Partial<OverDriveSyncResult>} = {}) {
    const c1 = card('c1', 'lapl', 'LAPL');
    const c2 = card('c2', 'bpl', 'BPL');
    component.cards.set([c1, c2]);
    component.selectedCards.set([c1, c2]);
    component.cardSyncs.set(new Map([
      ['c1', sync(counts.lapl ?? {loanCount: 0, holdCount: 0})],
      ['c2', sync(counts.bpl ?? {loanCount: 0, holdCount: 0})],
    ]));
    return {c1, c2};
  }

  it('lists only cards whose library has the title available to borrow', () => {
    setup();
    const it1 = item({
      available: true,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true},
        {libraryKey: 'bpl', available: true, holdable: false},
      ],
    });
    expect(component.borrowEligibleCards(it1).map(c => c.cardId)).toEqual(['c2']);
  });

  it('lists only cards whose library allows a hold when the title is unavailable', () => {
    setup();
    const it1 = item({
      available: false,
      holdable: true,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true},
        {libraryKey: 'bpl', available: false, holdable: false},
      ],
    });
    expect(component.holdEligibleCards(it1).map(c => c.cardId)).toEqual(['c1']);
  });

  it('excludes a card that is at its loan limit from borrow eligibility', () => {
    setup({lapl: {loanCount: 5, loanLimit: 5}, bpl: {loanCount: 1, loanLimit: 10}});
    const it1 = item({
      available: true,
      availability: [
        {libraryKey: 'lapl', available: true, holdable: false},
        {libraryKey: 'bpl', available: true, holdable: false},
      ],
    });
    expect(component.borrowEligibleCards(it1).map(c => c.cardId)).toEqual(['c2']);
  });

  it('defaults the borrow card to the one with the most remaining loan capacity', () => {
    // Different limits: lapl 13/15 = 2 left, bpl 14/50 = 36 left → prefer bpl despite MORE loans.
    setup({lapl: {loanCount: 13, loanLimit: 15}, bpl: {loanCount: 14, loanLimit: 50}});
    const it1 = item({
      available: true,
      availability: [
        {libraryKey: 'lapl', available: true, holdable: false},
        {libraryKey: 'bpl', available: true, holdable: false},
      ],
    });
    expect(component.chosenCardId(it1)).toBe('c2');
  });

  it('defaults the hold card to the eligible card with the fewest current holds', () => {
    setup({lapl: {holdCount: 3, holdLimit: 10}, bpl: {holdCount: 0, holdLimit: 10}});
    const it1 = item({
      available: false,
      holdable: true,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true},
        {libraryKey: 'bpl', available: false, holdable: true},
      ],
    });
    expect(component.chosenCardId(it1)).toBe('c2'); // bpl has fewer holds
  });

  it('honors a per-title card override while it remains eligible', () => {
    setup({lapl: {loanCount: 4, loanLimit: 10}, bpl: {loanCount: 1, loanLimit: 10}});
    const it1 = item({
      available: true,
      availability: [
        {libraryKey: 'lapl', available: true, holdable: false},
        {libraryKey: 'bpl', available: true, holdable: false},
      ],
    });
    component.setActionCard(it1.titleId, 'c1');
    expect(component.chosenCardId(it1)).toBe('c1');
  });

  it('flags a title with no eligible card', () => {
    setup();
    const it1 = item({
      available: true,
      availability: [{libraryKey: 'other', available: true, holdable: false}], // no selected card here
    });
    expect(component.noEligibleCard(it1)).toBe(true);
    expect(component.chosenCardId(it1)).toBeNull();
  });

  it('offers a hold at another library even when already on hold at one', () => {
    setup();
    // Already on hold at c1 (lapl); bpl still allows a hold.
    component.holds.set([{id: 'title-1', title: 'Dune', cardId: 'c1'}]);
    const it1 = item({
      available: false,
      holdable: true,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true},
        {libraryKey: 'bpl', available: false, holdable: true},
      ],
    });
    // c1 already holds it → excluded; c2 remains eligible, and an action card is still needed.
    expect(component.holdEligibleCards(it1).map(c => c.cardId)).toEqual(['c2']);
    expect(component.needsActionCard(it1)).toBe(true);
  });

  it('flags when another library estimates a sooner wait than the current hold', () => {
    setup();
    component.holds.set([{id: 'title-1', title: 'Dune', cardId: 'c1', estimatedWaitDays: '40'}]);
    const it1 = item({
      available: false,
      holdable: true,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true, estimatedWaitDays: 40},
        {libraryKey: 'bpl', available: false, holdable: true, estimatedWaitDays: 10},
      ],
    });
    // The only eligible card is c2 (bpl, ~10d) vs the current 40d hold → sooner.
    expect(component.soonerElsewhere(it1)).toBe(true);
    expect(component.chosenHoldWaitDays(it1)).toBe(10);
  });

  it('treats a Lucky Day copy as borrowable now, even with no regular copies', () => {
    setup();
    const it1 = item({
      available: false,
      holdable: true,
      luckyDayAvailableCopies: 1,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true, luckyDayAvailableCopies: 1},
        {libraryKey: 'bpl', available: false, holdable: true, luckyDayAvailableCopies: 0},
      ],
    });
    expect(component.hasLuckyDay(it1)).toBe(true);
    expect(component.borrowableNow(it1)).toBe(true);
    // Only the library with a Lucky Day copy is borrow-eligible.
    expect(component.borrowEligibleCards(it1).map(c => c.cardId)).toEqual(['c1']);
  });

  it('flags when a title is available but you are at your loan limit', () => {
    setup({lapl: {loanCount: 5, loanLimit: 5}, bpl: {loanCount: 0, loanLimit: 10}});
    const it1 = item({
      available: true,
      availability: [{libraryKey: 'lapl', available: true, holdable: false}], // only lapl offers it
    });
    expect(component.borrowEligibleCards(it1)).toEqual([]);
    expect(component.noEligibleCard(it1)).toBe(true);
    expect(component.borrowBlockedByLimit(it1)).toBe(true);
    expect(component.limitBlockedLibraryLabel(it1)).toBe('LAPL');
  });

  it('flags when a title is only holdable but you are at your hold limit', () => {
    setup({lapl: {holdCount: 10, holdLimit: 10}, bpl: {holdCount: 0, holdLimit: 10}});
    const it1 = item({
      available: false,
      holdable: true,
      availability: [{libraryKey: 'lapl', available: false, holdable: true}], // only lapl offers it
    });
    expect(component.holdEligibleCards(it1)).toEqual([]);
    expect(component.holdBlockedByLimit(it1)).toBe(true);
    expect(component.limitBlockedLibraryLabel(it1)).toBe('LAPL');
  });

  it('builds a per-library availability breakdown for the hover tooltip', () => {
    setup();
    const it1 = item({
      available: true,
      availability: [
        {libraryKey: 'lapl', available: true, holdable: false, availableCopies: 1},
        {libraryKey: 'bpl', available: false, holdable: true, estimatedWaitDays: 14, holdsCount: 3},
      ],
    });
    expect(component.availabilityBreakdown(it1)).toBe(
      'LAPL: Available (1 copy)\nBPL: Wait list · ~14 day wait · 3 holds'
    );
  });

  it('computes loan/hold capacity bars per card', () => {
    setup({lapl: {loanCount: 5, loanLimit: 10, holdCount: 10, holdLimit: 10}});
    const loan = component.loanBar('c1');
    expect(loan.count).toBe(5);
    expect(loan.limit).toBe(10);
    expect(loan.pct).toBe(50);
    expect(loan.atLimit).toBe(false);

    const hold = component.holdBar('c1');
    expect(hold.pct).toBe(100);
    expect(hold.atLimit).toBe(true);
    expect(hold.canHold).toBe(true);
  });

  it('orders hold-eligible cards by shortest wait and defaults to the soonest', () => {
    setup();
    const it1 = item({
      available: false,
      holdable: true,
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true, estimatedWaitDays: 40},
        {libraryKey: 'bpl', available: false, holdable: true, estimatedWaitDays: 12},
      ],
    });
    // bpl (12d) sorts before lapl (40d); default is the shortest wait.
    expect(component.holdEligibleCards(it1).map(c => c.cardId)).toEqual(['c2', 'c1']);
    expect(component.chosenCardId(it1)).toBe('c2');
  });

  it('shows the shortest wait across libraries in the availability cell', () => {
    const it1 = item({
      available: false,
      holdable: true,
      estimatedWaitDays: 40, // aggregate/first-library value
      availability: [
        {libraryKey: 'lapl', available: false, holdable: true, estimatedWaitDays: 40, holdsCount: 8},
        {libraryKey: 'bpl', available: false, holdable: true, estimatedWaitDays: 12, holdsCount: 3},
      ],
    });
    expect(component.shortestWaitDays(it1)).toBe(12);
    expect(component.shortestWaitHoldsCount(it1)).toBe(3); // holds from the same (shortest-wait) library
  });

  it('flags a result that is not in the user\'s language', () => {
    // User language is 'en' (mocked TranslocoService).
    expect(component.isForeignLanguage(item({language: 'es'}))).toBe(true);
    expect(component.isForeignLanguage(item({language: 'en'}))).toBe(false);
    expect(component.isForeignLanguage(item({language: null}))).toBe(false);
  });

  it('checks every unchecked waiting hold across other libraries in one batched call', () => {
    const {c1, c2} = setup();
    // Two waiting holds on c1; c2 (bpl) is another selected library to check against.
    component.holds.set([
      {id: 'title-1', title: 'Dune', cardId: 'c1', ready: false},
      {id: 'title-2', title: 'Foundation', cardId: 'c1', ready: false},
    ]);
    overdriveService.titleAvailabilityBatch.mockReturnValue(of({
      // Includes c1's own library (lapl) which must be filtered out per hold, plus bpl (the other lib).
      'title-1': [{libraryKey: 'lapl', available: true, holdable: false}, {libraryKey: 'bpl', available: true, holdable: false}],
      'title-2': [{libraryKey: 'bpl', available: false, holdable: true}],
    }));

    expect(component.uncheckedHolds().map(h => h.id)).toEqual(['title-1', 'title-2']);
    component.onCheckAllOtherLibraries();

    // One batched call for all titles across all selected cards.
    expect(overdriveService.titleAvailabilityBatch).toHaveBeenCalledTimes(1);
    expect(overdriveService.titleAvailabilityBatch).toHaveBeenCalledWith(['title-1', 'title-2'], [c1.cardId, c2.cardId]);
    // title-1's own library (lapl) is filtered out, leaving only bpl.
    expect(component.holdAvailability()['title-1'].map(a => a.libraryKey)).toEqual(['bpl']);
    expect(component.holdAvailability()['title-2'].map(a => a.libraryKey)).toEqual(['bpl']);
    // Both holds are now checked → nothing left, spinner cleared.
    expect(component.uncheckedHolds()).toHaveLength(0);
    expect(component.checkingAll()).toBe(false);
  });

  it('filters loans to those with no copies available at their library (would hold up the queue)', () => {
    const {c1, c2} = setup();
    component.loans.set([
      {id: 'title-1', title: 'Dune', expireDate: '2026-08-08', cardId: 'c1'},        // 0 copies (queued)
      {id: 'title-2', title: 'Foundation', expireDate: '2026-08-08', cardId: 'c1'},  // copies free
      {id: 'title-3', title: 'Hyperion', expireDate: '2026-08-08', cardId: 'c1'},    // 0 copies (no queue yet)
    ]);
    overdriveService.titleAvailabilityBatch.mockReturnValue(of({
      'title-1': [{libraryKey: 'lapl', available: false, holdable: true, availableCopies: 0, holdsCount: 3}],
      'title-2': [{libraryKey: 'lapl', available: true, holdable: false, availableCopies: 2, holdsCount: 0}],
      'title-3': [{libraryKey: 'lapl', available: false, holdable: true, availableCopies: 0, holdsCount: 0}],
    }));

    component.onToggleHoldingQueueFilter(true);

    // One batched call across all selected cards for every loan title.
    expect(overdriveService.titleAvailabilityBatch).toHaveBeenCalledWith(['title-1', 'title-2', 'title-3'], [c1.cardId, c2.cardId]);
    // Any loan with zero available copies at its own library qualifies, queue or not; only the one with
    // free copies is excluded.
    expect(component.filteredLoans().map(l => l.id)).toEqual(['title-1', 'title-3']);
    expect(component.loadingLoanAvailability()).toBe(false);

    // With every loan's availability already cached, a re-fetch makes no new request — so returning one
    // loan (which re-syncs) doesn't re-check the loans still on the shelf.
    overdriveService.titleAvailabilityBatch.mockClear();
    component.fetchLoanAvailability();
    expect(overdriveService.titleAvailabilityBatch).not.toHaveBeenCalled();

    // Clearing the filter restores the full list.
    component.clearLoanFilters();
    expect(component.loanFilterHoldingQueue()).toBe(false);
    expect(component.filteredLoans().map(l => l.id)).toEqual(['title-1', 'title-2', 'title-3']);
  });

  it('lazy-loads history only when the History tab is opened', () => {
    setup();
    overdriveService.history.mockReturnValue(of([
      {id: 1, action: 'BORROW', title: 'Dune', cardName: 'JoCo', success: true, createdAt: '2026-07-18T20:00:00Z'},
    ]));

    component.onTabChange('loans');
    expect(overdriveService.history).not.toHaveBeenCalled();

    component.onTabChange('history');
    expect(component.activeTab()).toBe('history');
    expect(overdriveService.history).toHaveBeenCalledTimes(1);
    expect(component.history()).toHaveLength(1);
  });

  it('maps history action codes to friendly labels and colour groups', () => {
    setup();
    expect(component.actionLabel('BORROW_AND_IMPORT')).toBe('Borrowed & imported');
    expect(component.actionLabel('HOLD_PLACED')).toBe('Hold placed');
    expect(component.actionLabel('WHAT')).toBe('WHAT');
    expect(component.actionClass('BORROW')).toBe('borrow');
    expect(component.actionClass('RETURN')).toBe('return');
    expect(component.actionClass('SHARE_UPDATED')).toBe('neutral');
  });

  it('flags a search result the user already has on loan', () => {
    setup();
    component.loans.set([{id: 'title-1', title: 'Dune', expireDate: '2026-08-08', cardId: 'c1'}]);
    expect(component.isOnLoan(item({titleId: 'title-1'}))).toBe(true);
    expect(component.loanLibraryLabel(item({titleId: 'title-1'}))).toBe('LAPL');
    expect(component.isOnLoan(item({titleId: 'other'}))).toBe(false);
  });

  it('stamps the new book id onto the loan after import so the library link appears', () => {
    setup();
    component.loans.set([{id: 'title-1', title: 'Black', expireDate: '2026-08-08', cardId: 'c1'}]);
    overdriveService.borrowAndImport.mockReturnValue(of({id: 42}));
    component.onImportLoan(component.loans()[0]);
    expect(component.loans()[0].bookId).toBe(42);
  });

  it('leaves the loan without a book id when the import drops to Bookdrop', () => {
    setup();
    component.loans.set([{id: 'title-1', title: 'Black', expireDate: '2026-08-08', cardId: 'c1'}]);
    overdriveService.borrowAndImport.mockReturnValue(of({id: null}));
    component.onImportLoan(component.loans()[0]);
    expect(component.loans()[0].bookId ?? null).toBeNull();
  });

  it('scopes the search request to the selected card ids', () => {
    const {c1, c2} = setup();
    overdriveService.search.mockReturnValue(of([]));
    component.searchQuery.set('dune');
    component.onSearch();
    expect(overdriveService.search).toHaveBeenCalledWith('dune', [c1.cardId, c2.cardId], undefined, 60);
  });

  it('offers load more when the window fills and requests a larger window', () => {
    setup();
    const full = Array.from({length: 60}, (_, i) => item({titleId: `t${i}`}));
    overdriveService.search.mockReturnValue(of(full));
    component.searchQuery.set('dune');
    component.onSearch();
    // A full window (60) means there may be more.
    expect(component.results()).toHaveLength(60);
    expect(component.canLoadMore()).toBe(true);

    overdriveService.search.mockClear();
    component.loadMore();
    // Grows the window by one page and re-fetches.
    expect(overdriveService.search).toHaveBeenLastCalledWith('dune', expect.anything(), undefined, 120);

    // A short window (< requested) means we've reached the end — hide load more.
    overdriveService.search.mockReturnValue(of(full.slice(0, 30)));
    component.loadMore();
    expect(component.canLoadMore()).toBe(false);
  });

  describe('server-side filtering', () => {
    it('sends no filter when the server-side toggle is off', () => {
      setup();
      overdriveService.search.mockReturnValue(of([]));
      component.searchQuery.set('dune');
      component.filterFormat.set('audiobook');
      component.filterAvailableNow.set(true);
      component.onSearch();
      expect(overdriveService.search).toHaveBeenLastCalledWith('dune', expect.anything(), undefined, 60);
    });

    it('pushes the active facets into the query when the toggle is on', () => {
      setup();
      overdriveService.search.mockReturnValue(of([]));
      component.searchQuery.set('dune');
      component.serverSideFilter.set(true);
      component.filterFormat.set('audiobook');
      component.filterAvailableNow.set(true);
      component.filterMyLanguage.set(true);
      component.onSearch();
      expect(overdriveService.search).toHaveBeenLastCalledWith('dune', expect.anything(),
        {mediaTypes: 'audiobook', availableOnly: true, language: 'en'}, 60);
    });

    it('omits untouched facets from the server filter', () => {
      setup();
      overdriveService.search.mockReturnValue(of([]));
      component.searchQuery.set('dune');
      component.serverSideFilter.set(true);
      component.filterFormat.set('ebook');
      component.onSearch();
      expect(overdriveService.search).toHaveBeenLastCalledWith('dune', expect.anything(), {mediaTypes: 'ebook'}, 60);
    });

    it('re-runs the search when a facet changes while server-side filtering is on', () => {
      setup();
      overdriveService.search.mockReturnValue(of([]));
      component.searchQuery.set('dune');
      component.serverSideFilter.set(true);
      component.onSearch(); // establishes a prior search
      overdriveService.search.mockClear();
      component.filterAvailableNow.set(true); // should trigger an auto-refetch
      TestBed.tick(); // flush the auto-refetch effect
      expect(overdriveService.search).toHaveBeenLastCalledWith('dune', expect.anything(), {availableOnly: true}, 60);
    });
  });

  describe('facet filters', () => {
    function results() {
      setup();
      component.results.set([
        item({titleId: 'ebook', audiobook: false, available: true, language: 'en'}),
        item({titleId: 'audio', audiobook: true, available: false, holdable: true, language: 'en'}),
        item({titleId: 'abridged', audiobook: true, available: true, language: 'en', edition: 'Abridged'}),
        item({titleId: 'spanish', audiobook: false, available: true, language: 'es'}),
      ]);
    }

    it('passes everything through when no filter is active', () => {
      results();
      expect(component.filtersActive()).toBe(false);
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['ebook', 'audio', 'abridged', 'spanish']);
    });

    it('filters by format', () => {
      results();
      component.filterFormat.set('audiobook');
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['audio', 'abridged']);
      component.filterFormat.set('ebook');
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['ebook', 'spanish']);
    });

    it('filters to titles available to borrow now', () => {
      results();
      component.filterAvailableNow.set(true);
      // 'audio' is only holdable, so it drops out.
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['ebook', 'abridged', 'spanish']);
    });

    it('filters out foreign-language titles', () => {
      results();
      component.filterMyLanguage.set(true); // user language is 'en'
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['ebook', 'audio', 'abridged']);
    });

    it('hides abridged audiobooks', () => {
      results();
      component.filterHideAbridged.set(true);
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['ebook', 'audio', 'spanish']);
    });

    it('detects abridged from the raw edition label (not "unabridged")', () => {
      expect(component.isAbridged(item({edition: 'Abridged'}))).toBe(true);
      expect(component.isAbridged(item({edition: 'Unabridged'}))).toBe(false);
      expect(component.isAbridged(item({edition: null}))).toBe(false);
      expect(component.isAbridged(item({edition: 'Special Edition'}))).toBe(false);
    });

    it('formats an audiobook duration compactly', () => {
      expect(component.durationLabel(item({duration: '11:05:04'}))).toBe('11h 5m');
      expect(component.durationLabel(item({duration: '01:00:00'}))).toBe('1h');
      expect(component.durationLabel(item({duration: '00:45:12'}))).toBe('45m');
      expect(component.durationLabel(item({duration: null}))).toBeNull();
    });

    it('combines filters and clears them', () => {
      results();
      component.filterFormat.set('audiobook');
      component.filterHideAbridged.set(true);
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['audio']);
      expect(component.filtersActive()).toBe(true);

      component.clearFilters();
      expect(component.filtersActive()).toBe(false);
      expect(component.filteredResults()).toHaveLength(4);
    });
  });

  describe('column sorting', () => {
    function titled() {
      setup();
      component.results.set([
        item({titleId: 'c', title: 'Cain', author: 'Zed', available: false, holdable: true, estimatedWaitDays: 5}),
        item({titleId: 'a', title: 'Abel', author: 'Yan', available: true}),
        item({titleId: 'b', title: 'Baker', author: 'Xor', available: false, holdable: true, estimatedWaitDays: 2}),
      ]);
    }

    it('keeps source order until a column is chosen', () => {
      titled();
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['c', 'a', 'b']);
    });

    it('sorts by title ascending and descending', () => {
      titled();
      component.onSortResults({field: 'title', order: 1});
      expect(component.filteredResults().map(r => r.title)).toEqual(['Abel', 'Baker', 'Cain']);
      component.onSortResults({field: 'title', order: -1});
      expect(component.filteredResults().map(r => r.title)).toEqual(['Cain', 'Baker', 'Abel']);
    });

    it('sorts by author', () => {
      titled();
      component.onSortResults({field: 'author', order: 1});
      expect(component.filteredResults().map(r => r.author)).toEqual(['Xor', 'Yan', 'Zed']);
    });

    it('sorts by availability (borrowable first, then shortest wait)', () => {
      titled();
      component.onSortResults({field: 'availability', order: 1});
      // 'a' available now (-1), then 'b' (wait 2), then 'c' (wait 5).
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['a', 'b', 'c']);
    });

    it('sorts within the filtered set only', () => {
      titled();
      component.filterAvailableNow.set(true);
      component.onSortResults({field: 'title', order: 1});
      expect(component.filteredResults().map(r => r.titleId)).toEqual(['a']);
    });
  });

  describe('loans filter + sort', () => {
    function loans() {
      setup();
      component.loans.set([
        {id: 'l1', title: 'Cain', firstCreatorName: 'Zed', expireDate: '2026-08-10', checkoutDate: '2026-07-01', cardId: 'c1', formatId: 'audiobook-mp3', bookId: 42},
        {id: 'l2', title: 'Abel', firstCreatorName: 'Yan', expireDate: '2026-08-01', checkoutDate: '2026-07-05', cardId: 'c2', formatId: 'ebook-epub-open'},
        {id: 'l3', title: 'Baker', firstCreatorName: 'Xor', expireDate: '2026-08-20', checkoutDate: '2026-07-03', cardId: 'c1', formatId: 'ebook-epub-adobe'},
      ]);
    }

    it('filters loans by card and by format', () => {
      loans();
      component.loanFilterCard.set('c1');
      expect(component.filteredLoans().map(l => l.id)).toEqual(['l1', 'l3']);
      component.loanFilterFormat.set('audiobook');
      expect(component.filteredLoans().map(l => l.id)).toEqual(['l1']);
      expect(component.loanFiltersActive()).toBe(true);
      component.clearLoanFilters();
      expect(component.filteredLoans()).toHaveLength(3);
    });

    it('filters loans by import status', () => {
      loans();
      component.loanFilterImported.set('imported');
      expect(component.filteredLoans().map(l => l.id)).toEqual(['l1']);
      component.loanFilterImported.set('unimported');
      expect(component.filteredLoans().map(l => l.id)).toEqual(['l2', 'l3']);
      expect(component.loanFiltersActive()).toBe(true);
      component.clearLoanFilters();
      expect(component.filteredLoans()).toHaveLength(3);
    });

    it('sorts loans by title and by borrowed date', () => {
      loans();
      component.onSortLoans({field: 'title', order: 1});
      expect(component.filteredLoans().map(l => l.title)).toEqual(['Abel', 'Baker', 'Cain']);
      component.onSortLoans({field: 'borrowed', order: 1});
      expect(component.filteredLoans().map(l => l.id)).toEqual(['l1', 'l3', 'l2']);
    });
  });

  describe('holds filter + sort', () => {
    function holds() {
      setup();
      component.holds.set([
        {id: 'h1', title: 'Cain', firstCreatorName: 'Zed', estimatedWaitDays: '9', placedDate: '2026-07-01', cardId: 'c1'},
        {id: 'h2', title: 'Abel', firstCreatorName: 'Yan', ready: true, placedDate: '2026-07-05', cardId: 'c2'},
        {id: 'h3', title: 'Baker', firstCreatorName: 'Xor', estimatedWaitDays: '3', placedDate: '2026-07-03', cardId: 'c1'},
      ]);
    }

    it('filters holds by card and ready-only', () => {
      holds();
      component.holdFilterCard.set('c1');
      expect(component.filteredHolds().map(h => h.id)).toEqual(['h1', 'h3']);
      component.clearHoldFilters();
      component.holdFilterReady.set(true);
      expect(component.filteredHolds().map(h => h.id)).toEqual(['h2']);
      expect(component.holdFiltersActive()).toBe(true);
    });

    it('sorts holds by wait (ready first, then shortest)', () => {
      holds();
      component.onSortHolds({field: 'wait', order: 1});
      // ready 'h2' first, then wait 3 ('h3'), then wait 9 ('h1').
      expect(component.filteredHolds().map(h => h.id)).toEqual(['h2', 'h3', 'h1']);
    });
  });

  describe('toolProgressPct', () => {
    it('uses an explicit pct when the tool sends one', () => {
      expect(toolProgressPct({pct: 42})).toBe(42);
    });

    it('derives a pct from current/total', () => {
      expect(toolProgressPct({current: 50, total: 200})).toBe(25);
    });

    it('clamps overshoot to 100', () => {
      // Handlers size the download against an *estimated* total (the audiobook tool reports
      // "228.2 MB / ~228.2 MB" against a real 217.6 MB), so current can exceed total.
      expect(toolProgressPct({current: 228, total: 217})).toBe(100);
      expect(toolProgressPct({pct: 137})).toBe(100);
    });

    it('clamps negatives to 0', () => {
      expect(toolProgressPct({pct: -5})).toBe(0);
    });

    it('returns null when there is no usable figure', () => {
      expect(toolProgressPct({})).toBeNull();
      expect(toolProgressPct({current: 5})).toBeNull();
      // total 0 would divide to Infinity rather than a percentage.
      expect(toolProgressPct({current: 5, total: 0})).toBeNull();
    });
  });

});
