import {TestBed} from '@angular/core/testing';
import {beforeEach, afterEach, describe, expect, it, vi} from 'vitest';
import {of} from 'rxjs';

import {MessageService} from 'primeng/api';
import {OverdriveCatalogComponent} from './overdrive-catalog.component';
import {OverDriveService, OverDriveCard, OverDriveCatalogItem, OverDriveSyncResult} from '../../core/services/overdrive.service';
import {LibraryService} from '../../features/book/service/library.service';

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
    capabilities: vi.fn(() => of({acsmHandlerConfigured: false, credentialStorageEnabled: false})),
    sync: vi.fn(),
    search: vi.fn(),
  };
  const libraryService = {libraries: () => []};

  let component: OverdriveCatalogComponent;

  beforeEach(() => {
    vi.clearAllMocks();
    overdriveService.cards.mockReturnValue(of([]));
    overdriveService.capabilities.mockReturnValue(of({acsmHandlerConfigured: false, credentialStorageEnabled: false}));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {provide: OverDriveService, useValue: overdriveService},
        {provide: LibraryService, useValue: libraryService},
        {provide: MessageService, useValue: {add: vi.fn()}},
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

  it('defaults the borrow card to the eligible card with the fewest current loans', () => {
    setup({lapl: {loanCount: 4, loanLimit: 10}, bpl: {loanCount: 1, loanLimit: 10}});
    const it1 = item({
      available: true,
      availability: [
        {libraryKey: 'lapl', available: true, holdable: false},
        {libraryKey: 'bpl', available: true, holdable: false},
      ],
    });
    expect(component.chosenCardId(it1)).toBe('c2'); // bpl has fewer loans
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

  it('scopes the search request to the selected card ids', () => {
    const {c1, c2} = setup();
    overdriveService.search.mockReturnValue(of([]));
    component.searchQuery.set('dune');
    component.onSearch();
    expect(overdriveService.search).toHaveBeenCalledWith('dune', [c1.cardId, c2.cardId]);
  });
});
