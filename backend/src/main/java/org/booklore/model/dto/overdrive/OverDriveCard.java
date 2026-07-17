package org.booklore.model.dto.overdrive;

/**
 * A linked OverDrive/Libby library card: the card id used for loans/borrowing, a display name, and
 * the library "advantage key" used to scope catalog search.
 */
public record OverDriveCard(String cardId, String name, String libraryKey) {}
