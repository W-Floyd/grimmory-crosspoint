import {TestBed} from '@angular/core/testing';
import {beforeEach, afterEach, describe, expect, it, vi} from 'vitest';
import {of} from 'rxjs';

import {MessageService} from 'primeng/api';
import {OverdriveCatalogComponent} from './overdrive-catalog.component';
import {OverDriveService, OverDriveAuditEntry, OverDriveCard, OverDriveCatalogItem, OverDriveSyncResult} from '../../core/services/overdrive.service';
import {LibraryService} from '../../features/book/service/library.service';
import {Library} from '../../features/book/model/library.model';
import {TranslocoService} from '@jsverse/transloco';

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
    capabilities: vi.fn(() => of({acsmHandlerConfigured: false, credentialStorageEnabled: false, audiobookHandlerConfigured: false})),
    sync: vi.fn(),
    search: vi.fn(),
    borrowAndImport: vi.fn(),
    titleAvailability: vi.fn(),
    history: vi.fn(() => of([] as OverDriveAuditEntry[])),
  };
  const libraryService = {libraries: () => []};

  let component: OverdriveCatalogComponent;

  beforeEach(() => {
    vi.clearAllMocks();
    overdriveService.cards.mockReturnValue(of([]));
    overdriveService.capabilities.mockReturnValue(of({acsmHandlerConfigured: false, credentialStorageEnabled: false, audiobookHandlerConfigured: false}));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {provide: OverDriveService, useValue: overdriveService},
        {provide: LibraryService, useValue: libraryService},
        {provide: MessageService, useValue: {add: vi.fn()}},
        {provide: TranslocoService, useValue: {langChanges$: of('en'), getActiveLang: () => 'en'}},
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

  it('warns when the destination library would reject the chosen format', () => {
    setup();
    component.selectedLibrary.set({id: 1, name: 'Books', allowedFormats: ['EPUB']} as unknown as Library);

    const pdf = item({titleId: 't-pdf', formatId: 'ebook-pdf-adobe', formats: ['ebook-pdf-adobe']});
    expect(component.chosenBookType(pdf)).toBe('PDF');
    expect(component.destinationRejectsFormat(pdf)).toBe(true);

    const epub = item({titleId: 't-epub', formatId: 'ebook-epub-open', formats: ['ebook-epub-open']});
    expect(component.destinationRejectsFormat(epub)).toBe(false);

    // No restriction → no warning.
    component.selectedLibrary.set({id: 2, name: 'Anything', allowedFormats: []} as unknown as Library);
    expect(component.destinationRejectsFormat(pdf)).toBe(false);
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

  it('checks every unchecked waiting hold across other libraries in one sweep', () => {
    const {c2} = setup();
    // Two waiting holds on c1; c2 is another selected library to check against.
    component.holds.set([
      {id: 'title-1', title: 'Dune', cardId: 'c1', ready: false},
      {id: 'title-2', title: 'Foundation', cardId: 'c1', ready: false},
    ]);
    overdriveService.titleAvailability.mockReturnValue(
      of([{libraryKey: 'bpl', available: true, holdable: false}]));

    expect(component.uncheckedHolds().map(h => h.id)).toEqual(['title-1', 'title-2']);
    component.onCheckAllOtherLibraries();

    expect(overdriveService.titleAvailability).toHaveBeenCalledTimes(2);
    expect(overdriveService.titleAvailability).toHaveBeenCalledWith('title-1', [c2.cardId]);
    expect(overdriveService.titleAvailability).toHaveBeenCalledWith('title-2', [c2.cardId]);
    // Both holds are now checked → nothing left, spinner cleared.
    expect(component.uncheckedHolds()).toHaveLength(0);
    expect(component.checkingAll()).toBe(false);
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
    expect(overdriveService.search).toHaveBeenCalledWith('dune', [c1.cardId, c2.cardId]);
  });
});
