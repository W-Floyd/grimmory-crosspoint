import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';

import {AppSettings} from '../../../shared/model/app-settings.model';
import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {OverDriveService, OverDriveCard, OverDriveImportDestinations, OverDriveShareUser} from '../../../core/services/overdrive.service';
import {LibraryService} from '../../../features/book/service/library.service';
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
};

const appSettingsState = signal<AppSettings | null>(null);

function setup(): OverdriveSettingsComponent {
  TestBed.configureTestingModule({
    imports: [OverdriveSettingsComponent],
    providers: [
      {provide: OverDriveService, useValue: overdriveService},
      {provide: AppSettingsService, useValue: {appSettings: () => appSettingsState(), saveSettings: () => of(void 0)}},
      {provide: LibraryService, useValue: {libraries: () => []}},
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
