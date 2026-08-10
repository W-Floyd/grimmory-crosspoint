import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {ConfirmationService} from '@openng/optimus-ui/api';

import {OverDriveManagedCard, OverDriveService, OverDriveShareUser} from '../../../../core/services/overdrive.service';
import {UserService} from '../../user-management/user.service';
import {OverdriveCardAdminComponent} from './overdrive-card-admin.component';

const overdriveService = {
  allCards: vi.fn(() => of([] as OverDriveManagedCard[])),
  shareableUsers: vi.fn(() => of([] as OverDriveShareUser[])),
  listShares: vi.fn(() => of([] as OverDriveShareUser[])),
  setShares: vi.fn(() => of(void 0)),
  setCardLabel: vi.fn(() => of(void 0)),
  refreshCard: vi.fn(() => of(void 0)),
  removeCard: vi.fn(() => of(void 0)),
  linkCard: vi.fn(() => of([] as unknown[])),
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
  return TestBed.createComponent(OverdriveCardAdminComponent).componentInstance;
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

  it('requires a target user, a library key and a card number before linking', () => {
    const c = setup();
    c.openLinkDialog();

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
});
