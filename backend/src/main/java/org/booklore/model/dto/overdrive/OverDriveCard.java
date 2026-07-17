package org.booklore.model.dto.overdrive;

/**
 * A linked OverDrive/Libby library card: the card id used for loans/borrowing, a display name, the
 * library "advantage key" used to scope catalog search, and whether encrypted card credentials are
 * stored for it ({@code true} only for card+PIN links with a credential key configured — those can be
 * refreshed/re-linked without re-entering the PIN).
 */
public record OverDriveCard(String cardId, String name, String libraryKey, boolean credentialsStored,
                            Long defaultLibraryId, Long defaultPathId) {}
