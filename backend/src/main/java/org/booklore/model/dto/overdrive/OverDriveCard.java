package org.booklore.model.dto.overdrive;

/**
 * A linked OverDrive/Libby library card: the card id used for loans/borrowing, a display name, the
 * library "advantage key" used to scope catalog search, and whether encrypted card credentials are
 * stored for it ({@code true} only for card+PIN links with a credential key configured — those can be
 * refreshed/re-linked without re-entering the PIN).
 *
 * <p>{@code owned} is {@code false} for a card another user shared with you: you can borrow/hold/view
 * with it, but management (unlink, relabel, default library, refresh) is hidden. {@code ownerName} is
 * the sharer's display name on such cards (null when you own it). {@code sharedWithCount} is how many
 * other users you've shared this card with (0 on cards you don't own).
 */
public record OverDriveCard(String cardId, String name, String libraryKey, boolean credentialsStored,
                            Long defaultLibraryId, Long defaultPathId,
                            boolean owned, String ownerName, int sharedWithCount) {}
