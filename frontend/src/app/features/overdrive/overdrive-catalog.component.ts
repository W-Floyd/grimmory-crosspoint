import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OverDriveService, OverDriveCard, OverDriveCatalogItem, OverDriveHold, OverDriveLibrary, OverDriveLoan, OverDriveSyncResult } from '../../core/services/overdrive.service';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { CardModule } from 'primeng/card';
import { TableModule } from 'primeng/table';
import { SelectModule } from 'primeng/select';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { TooltipModule } from 'primeng/tooltip';
import { InputTextModule } from 'primeng/inputtext';
import { LibraryService } from '../../features/book/service/library.service';
import { Library, LibraryPath } from '../../features/book/model/library.model';

@Component({
  selector: 'app-overdrive-catalog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ButtonModule,
    MessageModule,
    CardModule,
    TableModule,
    SelectModule,
    ProgressSpinnerModule,
    ToastModule,
    TooltipModule,
    InputTextModule
   ],
  templateUrl: './overdrive-catalog.component.html',
  styleUrl: './overdrive-catalog.component.scss',
  providers: [MessageService]
})
export class OverdriveCatalogComponent {
  private readonly overdriveService = inject(OverDriveService);
  private readonly messageService = inject(MessageService);
  private readonly libraryService = inject(LibraryService);

   // State
  loading = signal(false);
  error = signal<string | null>(null);
  loans = signal<OverDriveLoan[]>([]);
  holds = signal<OverDriveHold[]>([]);
  libraries = signal<OverDriveLibrary[]>([]);

   // Linked Libby cards (per user); the selected card drives sync/borrow/return.
  cards = signal<OverDriveCard[]>([]);
  selectedCard = signal<OverDriveCard | null>(null);

   // Setup code input
  setupCode = signal('');
  connecting = signal(false);

   // Link by card number + PIN (produces a fulfillment-capable primary card).
  linkLibraryKey = signal('');
  linkCardNumber = signal('');
  linkPin = signal('');
  linkingCard = signal(false);

   // Link by pasting a Libby identity token from a signed-in browser.
  identityToken = signal('');
  linkingToken = signal(false);

   // Whether OVERDRIVE_CREDENTIAL_KEY is set (enables encrypted credential storage + auto-relink).
  credentialStorageEnabled = signal(false);

   // Catalog search + import
  readonly grimmoryLibraries = this.libraryService.libraries;
  searchQuery = signal('');
  searching = signal(false);
  results = signal<OverDriveCatalogItem[]>([]);
  selectedLibrary = signal<Library | null>(null);
  selectedPath = signal<LibraryPath | null>(null);
  importingTitleId = signal<string | null>(null);

   // Diagnostics: a passive, read-only state snapshot (no live calls, no inputs).
  diagnosticsJson = signal<string | null>(null);
  runningDiagnostics = signal(false);

   constructor() {
     this.loadCards();
     this.overdriveService.capabilities().subscribe({
       next: (c) => this.credentialStorageEnabled.set(!!c?.credentialStorageEnabled),
       error: () => { /* leave defaults */ }
     });
   }

   cardLabel(card: OverDriveCard | null): string {
     if (!card) return '';
     return card.name ? `${card.name} (${card.cardId})` : card.cardId;
   }

   /** Load the user's linked cards; optionally select a specific one, else keep/first. */
   private loadCards(preferCardId?: string): void {
     this.overdriveService.cards().subscribe({
       next: (cards) => {
         this.cards.set(cards ?? []);
         if (cards && cards.length > 0) {
           const preferred = preferCardId ? cards.find(c => c.cardId === preferCardId) : undefined;
           const current = this.selectedCard();
           const stillPresent = current ? cards.find(c => c.cardId === current.cardId) : undefined;
           this.selectedCard.set(preferred ?? stillPresent ?? cards[0]);
           this.onLoadLoans();
         } else {
           this.selectedCard.set(null);
         }
       },
       error: () => { /* not connected yet; leave cards empty */ }
     });
   }

   onCardChange(card: OverDriveCard | null): void {
     this.selectedCard.set(card);
     if (card) {
       this.onLoadLoans();
     }
   }

   onConnect(): void {
     const code = this.setupCode().trim();
     if (!code) {
       this.error.set('Setup code is required');
       return;
       }

     this.connecting.set(true);
     this.error.set(null);

     this.overdriveService.redeemSetupCode(code).subscribe({
       next: (linked) => {
         this.setupCode.set('');
         this.messageService.add({
           severity: 'success',
           summary: 'Connected',
           detail: `Linked ${linked.length} card(s)`
          });
         this.connecting.set(false);
         this.loadCards(linked[0]?.cardId);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Connection failed'));
         this.connecting.set(false);
         }
      });
     }

   onLinkCard(): void {
     const key = this.linkLibraryKey().trim();
     const card = this.linkCardNumber().trim();
     if (!key || !card) {
       this.error.set('Library key and card number are required');
       return;
       }
     this.linkingCard.set(true);
     this.error.set(null);
     this.overdriveService.linkCard(key, card, this.linkPin().trim()).subscribe({
       next: (linked) => {
         this.messageService.add({
           severity: 'success',
           summary: 'Card linked',
           detail: `Linked ${linked.length} card(s) by number`
          });
         this.linkCardNumber.set('');
         this.linkPin.set('');
         this.linkingCard.set(false);
         this.loadCards(linked[0]?.cardId);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Card link failed'));
         this.linkingCard.set(false);
         }
       });
     }

   onLinkToken(): void {
     const token = this.identityToken().trim();
     if (!token) {
       this.error.set('Paste your Libby identity token first');
       return;
       }
     this.linkingToken.set(true);
     this.error.set(null);
     this.overdriveService.linkToken(token).subscribe({
       next: (linked) => {
         this.messageService.add({
           severity: 'success',
           summary: 'Token linked',
           detail: `Linked ${linked.length} card(s) from token`
          });
         this.identityToken.set('');
         this.linkingToken.set(false);
         this.loadCards(linked[0]?.cardId);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Token link failed'));
         this.linkingToken.set(false);
         }
       });
     }

   /** Unlink a card (clear its stored token/credentials) and refresh the picker. */
   onUnlinkCard(card: OverDriveCard): void {
     this.overdriveService.removeCard(card.cardId).subscribe({
       next: () => {
         this.messageService.add({ severity: 'success', summary: 'Unlinked', detail: `Removed ${this.cardLabel(card)}` });
         if (this.selectedCard()?.cardId === card.cardId) {
           this.selectedCard.set(null);
         }
         this.loadCards();
         },
       error: (err: unknown) => this.error.set(this.errorMessage(err, 'Unlink failed'))
       });
     }

   /** Refresh a card+PIN card's token by re-linking from its stored credentials. */
   onRefreshCard(card: OverDriveCard): void {
     this.overdriveService.refreshCard(card.cardId).subscribe({
       next: () => {
         this.messageService.add({ severity: 'success', summary: 'Refreshed', detail: `Re-linked ${this.cardLabel(card)}` });
         this.onLoadLoans();
         },
       error: (err: unknown) => this.error.set(this.errorMessage(err, 'Refresh failed'))
       });
     }

   onLoadLoans(): void {
     const card = this.selectedCard();
     if (!card) {
       this.error.set('Connect a Libby account and select a card first');
       return;
       }

     this.loading.set(true);
     this.error.set(null);

     this.overdriveService.sync(card.cardId).subscribe({
       next: (result: OverDriveSyncResult) => {
         this.loans.set(result.loans ?? []);
         this.holds.set(result.holds ?? []);
         this.libraries.set(result.libraries ?? []);
         this.loading.set(false);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Failed to sync'));
         this.loading.set(false);
         }
       });
     }

   onLibraryChange(library: Library | null): void {
     this.selectedLibrary.set(library);
     // Auto-select the only path, otherwise clear.
     this.selectedPath.set(library?.paths?.length === 1 ? library.paths[0] : null);
   }

   onSearch(): void {
     const query = this.searchQuery().trim();
     if (!query) {
       this.error.set('Enter a search term');
       return;
       }
     this.searching.set(true);
     this.error.set(null);
     this.overdriveService.search(query).subscribe({
       next: (items) => {
         this.results.set(items ?? []);
         this.searching.set(false);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Search failed'));
         this.searching.set(false);
         }
       });
     }

   onBorrowImport(item: OverDriveCatalogItem): void {
     const card = this.selectedCard();
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     if (!card) {
       this.error.set('Connect a Libby account and select a card first');
       return;
       }
     if (!item.titleId) {
       this.error.set('This title is not borrowable');
       return;
       }
     if (!library?.id || !path?.id) {
       this.error.set('Select a destination library and path');
       return;
       }

     this.importingTitleId.set(item.titleId);
     this.error.set(null);
     this.overdriveService.borrowAndImport(card.cardId, {
       titleId: item.titleId,
       libraryId: library.id,
       pathId: path.id,
       title: item.title,
       author: item.author,
       coverUrl: item.coverUrl,
       isbn: item.isbn
     }).subscribe({
       next: (book) => {
         this.messageService.add({
           severity: 'success',
           summary: 'Imported',
           detail: `"${item.title}" borrowed and imported (book #${book.id})`
          });
         this.importingTitleId.set(null);
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Borrow & import failed'));
         this.importingTitleId.set(null);
         }
       });
     }

   onPlaceHold(item: OverDriveCatalogItem): void {
     const card = this.selectedCard();
     if (!card) {
       this.error.set('Connect a Libby account and select a card first');
       return;
       }
     if (!item.titleId) {
       return;
       }
     this.error.set(null);
     this.overdriveService.placeHold(card.cardId, item.titleId).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Hold placed',
           detail: `Placed a hold on "${item.title}"`
          });
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Place hold failed'));
         }
      });
     }

   onCancelHold(hold: OverDriveHold): void {
     const card = this.selectedCard();
     if (!card) return;

     this.overdriveService.cancelHold(card.cardId, hold.id).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Hold cancelled',
           detail: `Cancelled the hold on "${hold.title}"`
          });
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Cancel hold failed'));
         }
      });
     }

   onReturn(loanId: string): void {
     const card = this.selectedCard();
     if (!card) return;

     this.overdriveService.returnBook(card.cardId, loanId).subscribe({
       next: () => {
         this.messageService.add({
           severity: 'success',
           summary: 'Returned',
           detail: 'Book returned successfully'
          });
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Return failed'));
         }
      });
     }

   onFulfill(loanId: string): void {
     const card = this.selectedCard();
     if (!card) return;

     this.overdriveService.fulfill(card.cardId, loanId).subscribe({
       next: (result) => {
         if (result.acsmBase64) {
            // Decode and download the ACSM file
           const byteCharacters = atob(result.acsmBase64);
           const byteNumbers = new Uint8Array(byteCharacters.length);
           for (let i = 0; i < byteCharacters.length; i++) {
             byteNumbers[i] = byteCharacters.charCodeAt(i);
             }
           const blob = new Blob([byteNumbers], { type: 'application/smil+xml' });
           const url = URL.createObjectURL(blob);
           const a = document.createElement('a');
           a.href = url;
           a.download = `${loanId}.acsm`;
           a.click();
           URL.revokeObjectURL(url);

           this.messageService.add({
             severity: 'success',
             summary: 'Downloaded',
             detail: 'ACSM downloaded'
            });
           }
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Fulfill failed'));
         }
      });
     }

   /** Load the passive diagnostics snapshot (read-only; no live OverDrive calls). */
   runDiagnostics(): void {
     this.runningDiagnostics.set(true);
     this.diagnosticsJson.set(null);
     this.error.set(null);
     this.overdriveService.diagnostics().subscribe({
       next: (report) => {
         this.diagnosticsJson.set(JSON.stringify(report, null, 2));
         this.runningDiagnostics.set(false);
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Diagnostics failed'));
         this.runningDiagnostics.set(false);
         }
       });
     }

   /** Copy the diagnostics JSON to the clipboard. */
   copyDiagnostics(): void {
     const json = this.diagnosticsJson();
     if (!json) return;
     navigator.clipboard?.writeText(json).then(
       () => this.messageService.add({ severity: 'success', summary: 'Copied', detail: 'Diagnostics copied to clipboard' }),
       () => this.error.set('Could not copy to clipboard')
     );
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

   clearError(): void {
     this.error.set(null);
     }

   /** Prefer the backend's structured error body message, falling back to a generic string. */
   private errorMessage(err: unknown, fallback: string): string {
     if (err instanceof HttpErrorResponse) {
       return err.error?.message || err.message || fallback;
     }
     if (err instanceof Error) {
       return err.message || fallback;
     }
     return fallback;
   }

   formatDate(dateStr: string | null | undefined): string {
     if (!dateStr) return '—';
     try {
       return new Date(dateStr).toLocaleDateString();
       } catch {
       return dateStr;
       }
     }
}
