import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ConfirmationService} from '@openng/optimus-ui/api';

import {OverDriveBorrowLimits, OverDriveCardBudget, OverDriveManagedCard, OverDriveService, OverDriveShareUser} from '../../../../core/services/overdrive.service';
import {UserService} from '../../user-management/user.service';
import {OverdriveCardAdminComponent} from './overdrive-card-admin.component';

/** Zeroed usage counts, for stubs that need a rate but do not care about it. */
function rate() {
  return {lastMinute: 0, lastHour: 0, lastDay: 0, lastWeek: 0, last30Days: 0};
}

const overdriveService = {
  allCards: vi.fn(() => of([] as OverDriveManagedCard[])),
  shareableUsers: vi.fn(() => of([] as OverDriveShareUser[])),
  listShares: vi.fn(() => of([] as OverDriveShareUser[])),
  setShares: vi.fn(() => of(void 0)),
  setCardLabel: vi.fn(() => of(void 0)),
  refreshCard: vi.fn(() => of(void 0)),
  removeCard: vi.fn(() => of(void 0)),
  cardLimits: vi.fn(() => of([] as OverDriveCardBudget[])),
  setCardLimits: vi.fn((identity: string, limits: OverDriveBorrowLimits) =>
    of({identity, cardName: identity, limits, effective: limits, rate: rate()} as OverDriveCardBudget)),
  defaultCardLimits: vi.fn(() => of({perMinute: 2, perHour: 5, perDay: 10, perWeek: 30, perMonth: 100} as OverDriveBorrowLimits)),
  setDefaultCardLimits: vi.fn((limits: OverDriveBorrowLimits) => of(limits)),
  linkCard: vi.fn(() => of([] as unknown[])),
  redeemSetupCode: vi.fn(() => of([] as unknown[])),
  linkToken: vi.fn(() => of([] as unknown[])),
  resolveLibrary: vi.fn(() => of({valid: true, libraryKey: 'lapl', name: 'Los Angeles PL'})),
};

const confirmationService = {
  // Auto-accept so the destructive path is exercised; a separate test covers declining.
  confirm: vi.fn((options: {accept?: () => void}) => options.accept?.()),
};

let permissions: Record<string, boolean> = {};

function card(overrides: Partial<OverDriveManagedCard> = {}): OverDriveManagedCard {
  return {cardId: 'card-1', name: 'LAPL', ownerUserId: 4, ownerName: 'Ann', ...overrides};
}

function setup(): OverdriveCardAdminComponent {
  // Reset first: some tests call setup() more than once to vary the current user's permissions.
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [OverdriveCardAdminComponent],
    providers: [
      {provide: OverDriveService, useValue: overdriveService},
      {provide: ConfirmationService, useValue: confirmationService},
      {provide: UserService, useValue: {currentUser: () => ({permissions})}},
    ],
  });
  TestBed.overrideComponent(OverdriveCardAdminComponent, {set: {template: ''}});
  const fixture = TestBed.createComponent(OverdriveCardAdminComponent);
  // Run lifecycle: what the component fetches on init is part of its contract now.
  fixture.detectChanges();
  return fixture.componentInstance;
}

describe('OverdriveCardAdminComponent', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = {canManageAllOverdriveCards: true};
  });

  it('renders only for users who may manage every user\'s cards', () => {
    permissions = {canAccessOverdrive: true};
    expect(setup().permitted()).toBe(false);

    permissions = {canManageAllOverdriveCards: true};
    expect(setup().permitted()).toBe(true);

    permissions = {admin: true};
    expect(setup().permitted()).toBe(true);
  });

  it('does not offer to save a row nobody has edited', () => {
    const c = setup();
    const budget = {identity: 'card-a', cardName: 'MCPL', limits: {perHour: 5}, rate: rate()} as OverDriveCardBudget;
    c.budgets.set([budget]);

    expect(c.hasUnsavedLimits(budget)).toBe(false);
    expect(c.limitsFor(budget).perHour).toBe(5);
  });

  it('treats a cleared box as no ceiling rather than a ceiling of zero', () => {
    const c = setup();
    const budget = {identity: 'card-a', limits: {perHour: 5}, rate: rate()} as OverDriveCardBudget;
    c.budgets.set([budget]);

    c.onLimitChange(budget, 'perHour', '');

    // Zero would ground the card; blank means the window is simply not capped.
    expect(c.limitsFor(budget).perHour).toBeNull();
    expect(c.hasUnsavedLimits(budget)).toBe(true);
  });

  it('adopts what the server stored rather than what was typed', () => {
    const c = setup();
    const budget = {identity: 'card-a', limits: {}, rate: rate()} as OverDriveCardBudget;
    c.budgets.set([budget]);
    c.onLimitChange(budget, 'perMonth', '120');

    c.saveLimits(budget);

    expect(overdriveService.setCardLimits).toHaveBeenCalledWith('card-a', {perMonth: 120});
    // The edit is dropped once saved, so the row shows stored state and the Save button goes quiet.
    expect(c.hasUnsavedLimits(budget)).toBe(false);
    expect(c.budgets()[0].limits.perMonth).toBe(120);
  });

  it('keeps the card list usable when the limits call fails', () => {
    overdriveService.cardLimits.mockReturnValueOnce(
      throwError(() => new Error('nope')) as unknown as ReturnType<typeof overdriveService.cardLimits>);
    const c = setup();

    c.load();

    // The card list is what this section is for; a limits failure must not take it down with it.
    expect(c.budgets()).toEqual([]);
    expect(overdriveService.allCards).toHaveBeenCalled();
  });

  it('groups cards by owner and counts what is shown', () => {
    overdriveService.allCards.mockReturnValue(of([
      card({cardId: 'a', ownerUserId: 4, ownerName: 'Ann'}),
      card({cardId: 'b', ownerUserId: 9, ownerName: 'Bob'}),
      card({cardId: 'c', ownerUserId: 4, ownerName: 'Ann'}),
    ]));
    const c = setup();
    c.load();

    expect(c.groups().map(g => `${g.ownerName}:${g.cards.length}`)).toEqual(['Ann:2', 'Bob:1']);
    expect(c.totalShown()).toBe(3);
  });

  it('filters across owner, label, card id and library key', () => {
    overdriveService.allCards.mockReturnValue(of([
      card({cardId: 'a', name: 'Downtown', ownerUserId: 4, ownerName: 'Ann', libraryKey: 'lapl'}),
      card({cardId: 'b', name: 'Suburb', ownerUserId: 9, ownerName: 'Bob', libraryKey: 'jocolibrary'}),
    ]));
    const c = setup();
    c.load();

    c.filter.set('bob');
    expect(c.totalShown()).toBe(1);
    c.filter.set('lapl');
    expect(c.groups()[0].cards[0].cardId).toBe('a');
    c.filter.set('Suburb');
    expect(c.groups()[0].cards[0].cardId).toBe('b');
    c.filter.set('nothing-matches');
    expect(c.groups()).toEqual([]);
  });

  it('surfaces a load failure as an error message', () => {
    overdriveService.allCards.mockReturnValue(throwError(() => ({error: {message: 'Forbidden'}})));
    const c = setup();
    c.load();

    expect(c.error()).toBe('Forbidden');
    expect(c.loaded()).toBe(true);
  });

  it('names the card owner on every management call', () => {
    const c = setup();
    const target = card({cardId: 'card-9', ownerUserId: 7, ownerName: 'Cass'});
    c.cards.set([target]);

    c.onRenameCard(target, ' Nickname ');
    expect(overdriveService.setCardLabel).toHaveBeenCalledWith('card-9', 'Nickname', 7);

    c.onRefreshCard(target);
    expect(overdriveService.refreshCard).toHaveBeenCalledWith('card-9', 7);

    c.onUnlinkCard(target);
    expect(overdriveService.removeCard).toHaveBeenCalledWith('card-9', 7);
  });

  it('confirms before unlinking, and drops the row only once accepted', () => {
    const c = setup();
    const target = card();
    c.cards.set([target]);
    confirmationService.confirm.mockImplementationOnce(() => undefined); // user declines

    c.onUnlinkCard(target);

    expect(overdriveService.removeCard).not.toHaveBeenCalled();
    expect(c.cards()).toHaveLength(1);

    c.onUnlinkCard(target);
    expect(c.cards()).toHaveLength(0);
  });

  it('scopes the share picker to the card owner so the owner is not offered as a target', () => {
    overdriveService.listShares.mockReturnValue(of([{userId: 8, username: 'bob', name: 'Bob'}]));
    const c = setup();

    c.openShareDialog(card({ownerUserId: 4}));

    expect(overdriveService.shareableUsers).toHaveBeenCalledWith(4);
    expect(overdriveService.listShares).toHaveBeenCalledWith('card-1', 4);
    expect(c.selectedShareUserIds()).toEqual([8]);
    expect(c.shareDialogVisible()).toBe(true);
  });

  it('saves shares against the owner and reflects the new count', () => {
    const c = setup();
    const target = card({sharedWithCount: 0});
    c.cards.set([target]);
    c.shareCard.set(target);
    c.selectedShareUserIds.set([8, 9]);

    c.saveShares();

    expect(overdriveService.setShares).toHaveBeenCalledWith('card-1', [8, 9], 4);
    expect(c.cards()[0].sharedWithCount).toBe(2);
    expect(c.shareDialogVisible()).toBe(false);
  });

  it('only patches the row belonging to the acted-on owner when two users linked the same card', () => {
    const c = setup();
    c.cards.set([
      card({cardId: 'dup', ownerUserId: 4, ownerName: 'Ann', name: 'Ann\'s'}),
      card({cardId: 'dup', ownerUserId: 9, ownerName: 'Bob', name: 'Bob\'s'}),
    ]);

    c.onRenameCard(c.cards()[1], 'Renamed');

    expect(c.cards()[0].name).toBe('Ann\'s');
    expect(c.cards()[1].name).toBe('Renamed');
  });

  it('links a card+PIN as the chosen user, not as the manager', () => {
    const c = setup();
    c.openLinkDialog();
    c.linkMethod.set('card-pin');
    c.linkForUserId.set(9);
    c.linkLibraryKey.set(' lapl ');
    c.linkCardNumber.set(' 12345 ');
    c.linkPin.set('4321');

    c.onLinkForUser();

    expect(overdriveService.linkCard).toHaveBeenCalledWith('lapl', '12345', '4321', 9);
    expect(c.linkDialogVisible()).toBe(false);
    // The new rows belong to someone else, so the list is re-fetched rather than patched.
    expect(overdriveService.allCards).toHaveBeenCalled();
  });

  it('redeems a setup code for the chosen user', () => {
    const c = setup();
    c.openLinkDialog();
    // Setup code is the default method: the artifact exists to be passed to another device.
    expect(c.linkMethod()).toBe('setup-code');
    c.linkForUserId.set(9);
    c.linkSetupCode.set(' 12345678 ');

    c.onLinkForUser();

    expect(overdriveService.redeemSetupCode).toHaveBeenCalledWith('12345678', 9);
    expect(c.linkDialogVisible()).toBe(false);
  });

  it('rejects a setup code that is not 8 digits', () => {
    const c = setup();
    c.openLinkDialog();
    c.linkForUserId.set(9);
    c.linkSetupCode.set('1234');

    c.onLinkForUser();

    expect(overdriveService.redeemSetupCode).not.toHaveBeenCalled();
    expect(c.linkError()).toBe('A Libby setup code is 8 digits');
  });

  it('links a pasted identity token for the chosen user', () => {
    const c = setup();
    c.openLinkDialog();
    c.linkMethod.set('token');
    c.linkForUserId.set(9);
    c.linkIdentityToken.set(' eyJhbGciOi.abc.def ');

    c.onLinkForUser();

    expect(overdriveService.linkToken).toHaveBeenCalledWith('eyJhbGciOi.abc.def', 9);
    expect(c.linkDialogVisible()).toBe(false);
  });

  it('requires an identity token when that method is chosen', () => {
    const c = setup();
    c.openLinkDialog();
    c.linkMethod.set('token');
    c.linkForUserId.set(9);

    c.onLinkForUser();

    expect(overdriveService.linkToken).not.toHaveBeenCalled();
    expect(c.linkError()).toBe('Paste the identity token');
  });

  it('requires a target user, a library key and a card number before linking', () => {
    const c = setup();
    c.openLinkDialog();
    c.linkMethod.set('card-pin');

    c.onLinkForUser();
    expect(c.linkError()).toBe('Choose which user this card belongs to');

    c.linkForUserId.set(9);
    c.onLinkForUser();
    expect(c.linkError()).toBe('Enter the library key for this card');

    c.linkLibraryKey.set('lapl');
    c.onLinkForUser();
    expect(c.linkError()).toBe('Card number is required');

    expect(overdriveService.linkCard).not.toHaveBeenCalled();
    expect(c.linkDialogVisible()).toBe(true);
  });

  it('reports an unresolvable library key when checking it', () => {
    const c = setup();
    overdriveService.resolveLibrary.mockReturnValue(throwError(() => new Error('nope')));
    c.linkLibraryKey.set('bogus');

    c.checkLinkKey();

    expect(c.linkKeyResolution()).toEqual({valid: false, libraryKey: 'bogus', name: null});
    expect(c.resolvingLinkKey()).toBe(false);
  });

  it('flags an expired token', () => {
    const c = setup();
    expect(c.isExpired(card({tokenExpiresAt: 1}))).toBe(true);
    expect(c.isExpired(card({tokenExpiresAt: Math.floor(Date.now() / 1000) + 3600}))).toBe(false);
    expect(c.isExpired(card({tokenExpiresAt: null}))).toBe(false);
  });

  it('shows a blank box for an unset window, with the inherited number as its placeholder', () => {
    permissions = {canManageAllOverdriveCards: true};
    const component = setup();
    const budget = {
      identity: 'card-1', cardName: 'LAPL',
      limits: {perMonth: 145},
      effective: {perMinute: 2, perHour: 5, perDay: 10, perWeek: 30, perMonth: 145},
      rate: rate()
    } as OverDriveCardBudget;

    // Overriding one window must not look like it cleared the other four: they are still enforced,
    // just from the default, so the box shows what the card is actually held to.
    expect(component.limitValue(component.limitsFor(budget), 'perMinute')).toBeNull();
    expect(component.inheritedHint(budget, 'perMinute')).toBe('2');
    expect(component.limitValue(component.limitsFor(budget), 'perMonth')).toBe(145);
  });

  it('reads an opted-out window as none rather than as a negative number', () => {
    permissions = {canManageAllOverdriveCards: true};
    const component = setup();
    const budget = {
      identity: 'card-1', limits: {perMinute: -1, perHour: -1, perDay: -1, perWeek: -1, perMonth: -1},
      effective: {}, rate: rate()
    } as OverDriveCardBudget;

    // -1 is how the opt-out is stored, not something a person should have to type or read.
    expect(component.limitValue(component.limitsFor(budget), 'perMinute')).toBeNull();
    expect(component.inheritedHint(budget, 'perMinute')).toBe('none');
    expect(component.hasNoLimits(budget)).toBe(true);
  });

  it('clears back to inheriting rather than to unlimited when no limits is switched off', () => {
    permissions = {canManageAllOverdriveCards: true};
    const component = setup();
    const budget = {
      identity: 'card-1', limits: {perMinute: -1, perHour: -1, perDay: -1, perWeek: -1, perMonth: -1},
      effective: {}, rate: rate()
    } as OverDriveCardBudget;

    component.toggleNoLimits(budget, false);

    // Turning the switch off must land on the safe state, not leave the card uncapped with an
    // unticked box claiming otherwise.
    expect(component.limitsFor(budget).perMinute).toBeNull();
    expect(component.hasNoLimits(budget)).toBe(false);
  });

  it('re-reads every card after the default changes', () => {
    permissions = {canManageAllOverdriveCards: true};
    const component = setup();
    overdriveService.cardLimits.mockClear();

    component.onDefaultLimitChange('perMonth', 120);
    expect(component.hasUnsavedDefault()).toBe(true);
    component.saveDefaultLimits();

    // Every inheriting card is now held to different numbers, and the grid shows those numbers —
    // patching the saved row in place would leave the rest of the table stale.
    expect(overdriveService.setDefaultCardLimits).toHaveBeenCalledWith(
      expect.objectContaining({perMonth: 120}));
    expect(overdriveService.cardLimits).toHaveBeenCalled();
    expect(component.hasUnsavedDefault()).toBe(false);
  });

  it('treats a cleared box as deferring, not as zero', () => {
    permissions = {canManageAllOverdriveCards: true};
    const component = setup();
    const budget = {identity: 'card-1', limits: {perMonth: 145}, effective: {}, rate: rate()} as OverDriveCardBudget;

    component.onLimitChange(budget, 'perMonth', '');

    // Zero would ground the card permanently and the server rejects it; null hands the window back.
    expect(component.limitsFor(budget).perMonth).toBeNull();
  });

  it('reads the default without loading every user\'s cards', () => {
    permissions = {canManageAllOverdriveCards: true};
    overdriveService.defaultCardLimits.mockClear();
    overdriveService.allCards.mockClear();
    const component = setup();

    // The default is one row and governs cards nobody has looked at, including on a server with none
    // linked — so it must not sit behind the button that enumerates every user's cards.
    expect(component.defaultLimits().perMonth).toBe(100);
    expect(overdriveService.defaultCardLimits).toHaveBeenCalled();
    expect(overdriveService.allCards).not.toHaveBeenCalled();
  });

  it('does not fetch the default for a user who cannot set it', () => {
    permissions = {};
    overdriveService.defaultCardLimits.mockClear();
    setup();

    expect(overdriveService.defaultCardLimits).not.toHaveBeenCalled();
  });
});
