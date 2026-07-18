import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AppSettingsService } from '../../../shared/service/app-settings.service';
import { AppSettingKey } from '../../../shared/model/app-settings.model';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { CardModule } from 'primeng/card';
import { MessageService } from 'primeng/api';

import { OverDriveService, OverDriveCard, OverDriveLibraryResolution } from '../../../core/services/overdrive.service';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { OrderListModule } from 'primeng/orderlist';

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
    OrderListModule
],
  templateUrl: './overdrive-settings.component.html',
  styleUrl: './overdrive-settings.component.scss',
  providers: [MessageService]
})
export class OverdriveSettingsComponent {
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly overdriveService = inject(OverDriveService);
  private readonly messageService = inject(MessageService);

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
