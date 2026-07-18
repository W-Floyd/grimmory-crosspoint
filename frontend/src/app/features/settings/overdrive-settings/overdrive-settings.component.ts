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

@Component({
  selector: 'app-overdrive-settings',
  standalone: true,
  imports: [
    FormsModule,
    InputTextModule,
    MessageModule,
    CardModule,
    ButtonModule,
    TooltipModule
],
  templateUrl: './overdrive-settings.component.html',
  styleUrl: './overdrive-settings.component.scss',
  providers: [MessageService]
})
export class OverdriveSettingsComponent {
  private readonly appSettingsService = inject(AppSettingsService);
  private readonly overdriveService = inject(OverDriveService);
  private readonly messageService = inject(MessageService);

  libraryKey = signal('');
  setupCode = signal('');
  connecting = signal(false);
  linkedCards = signal<OverDriveCard[]>([]);
  setupError = signal<string | null>(null);

  // Link by card number + PIN (produces a fulfillment-capable primary card).
  cardNumber = signal('');
  pin = signal('');
  linkingCard = signal(false);

  // Link by pasting a Libby identity token from a signed-in browser.
  identityToken = signal('');
  linkingToken = signal(false);

  // Whether OVERDRIVE_CREDENTIAL_KEY is set (enables encrypted credential storage + auto-relink).
  credentialStorageEnabled = signal(false);

  // Diagnostics: a passive, read-only state snapshot (no live OverDrive calls, no inputs).
  diagnosticsJson = signal<string | null>(null);
  runningDiagnostics = signal(false);

  // Library-key validation against the Thunder directory.
  resolving = signal(false);
  resolution = signal<OverDriveLibraryResolution | null>(null);
  // Guards the effect from re-resolving the same key on every settings change.
  private lastAutoResolvedKey = '';

  constructor() {
    effect(() => {
      const overdrive = this.appSettingsService.appSettings()?.metadataProviderSettings?.overdrive;
      if (overdrive) {
        const key = overdrive.libraryKey ?? '';
        this.libraryKey.set(key);
        if (key && key !== this.lastAutoResolvedKey) {
          this.lastAutoResolvedKey = key;
          this.checkKey();
        }
      }
    });
    this.overdriveService.cards().subscribe({
      next: (cards) => this.linkedCards.set(cards ?? []),
      error: () => this.linkedCards.set([])
    });
    this.overdriveService.capabilities().subscribe({
      next: (c) => this.credentialStorageEnabled.set(!!c?.credentialStorageEnabled),
      error: () => this.credentialStorageEnabled.set(false)
    });
  }

  cardLabel(card: OverDriveCard): string {
    return card.name ? `${card.name} (${card.cardId})` : card.cardId;
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

  /** Update the key and clear any stale validation result so the UI doesn't show a mismatched name. */
  onKeyChange(value: string): void {
    this.libraryKey.set(value);
    this.resolution.set(null);
  }

  /** Validate the entered library key against the Thunder directory and show the resolved name. */
  checkKey(): void {
    const key = this.libraryKey().trim();
    if (!key) {
      this.resolution.set(null);
      return;
    }
    this.resolving.set(true);
    this.overdriveService.resolveLibrary(key).subscribe({
      next: (res) => {
        this.resolution.set(res);
        this.resolving.set(false);
      },
      error: () => {
        this.resolution.set({ valid: false, libraryKey: key, name: null });
        this.resolving.set(false);
      }
    });
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
        this.linkedCards.set(cards ?? []);
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

  /** Link a card by number + PIN using the configured library key. */
  onLinkCard(): void {
    const key = this.libraryKey().trim();
    const card = this.cardNumber().trim();
    if (!key) {
      this.setupError.set('Set and save the library key first');
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
        this.linkedCards.set(cards ?? []);
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
        this.linkedCards.set(cards ?? []);
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
        libraryKey: this.libraryKey()
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
        this.lastAutoResolvedKey = this.libraryKey().trim();
        this.checkKey();
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
