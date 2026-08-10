import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AppSettingsService } from '../../../shared/service/app-settings.service';
import { AppSettingKey } from '../../../shared/model/app-settings.model';
import { InputTextModule } from '@openng/optimus-ui/inputtext';
import { MessageModule } from '@openng/optimus-ui/message';
import { CardModule } from '@openng/optimus-ui/card';
import { MessageService } from '@openng/optimus-ui/api';

import { OverDriveService, OverDriveCard, OverDriveImportDestinations, OverDriveLibraryResolution, OverDriveShareUser } from '../../../core/services/overdrive.service';
import { ButtonModule } from '@openng/optimus-ui/button';
import { TooltipModule } from '@openng/optimus-ui/tooltip';
import { OrderListModule } from '@openng/optimus-ui/orderlist';
import { DialogModule } from '@openng/optimus-ui/dialog';
import { MultiSelectModule } from '@openng/optimus-ui/multiselect';
import { SelectModule } from '@openng/optimus-ui/select';
import { LibraryService } from '../../../features/book/service/library.service';
import { OverdriveCardAdminComponent } from './card-admin/overdrive-card-admin.component';
import { Library, LibraryPath } from '../../../features/book/model/library.model';

@Component({
  selector: 'app-overdrive-settings',
  standalone: true,
  imports: [
    FormsModule,
    InputTextModule,
    MessageModule,
    CardModule,
    ButtonModule,
    TooltipModule,
    OrderListModule,
    DialogModule,
    MultiSelectModule,
    SelectModule,
    OverdriveCardAdminComponent
],
  templateUrl: './overdrive-settings.component.html',
  styleUrl: './overdrive-settings.component.scss',
  providers: [MessageService]
})
export class OverdriveSettingsComponent {
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly overdriveService = inject(OverDriveService);
  private readonly messageService = inject(MessageService);
  private readonly libraryService = inject(LibraryService);

  // Grimmory libraries (with paths) for the per-type import-destination selectors.
  readonly grimmoryLibraries = this.libraryService.libraries;
  // Per-document-type import destinations: ebooks (EPUB/PDF) vs audiobooks. Null = Bookdrop.
  ebookLibrary = signal<Library | null>(null);
  ebookPath = signal<LibraryPath | null>(null);
  audiobookLibrary = signal<Library | null>(null);
  audiobookPath = signal<LibraryPath | null>(null);
  magazineLibrary = signal<Library | null>(null);
  magazinePath = signal<LibraryPath | null>(null);

  // The list of OverDrive library keys to search for metadata (source of truth).
  libraryKeys = signal<string[]>([]);
  // The "add a key" input.
  newKeyInput = signal('');
  // Per-key Thunder validation result (or 'checking' while in flight), keyed by the key itself.
  keyStatus = signal<Record<string, OverDriveLibraryResolution | 'checking'>>({});
  setupCode = signal('');
  connecting = signal(false);
  linkedCards = signal<OverDriveCard[]>([]);
  setupError = signal<string | null>(null);

  // Link by card number + PIN (produces a fulfillment-capable primary card).
  // Its own library key, independent of the default key above — the card is authenticated against the
  // library that issued it, which may differ from the default search library.
  cardLibraryKey = signal('');
  cardNumber = signal('');
  pin = signal('');
  linkingCard = signal(false);
  // Validation of the card's library key against the Thunder directory.
  resolvingCardKey = signal(false);
  cardResolution = signal<OverDriveLibraryResolution | null>(null);

  // Link by pasting a Libby identity token from a signed-in browser.
  identityToken = signal('');
  linkingToken = signal(false);

  // Whether OVERDRIVE_CREDENTIAL_KEY is set (enables encrypted credential storage + auto-relink).
  credentialStorageEnabled = signal(false);
  // Whether an external ACSM handler is configured; Adobe-DRM formats are unsupported without it.
  acsmHandlerConfigured = signal(false);

  // Borrow & import format preference (most-preferred first). p-orderList reorders this array in place.
  readonly formatOptions: { id: string; label: string }[] = [
    { id: 'ebook-epub-open', label: 'EPUB (DRM-free)' },
    { id: 'ebook-epub-adobe', label: 'EPUB (Adobe DRM)' },
    { id: 'ebook-pdf-open', label: 'PDF (DRM-free)' },
    { id: 'ebook-pdf-adobe', label: 'PDF (Adobe DRM)' }
  ];
  formatPreference: { id: string; label: string }[] = [...this.formatOptions];

  // Diagnostics: a passive, read-only state snapshot (no live OverDrive calls, no inputs).
  diagnosticsJson = signal<string | null>(null);
  runningDiagnostics = signal(false);

  constructor() {
    effect(() => {
      const overdrive = this.appSettingsService.appSettings()?.metadataProviderSettings?.overdrive;
      if (overdrive) {
        this.libraryKeys.set([...new Set((overdrive.libraryKeys ?? []).map(k => k.trim()).filter(k => k.length > 0))]);
        this.formatPreference = this.orderFormatPreference(overdrive.formatPreference);
      }
    });
    this.overdriveService.cards().subscribe({
      next: (cards) => this.linkedCards.set(cards ?? []),
      error: () => this.linkedCards.set([])
    });
    this.overdriveService.capabilities().subscribe({
      next: (c) => {
        this.credentialStorageEnabled.set(!!c?.credentialStorageEnabled);
        this.acsmHandlerConfigured.set(!!c?.acsmHandlerConfigured);
      },
      error: () => {
        this.credentialStorageEnabled.set(false);
        this.acsmHandlerConfigured.set(false);
      }
    });
    this.loadImportDestinations();
  }

  /** Load the per-document-type import destinations and resolve their ids to library/path objects. */
  private loadImportDestinations(): void {
    this.overdriveService.importDestinations().subscribe({
      next: (d) => {
        const libs = this.grimmoryLibraries();
        const eLib = libs.find(l => l.id === d.ebookLibraryId) ?? null;
        this.ebookLibrary.set(eLib);
        this.ebookPath.set(eLib?.paths.find(p => p.id === d.ebookPathId) ?? null);
        const aLib = libs.find(l => l.id === d.audiobookLibraryId) ?? null;
        this.audiobookLibrary.set(aLib);
        this.audiobookPath.set(aLib?.paths.find(p => p.id === d.audiobookPathId) ?? null);
        const mLib = libs.find(l => l.id === d.magazineLibraryId) ?? null;
        this.magazineLibrary.set(mLib);
        this.magazinePath.set(mLib?.paths.find(p => p.id === d.magazinePathId) ?? null);
      },
      error: () => { /* leave unset (Bookdrop) */ }
    });
  }

  onEbookLibraryChange(library: Library | null): void {
    this.ebookLibrary.set(library);
    this.ebookPath.set(library?.paths.length === 1 ? library.paths[0] : null);
    this.saveImportDestinations();
  }

  onEbookPathChange(path: LibraryPath | null): void {
    this.ebookPath.set(path);
    this.saveImportDestinations();
  }

  onAudiobookLibraryChange(library: Library | null): void {
    this.audiobookLibrary.set(library);
    this.audiobookPath.set(library?.paths.length === 1 ? library.paths[0] : null);
    this.saveImportDestinations();
  }

  onAudiobookPathChange(path: LibraryPath | null): void {
    this.audiobookPath.set(path);
    this.saveImportDestinations();
  }

  onMagazineLibraryChange(library: Library | null): void {
    this.magazineLibrary.set(library);
    this.magazinePath.set(library?.paths.length === 1 ? library.paths[0] : null);
    this.saveImportDestinations();
  }

  onMagazinePathChange(path: LibraryPath | null): void {
    this.magazinePath.set(path);
    this.saveImportDestinations();
  }

  /** Persist the per-type import destinations (fire-and-forget; a toast confirms). */
  private saveImportDestinations(): void {
    const payload: OverDriveImportDestinations = {
      ebookLibraryId: this.ebookLibrary()?.id ?? null,
      ebookPathId: this.ebookPath()?.id ?? null,
      audiobookLibraryId: this.audiobookLibrary()?.id ?? null,
      audiobookPathId: this.audiobookPath()?.id ?? null,
      magazineLibraryId: this.magazineLibrary()?.id ?? null,
      magazinePathId: this.magazinePath()?.id ?? null
    };
    this.setupError.set(null);
    this.overdriveService.setImportDestinations(payload).subscribe({
      error: (err) => this.setupError.set(err?.error?.message || err?.message || 'Failed to save import destinations')
    });
  }

  /** True for Adobe-DRM formats that cannot be imported without a configured ACSM handler. */
  isFormatUnsupported(formatId: string): boolean {
    return formatId.endsWith('-adobe') && !this.acsmHandlerConfigured();
  }

  /** Order the known format options by the saved preference ids, appending any not listed. */
  private orderFormatPreference(saved: string[] | null | undefined): { id: string; label: string }[] {
    if (!saved || saved.length === 0) {
      return [...this.formatOptions];
    }
    const byId = new Map(this.formatOptions.map(o => [o.id, o]));
    const ordered = saved.map(id => byId.get(id)).filter((o): o is { id: string; label: string } => !!o);
    const remaining = this.formatOptions.filter(o => !saved.includes(o.id));
    return [...ordered, ...remaining];
  }

  cardLabel(card: OverDriveCard): string {
    return card.name ? `${card.name} (${card.cardId})` : card.cardId;
  }

  /** A card is yours to manage unless another user shared it with you (owned === false). */
  isOwned(card: OverDriveCard): boolean {
    return card.owned !== false;
  }

  /** Format an epoch-seconds token expiry as a short local date-time (or '—'). */
  formatEpoch(epochSeconds: number | null | undefined): string {
    if (!epochSeconds) return '—';
    try {
      return new Date(epochSeconds * 1000).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
    } catch {
      return '—';
    }
  }

  // ── Card credentials (promote a chip-only card to card+PIN / update the stored PIN) ──────────
  credDialogVisible = signal(false);
  credCard = signal<OverDriveCard | null>(null);
  credNumber = signal('');
  credPin = signal('');
  savingCred = signal(false);
  credError = signal<string | null>(null);

  /** Open the dialog to add or update a card's stored number + PIN. */
  openCredentialDialog(card: OverDriveCard): void {
    this.credCard.set(card);
    this.credNumber.set('');
    this.credPin.set('');
    this.credError.set(null);
    this.credDialogVisible.set(true);
  }

  /**
   * Store the entered card number + PIN for the dialog's card via a card+PIN re-link, which promotes a
   * chip-only card to a credential-backed one (and updates the PIN if it changed). Reuses linkCard: a
   * number+PIN link mints a primary chip and stores the (encrypted) credentials.
   */
  saveCredentials(): void {
    const card = this.credCard();
    if (!card) {
      return;
    }
    const key = (card.libraryKey ?? '').trim();
    const number = this.credNumber().trim();
    const pin = this.credPin().trim();
    if (!key) {
      this.credError.set('This card has no library key on file, so its credentials can\'t be set here.');
      return;
    }
    if (!number) {
      this.credError.set('Card number is required.');
      return;
    }
    this.savingCred.set(true);
    this.credError.set(null);
    this.overdriveService.linkCard(key, number, pin).subscribe({
      next: () => {
        this.savingCred.set(false);
        this.credDialogVisible.set(false);
        this.reloadLinkedCards();
        this.messageService.add({
          severity: 'success', summary: 'Credentials saved',
          detail: card.credentialsStored ? `Updated card + PIN for ${this.cardLabel(card)}` : `Stored card + PIN for ${this.cardLabel(card)}`
        });
      },
      error: (err) => {
        this.savingCred.set(false);
        this.credError.set(err?.error?.message || err?.message || 'Saving credentials failed');
      }
    });
  }

  // ── Card sharing ───────────────────────────────────────────────────────
  shareDialogVisible = signal(false);
  shareCard = signal<OverDriveCard | null>(null);
  shareableUsers = signal<OverDriveShareUser[]>([]);
  selectedShareUserIds = signal<number[]>([]);
  savingShares = signal(false);

  userOptionLabel(user: OverDriveShareUser): string {
    return user.name ? `${user.name} (${user.username})` : user.username;
  }

  /** Open the share dialog for a card, loading candidate users and the card's current shares. */
  openShareDialog(card: OverDriveCard): void {
    this.shareCard.set(card);
    this.selectedShareUserIds.set([]);
    this.shareableUsers.set([]);
    this.shareDialogVisible.set(true);
    this.overdriveService.shareableUsers().subscribe({
      next: (users) => this.shareableUsers.set(users ?? []),
      error: () => this.shareableUsers.set([])
    });
    this.overdriveService.listShares(card.cardId).subscribe({
      next: (shares) => this.selectedShareUserIds.set((shares ?? []).map(s => s.userId)),
      error: () => this.selectedShareUserIds.set([])
    });
  }

  /** Persist the chosen share set for the dialog's card. */
  saveShares(): void {
    const card = this.shareCard();
    if (!card) {
      return;
    }
    const ids = this.selectedShareUserIds();
    this.savingShares.set(true);
    this.overdriveService.setShares(card.cardId, ids).subscribe({
      next: () => {
        this.savingShares.set(false);
        this.shareDialogVisible.set(false);
        this.linkedCards.update(cards => cards.map(c => c.cardId === card.cardId ? { ...c, sharedWithCount: ids.length } : c));
        this.messageService.add({
          severity: 'success', summary: 'Sharing updated',
          detail: ids.length ? `${this.cardLabel(card)} shared with ${ids.length} user(s)` : `Sharing cleared for ${this.cardLabel(card)}`
        });
      },
      error: (err) => {
        this.savingShares.set(false);
        this.setupError.set(err?.error?.message || err?.message || 'Share update failed');
      }
    });
  }

  /**
   * Reload the full linked-card list from the server. The link endpoints only return the cards on the
   * just-linked account's token, so replacing the list with that would visually drop previously-linked
   * cards until reload — always re-fetch the authoritative set instead.
   */
  private reloadLinkedCards(): void {
    this.overdriveService.cards().subscribe({
      next: (cards) => this.linkedCards.set(cards ?? []),
      error: () => { /* keep whatever we have */ }
    });
  }

  /** Unlink a card (clear its stored token/credentials). */
  onUnlinkCard(card: OverDriveCard): void {
    this.overdriveService.removeCard(card.cardId).subscribe({
      next: () => {
        this.linkedCards.update(cards => cards.filter(c => c.cardId !== card.cardId));
        this.messageService.add({ severity: 'success', summary: 'Unlinked', detail: `Removed ${this.cardLabel(card)}` });
      },
      error: (err) => this.setupError.set(err?.error?.message || err?.message || 'Unlink failed')
    });
  }

  /** Set a friendly display label for a card (blank clears it back to the default name). */
  onRenameCard(card: OverDriveCard, name: string): void {
    const label = name.trim();
    this.overdriveService.setCardLabel(card.cardId, label).subscribe({
      next: () => {
        this.linkedCards.update(cards => cards.map(c => c.cardId === card.cardId ? { ...c, name: label || null } : c));
        this.messageService.add({ severity: 'success', summary: 'Renamed', detail: label ? `Card labelled "${label}"` : 'Card label cleared' });
      },
      error: (err) => this.setupError.set(err?.error?.message || err?.message || 'Rename failed')
    });
  }

  /** Refresh a card+PIN card's token by re-linking from its stored credentials. */
  onRefreshCard(card: OverDriveCard): void {
    this.overdriveService.refreshCard(card.cardId).subscribe({
      next: () => this.messageService.add({ severity: 'success', summary: 'Refreshed', detail: `Re-linked ${this.cardLabel(card)}` }),
      error: (err) => this.setupError.set(err?.error?.message || err?.message || 'Refresh failed')
    });
  }

  /** Add the typed key to the list (if new) and validate it against the Thunder directory. */
  onAddKey(): void {
    const key = this.newKeyInput().trim();
    this.newKeyInput.set('');
    if (!key || this.libraryKeys().includes(key)) {
      return;
    }
    this.libraryKeys.update(ks => [...ks, key]);
    this.resolveKey(key);
  }

  /** Remove a key from the list. */
  removeKey(key: string): void {
    this.libraryKeys.update(ks => ks.filter(k => k !== key));
    this.keyStatus.update(s => {
      const next = { ...s };
      delete next[key];
      return next;
    });
  }

  /** Re-validate every key currently in the list. */
  checkKeys(): void {
    this.libraryKeys().forEach(k => this.resolveKey(k));
  }

  /** Validate a single key, recording its resolution (or 'checking'). */
  private resolveKey(key: string): void {
    this.keyStatus.update(s => ({ ...s, [key]: 'checking' }));
    this.overdriveService.resolveLibrary(key).subscribe({
      next: (res) => this.keyStatus.update(s => ({ ...s, [key]: res })),
      error: () => this.keyStatus.update(s => ({ ...s, [key]: { valid: false, libraryKey: key, name: null } }))
    });
  }

  /** The resolved validation for a key, or null if not yet checked / still checking. */
  keyResolution(key: string): OverDriveLibraryResolution | null {
    const s = this.keyStatus()[key];
    return s && s !== 'checking' ? s : null;
  }

  isKeyChecking(key: string): boolean {
    return this.keyStatus()[key] === 'checking';
  }

  onConnect(): void {
    const code = this.setupCode().trim();
    if (!code) {
      this.setupError.set('Setup code is required');
      return;
    }

    this.connecting.set(true);
    this.setupError.set(null);

    this.overdriveService.redeemSetupCode(code).subscribe({
      next: (cards) => {
        this.reloadLinkedCards();
        this.messageService.add({
          severity: 'success',
          summary: 'Connected',
          detail: `Linked ${cards.length} card(s)`
        });
        this.setupCode.set('');
      },
      error: (err) => {
        this.setupError.set(err.message || 'Connection failed');
      },
      complete: () => {
        this.connecting.set(false);
      }
    });
  }

  /** Update the card-link library key and clear any stale validation result. */
  onCardKeyChange(value: string): void {
    this.cardLibraryKey.set(value);
    this.cardResolution.set(null);
  }

  /** Validate the card-link library key against the Thunder directory and show the resolved name. */
  checkCardKey(): void {
    const key = this.cardLibraryKey().trim();
    if (!key) {
      this.cardResolution.set(null);
      return;
    }
    this.resolvingCardKey.set(true);
    this.overdriveService.resolveLibrary(key).subscribe({
      next: (res) => {
        this.cardResolution.set(res);
        this.resolvingCardKey.set(false);
      },
      error: () => {
        this.cardResolution.set({ valid: false, libraryKey: key, name: null });
        this.resolvingCardKey.set(false);
      }
    });
  }

  /** Link a card by number + PIN against the card's own library key. */
  onLinkCard(): void {
    const key = this.cardLibraryKey().trim();
    const card = this.cardNumber().trim();
    if (!key) {
      this.setupError.set('Enter the library key for this card first');
      return;
    }
    if (!card) {
      this.setupError.set('Card number is required');
      return;
    }
    this.linkingCard.set(true);
    this.setupError.set(null);
    this.overdriveService.linkCard(key, card, this.pin().trim()).subscribe({
      next: (cards) => {
        this.reloadLinkedCards();
        this.messageService.add({
          severity: 'success',
          summary: 'Card linked',
          detail: `Linked ${cards.length} card(s) by number`
        });
        this.cardNumber.set('');
        this.pin.set('');
      },
      error: (err) => this.setupError.set(err?.error?.message || err?.message || 'Card link failed'),
      complete: () => this.linkingCard.set(false)
    });
  }

  /** Link by pasting a Libby identity token from a signed-in browser. */
  onLinkToken(): void {
    const token = this.identityToken().trim();
    if (!token) {
      this.setupError.set('Paste your Libby identity token first');
      return;
    }
    this.linkingToken.set(true);
    this.setupError.set(null);
    this.overdriveService.linkToken(token).subscribe({
      next: (cards) => {
        this.reloadLinkedCards();
        this.messageService.add({
          severity: 'success',
          summary: 'Token linked',
          detail: `Linked ${cards.length} card(s) from token`
        });
        this.identityToken.set('');
      },
      error: (err) => this.setupError.set(err?.error?.message || err?.message || 'Token link failed'),
      complete: () => this.linkingToken.set(false)
    });
  }

  /** Load the passive diagnostics snapshot (read-only; no live OverDrive calls). */
  runDiagnostics(): void {
    this.runningDiagnostics.set(true);
    this.diagnosticsJson.set(null);
    this.setupError.set(null);
    this.overdriveService.diagnostics().subscribe({
      next: (report) => {
        this.diagnosticsJson.set(JSON.stringify(report, null, 2));
        this.runningDiagnostics.set(false);
      },
      error: (err) => {
        this.setupError.set(err?.error?.message || err?.message || 'Diagnostics failed');
        this.runningDiagnostics.set(false);
      }
    });
  }

  /** Clear the loaded diagnostics snapshot. */
  clearDiagnostics(): void {
    this.diagnosticsJson.set(null);
  }

  /** Copy the diagnostics JSON to the clipboard (falls back for non-HTTPS where the async API is unavailable). */
  copyDiagnostics(): void {
    const json = this.diagnosticsJson();
    if (!json) return;
    const done = () => this.messageService.add({ severity: 'success', summary: 'Copied', detail: 'Diagnostics copied to clipboard' });
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(json).then(done, () => this.fallbackCopy(json, done));
    } else {
      this.fallbackCopy(json, done);
    }
  }

  /** Clipboard fallback for insecure (http://) contexts where navigator.clipboard is unavailable. */
  private fallbackCopy(text: string, onSuccess: () => void): void {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.focus();
      ta.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      if (ok) { onSuccess(); return; }
    } catch { /* fall through to selecting the visible text */ }
    this.selectDiagnosticsText();
  }

  /** Select the visible diagnostics JSON so the user can copy it manually (Cmd/Ctrl+C). */
  private selectDiagnosticsText(): void {
    const el = document.querySelector('.diagnostics-json');
    const sel = window.getSelection();
    if (el && sel) {
      const range = document.createRange();
      range.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(range);
      this.messageService.add({ severity: 'info', summary: 'Copy manually',
        detail: 'Clipboard access is blocked (non-HTTPS) — the text is selected; press Cmd/Ctrl+C.' });
    } else {
      this.setupError.set('Could not copy — select the text and copy it manually.');
    }
  }

  /** Download the diagnostics JSON as a file. */
  downloadDiagnostics(): void {
    const json = this.diagnosticsJson();
    if (!json) return;
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'overdrive-diagnostics.json';
    a.click();
    URL.revokeObjectURL(url);
  }

  onSave(): void {
    const metadata = this.appSettingsService.appSettings()?.metadataProviderSettings;
    if (!metadata) {
      this.messageService.add({
        severity: 'error',
        summary: 'Error',
        detail: 'Settings are not loaded yet'
      });
      return;
    }

    const updated = {
      ...metadata,
      overdrive: {
        ...metadata.overdrive,
        libraryKeys: this.libraryKeys(),
        formatPreference: this.formatPreference.map(o => o.id)
      }
    };

    this.appSettingsService.saveSettings([
      { key: AppSettingKey.METADATA_PROVIDER_SETTINGS, newValue: updated }
    ]).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: 'Saved',
          detail: 'OverDrive settings saved'
        });
      },
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: 'Error',
          detail: err.message || 'Save failed'
        });
      }
    });
  }
}
