package org.booklore.model.dto.overdrive;

/**
 * A linked OverDrive/Libby library card: the card id used for loans/borrowing, a display name, the
 * library "advantage key" used to scope catalog search, and whether encrypted card credentials are
 * stored for it ({@code true} only for card+PIN links with a credential key configured — those can be
 * refreshed/re-linked without re-entering the PIN).
 *
 * <p>{@code owned} is {@code false} for a card another user shared with you: you can borrow/hold/view
 * with it, but management (unlink, relabel, default library) is hidden. Renewing its token is not
 * management — a sharee's borrow renews the owner's row when needed. {@code ownerName} is
 * the sharer's display name on such cards (null when you own it). {@code sharedWithCount} is how many
 * other users you've shared this card with (0 on cards you don't own).
 *
 * <p>{@code canAutoRenew} is true when the card can silently re-link its token on expiry (card+PIN on
 * file and credential storage currently enabled) — the requirement for audiobook downloads too. It does
 * not depend on ownership: a shared card renews the owner's stored row, so it keeps working for every
 * sharee. {@code tokenExpiresAt} is the epoch-seconds token expiry (non-sensitive), or null.
 *
 * <p>{@code churnCooldownUntil} is set while OverDrive has the card flagged for borrowing and
 * returning too much: borrows and returns are paused until then, syncing is not. Null when free.
 *
 * <p>{@code borrowLimitReached} and {@code returnLimitReached} name an administrator's ceiling the
 * card has hit ("100 of 100 borrowed this 30 days"), or null. The two are counted separately against
 * the same numbers, since a card can be out of borrows while still free to hand books back. Both are
 * null while the card is resting — OverDrive's own refusal outranks a ceiling we set, and is reported
 * through {@code churnCooldownUntil} instead.
 */
public record OverDriveCard(String cardId, String name, String libraryKey, boolean credentialsStored,
                            Long defaultLibraryId, Long defaultPathId,
                            boolean owned, String ownerName, int sharedWithCount,
                            boolean canAutoRenew, Long tokenExpiresAt, String churnCooldownUntil,
                            String borrowLimitReached, String returnLimitReached) {}
