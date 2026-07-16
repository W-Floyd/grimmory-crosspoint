import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AppSettingsService } from '../../../shared/service/app-settings.service';
import { AppSettingKey } from '../../../shared/model/app-settings.model';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { CardModule } from 'primeng/card';
import { MessageService } from 'primeng/api';
import { CommonModule } from '@angular/common';
import { OverDriveService, OverDriveSetupResult } from '../../../core/services/overdrive.service';
import { ButtonModule } from 'primeng/button';

@Component({
  selector: 'app-overdrive-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    InputTextModule,
    MessageModule,
    CardModule,
    ButtonModule
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
  setupResult = signal<OverDriveSetupResult | null>(null);
  setupError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const overdrive = this.appSettingsService.appSettings()?.metadataProviderSettings?.overdrive;
      if (overdrive) {
        this.libraryKey.set(overdrive.libraryKey ?? '');
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
      next: (result) => {
        this.setupResult.set(result);
        this.messageService.add({
          severity: 'success',
          summary: 'Connected',
          detail: `Linked Libby card: ${result.identity}`
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
