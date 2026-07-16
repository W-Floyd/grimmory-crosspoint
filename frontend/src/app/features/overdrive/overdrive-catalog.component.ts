import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { OverDriveService, OverDriveCatalogItem, OverDriveHold, OverDriveLibrary, OverDriveLoan, OverDriveSyncResult } from '../../core/services/overdrive.service';
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
  identity = signal('');
  token = signal('');
  loans = signal<OverDriveLoan[]>([]);
  holds = signal<OverDriveHold[]>([]);
  libraries = signal<OverDriveLibrary[]>([]);

   // Setup code input
  setupCode = signal('');
  connecting = signal(false);

   // Catalog search + import
  readonly grimmoryLibraries = this.libraryService.libraries;
  searchQuery = signal('');
  searching = signal(false);
  results = signal<OverDriveCatalogItem[]>([]);
  selectedLibrary = signal<Library | null>(null);
  selectedPath = signal<LibraryPath | null>(null);
  importingTitleId = signal<string | null>(null);

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
     const id = this.identity();
     const tok = this.token();
     const library = this.selectedLibrary();
     const path = this.selectedPath();
     if (!id || !tok) {
       this.error.set('Connect to your library first (identity and token are required)');
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
     this.overdriveService.borrowAndImport(id, tok, {
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

   // Actions
  onLoadLoans(): void {
     const id = this.identity();
     const tok = this.token();
     if (!id || !tok) {
       this.error.set('Identity and token are required');
       return;
       }

     this.loading.set(true);
     this.error.set(null);

     this.overdriveService.sync(id, tok).subscribe({
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

   onConnect(): void {
     const code = this.setupCode().trim();
     if (!code) {
       this.error.set('Setup code is required');
       return;
       }

     this.connecting.set(true);
     this.error.set(null);

     this.overdriveService.redeemSetupCode(code).subscribe({
       next: (result) => {
         this.identity.set(result.identity);
         this.token.set(result.token);
         this.setupCode.set('');
         this.messageService.add({
           severity: 'success',
           summary: 'Connected',
           detail: `Linked Libby card: ${result.identity}`
          });
         this.connecting.set(false);
         this.onLoadLoans();
         },
       error: (err: unknown) => {
         this.error.set(this.errorMessage(err, 'Connection failed'));
         this.connecting.set(false);
         }
      });
     }

   onReturn(loanId: string): void {
     const id = this.identity();
     const tok = this.token();
     if (!id || !tok) return;

     this.overdriveService.returnBook(id, tok, loanId).subscribe({
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
     const id = this.identity();
     const tok = this.token();
     if (!id || !tok) return;

     this.overdriveService.fulfill(id, tok, loanId).subscribe({
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