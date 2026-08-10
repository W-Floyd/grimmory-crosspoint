import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InputTextModule } from '@openng/optimus-ui/inputtext';
import { MessageModule } from '@openng/optimus-ui/message';
import { CardModule } from '@openng/optimus-ui/card';
import { ButtonModule } from '@openng/optimus-ui/button';
import { TooltipModule } from '@openng/optimus-ui/tooltip';
import { DialogModule } from '@openng/optimus-ui/dialog';
import { MultiSelectModule } from '@openng/optimus-ui/multiselect';
import { SelectModule } from '@openng/optimus-ui/select';
import { Toast } from '@openng/optimus-ui/toast';
import { ConfirmationService, MessageService } from '@openng/optimus-ui/api';

import {
  OverDriveLibraryResolution,
  OverDriveManagedCard,
  OverDriveService,
  OverDriveShareUser
} from '../../../../core/services/overdrive.service';
import { UserService } from '../../user-management/user.service';

/** One card owner and the cards they've linked, for the grouped admin list. */
interface OwnerGroup {
  ownerUserId: number;
  ownerName: string;
  cards: OverDriveManagedCard[];
}

/**
 * Cross-user OverDrive card administration: every user's linked cards, grouped by owner, with the same
 * management actions the owner has on their own cards (relabel, refresh, unlink, sharing).
 *
 * <p>Rendered only for a user holding `canManageAllOverdriveCards` — the backend enforces the same on
 * every call, so this is a UI affordance rather than the boundary. Because `(user_id, identity)` is
 * unique rather than `identity` alone, every action passes the card's `ownerUserId` to name its row.
 */
@Component({
  selector: 'app-overdrive-card-admin',
  standalone: true,
  imports: [
    FormsModule,
    InputTextModule,
    MessageModule,
    CardModule,
    ButtonModule,
    TooltipModule,
    DialogModule,
    MultiSelectModule,
    SelectModule,
    Toast
  ],
  templateUrl: './overdrive-card-admin.component.html',
  styleUrl: './overdrive-card-admin.component.scss',
  providers: [MessageService]
})
export class OverdriveCardAdminComponent {
  private readonly overdriveService = inject(OverDriveService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly userService = inject(UserService);

  /** Whether the current user may administer other users' cards; hides the whole section when false. */
  readonly permitted = computed(() => {
    const permissions = this.userService.currentUser()?.permissions;
    return !!permissions?.admin || !!permissions?.canManageAllOverdriveCards;
  });

  loading = signal(false);
  loaded = signal(false);
  error = signal<string | null>(null);
  cards = signal<OverDriveManagedCard[]>([]);
  /** Free-text filter over owner name, card label, card id and library key. */
  filter = signal('');

  /** Cards grouped by owner, owners and cards each in the order the server sorted them. */
  readonly groups = computed<OwnerGroup[]>(() => {
    const needle = this.filter().trim().toLowerCase();
    const matches = (card: OverDriveManagedCard) => !needle || [
      card.ownerName, card.name ?? '', card.cardId, card.libraryKey ?? ''
    ].some(field => field.toLowerCase().includes(needle));

    const byOwner = new Map<number, OwnerGroup>();
    for (const card of this.cards()) {
      if (!matches(card)) {
        continue;
      }
      const group = byOwner.get(card.ownerUserId)
        ?? { ownerUserId: card.ownerUserId, ownerName: card.ownerName, cards: [] };
      group.cards.push(card);
      byOwner.set(card.ownerUserId, group);
    }
    return [...byOwner.values()];
  });

  readonly totalShown = computed(() => this.groups().reduce((n, g) => n + g.cards.length, 0));

  // ── Sharing dialog ──────────────────────────────────────────────────

  shareDialogVisible = signal(false);
  shareCard = signal<OverDriveManagedCard | null>(null);
  shareableUsers = signal<OverDriveShareUser[]>([]);
  selectedShareUserIds = signal<number[]>([]);
  savingShares = signal(false);

  /** Load (or reload) every user's cards. Called on expand rather than on construction. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.overdriveService.allCards().subscribe({
      next: (cards) => {
        this.cards.set(cards ?? []);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: (err) => {
        this.loading.set(false);
        this.loaded.set(true);
        this.error.set(this.messageOf(err, 'Could not load cards'));
      }
    });
  }

  cardLabel(card: OverDriveManagedCard): string {
    return card.name || card.cardId;
  }

  userOptionLabel(user: OverDriveShareUser): string {
    return user.name ? `${user.name} (${user.username})` : user.username;
  }

  /** Human-readable token expiry; the epoch value itself is non-sensitive. */
  formatEpoch(epochSeconds: number): string {
    return new Date(epochSeconds * 1000).toLocaleString();
  }

  /** True once a card's token expiry is in the past — the usual reason to refresh or re-link. */
  isExpired(card: OverDriveManagedCard): boolean {
    return !!card.tokenExpiresAt && card.tokenExpiresAt * 1000 < Date.now();
  }

  onRenameCard(card: OverDriveManagedCard, name: string): void {
    const label = name.trim();
    this.overdriveService.setCardLabel(card.cardId, label, card.ownerUserId).subscribe({
      next: () => {
        this.patchCard(card, { name: label || null });
        this.messageService.add({
          severity: 'success', summary: 'Renamed',
          detail: label ? `${card.ownerName}'s card labelled "${label}"` : `Label cleared on ${card.ownerName}'s card`
        });
      },
      error: (err) => this.error.set(this.messageOf(err, 'Rename failed'))
    });
  }

  onRefreshCard(card: OverDriveManagedCard): void {
    this.overdriveService.refreshCard(card.cardId, card.ownerUserId).subscribe({
      next: () => this.messageService.add({
        severity: 'success', summary: 'Refreshed',
        detail: `Re-linked ${this.cardLabel(card)} for ${card.ownerName}`
      }),
      error: (err) => this.error.set(this.messageOf(err, 'Refresh failed'))
    });
  }

  /**
   * Unlink another user's card. Confirmed first: it revokes the owner's access and drops the card's
   * shares, and it can only be undone by that user re-linking with their own credentials.
   */
  onUnlinkCard(card: OverDriveManagedCard): void {
    const shared = card.sharedWithCount
      ? ` It is shared with ${card.sharedWithCount} other user(s), and those shares will be removed too.`
      : '';
    this.confirmationService.confirm({
      header: 'Unlink this card?',
      message: `${this.cardLabel(card)} belongs to ${card.ownerName}. Unlinking clears its stored token and `
        + `credentials, so they lose access until they link it again — which only they can do.${shared}`,
      icon: 'pi pi-exclamation-triangle',
      acceptButtonProps: { label: 'Unlink', severity: 'danger' },
      rejectButtonProps: { label: 'Cancel', severity: 'secondary' },
      accept: () => this.overdriveService.removeCard(card.cardId, card.ownerUserId).subscribe({
        next: () => {
          this.cards.update(cards => cards.filter(c => !this.sameCard(c, card)));
          this.messageService.add({
            severity: 'success', summary: 'Unlinked',
            detail: `Removed ${this.cardLabel(card)} from ${card.ownerName}`
          });
        },
        error: (err) => this.error.set(this.messageOf(err, 'Unlink failed'))
      })
    });
  }

  /** Open the share dialog for a card, loading its owner's candidate users and current shares. */
  openShareDialog(card: OverDriveManagedCard): void {
    this.shareCard.set(card);
    this.selectedShareUserIds.set([]);
    this.shareableUsers.set([]);
    this.shareDialogVisible.set(true);
    // Scoped to the owner, so the owner is excluded as a target and the manager themselves is offered.
    this.overdriveService.shareableUsers(card.ownerUserId).subscribe({
      next: (users) => this.shareableUsers.set(users ?? []),
      error: () => this.shareableUsers.set([])
    });
    this.overdriveService.listShares(card.cardId, card.ownerUserId).subscribe({
      next: (shares) => this.selectedShareUserIds.set((shares ?? []).map(s => s.userId)),
      error: () => this.selectedShareUserIds.set([])
    });
  }

  saveShares(): void {
    const card = this.shareCard();
    if (!card) {
      return;
    }
    const ids = this.selectedShareUserIds();
    this.savingShares.set(true);
    this.overdriveService.setShares(card.cardId, ids, card.ownerUserId).subscribe({
      next: () => {
        this.savingShares.set(false);
        this.shareDialogVisible.set(false);
        this.patchCard(card, { sharedWithCount: ids.length });
        this.messageService.add({
          severity: 'success', summary: 'Sharing updated',
          detail: ids.length
            ? `${this.cardLabel(card)} shared with ${ids.length} user(s)`
            : `Sharing cleared for ${this.cardLabel(card)}`
        });
      },
      error: (err) => {
        this.savingShares.set(false);
        this.error.set(this.messageOf(err, 'Share update failed'));
      }
    });
  }

  // ── Link a card for another user ─────────────────────────────────────

  linkDialogVisible = signal(false);
  /** Users a card can be linked for; reuses the share roster, which already excludes you. */
  linkableUsers = signal<OverDriveShareUser[]>([]);
  linkForUserId = signal<number | null>(null);
  linkLibraryKey = signal('');
  linkCardNumber = signal('');
  linkPin = signal('');
  linking = signal(false);
  linkError = signal<string | null>(null);
  resolvingLinkKey = signal(false);
  linkKeyResolution = signal<OverDriveLibraryResolution | null>(null);

  openLinkDialog(): void {
    this.linkForUserId.set(null);
    this.linkLibraryKey.set('');
    this.linkCardNumber.set('');
    this.linkPin.set('');
    this.linkError.set(null);
    this.linkKeyResolution.set(null);
    this.linkDialogVisible.set(true);
    this.overdriveService.shareableUsers().subscribe({
      next: (users) => this.linkableUsers.set(users ?? []),
      error: () => this.linkableUsers.set([])
    });
  }

  /** Validate the typed library key against the Thunder directory and show the resolved name. */
  checkLinkKey(): void {
    const key = this.linkLibraryKey().trim();
    if (!key) {
      this.linkKeyResolution.set(null);
      return;
    }
    this.resolvingLinkKey.set(true);
    this.overdriveService.resolveLibrary(key).subscribe({
      next: (res) => {
        this.linkKeyResolution.set(res);
        this.resolvingLinkKey.set(false);
      },
      error: () => {
        this.linkKeyResolution.set({ valid: false, libraryKey: key, name: null });
        this.resolvingLinkKey.set(false);
      }
    });
  }

  /**
   * Link a card+PIN the user handed over, owned by them rather than by you. The PIN is sent once and
   * stored encrypted server-side (when a credential key is configured); it is never read back.
   */
  onLinkForUser(): void {
    const userId = this.linkForUserId();
    const key = this.linkLibraryKey().trim();
    const number = this.linkCardNumber().trim();
    if (userId == null) {
      this.linkError.set('Choose which user this card belongs to');
      return;
    }
    if (!key) {
      this.linkError.set('Enter the library key for this card');
      return;
    }
    if (!number) {
      this.linkError.set('Card number is required');
      return;
    }
    this.linking.set(true);
    this.linkError.set(null);
    this.overdriveService.linkCard(key, number, this.linkPin(), userId).subscribe({
      next: (cards) => {
        this.linking.set(false);
        this.linkDialogVisible.set(false);
        const owner = this.linkableUsers().find(u => u.userId === userId);
        this.messageService.add({
          severity: 'success', summary: 'Card linked',
          detail: `Linked ${cards?.length ?? 0} card(s) for ${owner ? this.userOptionLabel(owner) : 'that user'}`
        });
        // The new rows belong to another user, so re-fetch rather than patching local state.
        this.load();
      },
      error: (err) => {
        this.linking.set(false);
        this.linkError.set(this.messageOf(err, 'Link failed'));
      }
    });
  }

  /** Identity alone isn't unique across users — a card row is (ownerUserId, cardId). */
  private sameCard(a: OverDriveManagedCard, b: OverDriveManagedCard): boolean {
    return a.cardId === b.cardId && a.ownerUserId === b.ownerUserId;
  }

  private patchCard(card: OverDriveManagedCard, patch: Partial<OverDriveManagedCard>): void {
    this.cards.update(cards => cards.map(c => this.sameCard(c, card) ? { ...c, ...patch } : c));
  }

  private messageOf(err: unknown, fallback: string): string {
    const e = err as { error?: { message?: string }; message?: string } | null;
    return e?.error?.message || e?.message || fallback;
  }
}
