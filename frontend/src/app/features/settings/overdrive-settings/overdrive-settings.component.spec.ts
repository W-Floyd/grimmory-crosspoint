import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {AppSettings} from '../../../shared/model/app-settings.model';
import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {OverDriveService, OverDriveCard, OverDriveImportDestinations, OverDriveShareUser} from '../../../core/services/overdrive.service';
import {LibraryService} from '../../../features/book/service/library.service';
import {ConfirmationService} from '@openng/optimus-ui/api';
import {OverdriveSettingsComponent} from './overdrive-settings.component';

const overdriveService = {
  cards: vi.fn(() => of([] as OverDriveCard[])),
  capabilities: vi.fn(() => of({acsmHandlerConfigured: false, credentialStorageEnabled: false, audiobookHandlerConfigured: false, magazineHandlerConfigured: false, ebookHandlerConfigured: false})),
  shareableUsers: vi.fn(() => of([] as OverDriveShareUser[])),
  listShares: vi.fn(() => of([] as OverDriveShareUser[])),
  setShares: vi.fn(() => of(void 0)),
  linkCard: vi.fn(() => of([] as OverDriveCard[])),
  importDestinations: vi.fn(() => of({} as OverDriveImportDestinations)),
  setImportDestinations: vi.fn(() => of(void 0)),
  refreshCard: vi.fn(() => of(void 0)),
  removeCard: vi.fn(() => of(void 0)),
};

const confirmationService = {
  confirm: vi.fn((options: {accept?: () => void}) => options.accept?.()),
};

const appSettingsState = signal<AppSettings | null>(null);

function setup(): OverdriveSettingsComponent {
  TestBed.configureTestingModule({
    imports: [OverdriveSettingsComponent],
    providers: [
      {provide: OverDriveService, useValue: overdriveService},
      {provide: AppSettingsService, useValue: {appSettings: () => appSettingsState(), saveSettings: () => of(void 0)}},
      {provide: LibraryService, useValue: {libraries: () => []}},
      {provide: ConfirmationService, useValue: confirmationService},
    ],
  });
  // Render with a stub template so the suite tests component logic without PrimeNG DOM.
  TestBed.overrideComponent(OverdriveSettingsComponent, {set: {template: ''}});
  return TestBed.createComponent(OverdriveSettingsComponent).componentInstance;
}

describe('OverdriveSettingsComponent card sharing', () => {
  beforeEach(() => vi.clearAllMocks());

  it('treats cards as owned unless explicitly shared with you', () => {
    const c = setup();
    expect(c.isOwned({cardId: 'a'})).toBe(true);
    expect(c.isOwned({cardId: 'a', owned: true})).toBe(true);
    expect(c.isOwned({cardId: 'a', owned: false})).toBe(false);
  });

  it('formats the user picker label with name and username', () => {
    const c = setup();
    expect(c.userOptionLabel({userId: 1, username: 'bob', name: 'Bob B'})).toBe('Bob B (bob)');
    expect(c.userOptionLabel({userId: 2, username: 'ann', name: null})).toBe('ann');
  });

  it('opens the share dialog preloaded with the card\'s current shares', () => {
    const c = setup();
    overdriveService.shareableUsers.mockReturnValue(of([{userId: 8, username: 'bob', name: 'Bob'}]));
    overdriveService.listShares.mockReturnValue(of([{userId: 8, username: 'bob', name: 'Bob'}]));

    c.openShareDialog({cardId: 'card-1', name: 'LAPL', owned: true});

    expect(c.shareDialogVisible()).toBe(true);
    expect(c.shareCard()?.cardId).toBe('card-1');
    expect(overdriveService.listShares).toHaveBeenCalledWith('card-1');
    expect(c.shareableUsers()).toHaveLength(1);
    expect(c.selectedShareUserIds()).toEqual([8]);
  });

  it('saves the selected share set and reflects the count on the card', () => {
    const c = setup();
    c.linkedCards.set([{cardId: 'card-1', name: 'LAPL', owned: true, sharedWithCount: 0}]);
    c.shareCard.set(c.linkedCards()[0]);
    c.selectedShareUserIds.set([8, 9]);

    c.saveShares();

    expect(overdriveService.setShares).toHaveBeenCalledWith('card-1', [8, 9]);
    expect(c.shareDialogVisible()).toBe(false);
    expect(c.linkedCards()[0].sharedWithCount).toBe(2);
  });

  it('promotes a chip-only card to card+PIN via linkCard on the card\'s library', () => {
    const c = setup();
    overdriveService.linkCard.mockReturnValue(of([]));
    c.openCredentialDialog({cardId: 'card-1', name: 'JoCo', libraryKey: 'jocolibrary', owned: true, credentialsStored: false});
    expect(c.credDialogVisible()).toBe(true);

    c.credNumber.set('123456');
    c.credPin.set('4321');
    c.saveCredentials();

    expect(overdriveService.linkCard).toHaveBeenCalledWith('jocolibrary', '123456', '4321');
    expect(c.credDialogVisible()).toBe(false);
  });

  it('requires a card number before saving credentials', () => {
    const c = setup();
    c.openCredentialDialog({cardId: 'card-1', libraryKey: 'jocolibrary', owned: true});
    c.credNumber.set('   ');
    c.saveCredentials();

    expect(overdriveService.linkCard).not.toHaveBeenCalled();
    expect(c.credError()).toBeTruthy();
    expect(c.credDialogVisible()).toBe(true);
  });

  it('formats an epoch-seconds token expiry (and handles empty)', () => {
    const c = setup();
    expect(c.formatEpoch(null)).toBe('—');
    expect(c.formatEpoch(1785005163)).not.toBe('—');
  });
});

describe('OverdriveSettingsComponent bulk card actions', () => {
  beforeEach(() => vi.clearAllMocks());

  function withCards(c: OverdriveSettingsComponent): void {
    c.linkedCards.set([
      {cardId: 'a', name: 'A', owned: true, credentialsStored: true, sharedWithCount: 0},
      {cardId: 'b', name: 'B', owned: true, credentialsStored: false, sharedWithCount: 1},
      // Shared with you: no management actions at all, so never selectable.
      {cardId: 'c', name: 'C', owned: false, ownerName: 'Ann'},
    ]);
  }

  it('only offers cards you own for selection', () => {
    const c = setup();
    withCards(c);

    expect(c.selectableCards().map(card => card.cardId)).toEqual(['a', 'b']);

    c.toggleSelectAll();
    expect(c.selectedCardIds()).toEqual(['a', 'b']);
    expect(c.allSelected()).toBe(true);

    c.toggleSelectAll();
    expect(c.selectedCardIds()).toEqual([]);
  });

  it('toggles an individual card in and out of the selection', () => {
    const c = setup();
    withCards(c);

    c.toggleCardSelection(c.linkedCards()[0]);
    expect(c.isSelected(c.linkedCards()[0])).toBe(true);
    expect(c.allSelected()).toBe(false);

    c.toggleCardSelection(c.linkedCards()[0]);
    expect(c.isSelected(c.linkedCards()[0])).toBe(false);
  });

  it('bulk refresh skips cards with no stored card+PIN', () => {
    const c = setup();
    withCards(c);
    c.toggleSelectAll();

    expect(c.refreshableSelectedCards().map(card => card.cardId)).toEqual(['a']);

    c.onBulkRefresh();

    expect(overdriveService.refreshCard).toHaveBeenCalledTimes(1);
    expect(overdriveService.refreshCard).toHaveBeenCalledWith('a');
    expect(c.selectedCardIds()).toEqual([]);
  });

  it('bulk unlink confirms once, removes every selected card, and reports partial failure', () => {
    const c = setup();
    withCards(c);
    c.toggleSelectAll();
    overdriveService.removeCard.mockReturnValueOnce(of(void 0));
    overdriveService.removeCard.mockReturnValueOnce(throwError(() => new Error('boom')));

    c.onBulkUnlink();

    expect(confirmationService.confirm).toHaveBeenCalledTimes(1);
    expect(overdriveService.removeCard).toHaveBeenCalledTimes(2);
    // Only the card that actually unlinked leaves the list — 'b' failed, so it's still linked.
    expect(c.linkedCards().map(card => card.cardId)).toEqual(['b', 'c']);
    expect(c.setupError()).toContain('1 of 2');
  });

  it('bulk unlink does nothing when the confirmation is declined', () => {
    const c = setup();
    withCards(c);
    c.toggleSelectAll();
    confirmationService.confirm.mockImplementationOnce(() => undefined);

    c.onBulkUnlink();

    expect(overdriveService.removeCard).not.toHaveBeenCalled();
    expect(c.linkedCards()).toHaveLength(3);
  });

  it('bulk share applies one user set to every selected card, replacing what they had', () => {
    const c = setup();
    withCards(c);
    c.toggleSelectAll();
    c.openBulkShareDialog();
    // Starts empty: saving replaces sharing, so a merged set would mislead.
    expect(c.selectedShareUserIds()).toEqual([]);
    expect(c.bulkShare()).toBe(true);

    c.selectedShareUserIds.set([8, 9]);
    c.saveShares();

    expect(overdriveService.setShares).toHaveBeenCalledWith('a', [8, 9]);
    expect(overdriveService.setShares).toHaveBeenCalledWith('b', [8, 9]);
    expect(c.linkedCards()[0].sharedWithCount).toBe(2);
    expect(c.linkedCards()[1].sharedWithCount).toBe(2);
    expect(c.shareDialogVisible()).toBe(false);
    expect(c.selectedCardIds()).toEqual([]);
  });

  it('opening the single-card share dialog leaves bulk mode', () => {
    const c = setup();
    withCards(c);
    c.toggleSelectAll();
    c.openBulkShareDialog();
    expect(c.bulkShare()).toBe(true);

    c.openShareDialog(c.linkedCards()[0]);

    expect(c.bulkShare()).toBe(false);
    expect(c.shareCard()?.cardId).toBe('a');
  });
});
