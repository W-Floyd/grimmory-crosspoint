package org.booklore.model.dto.overdrive;

/**
 * A user for the card-sharing picker / share list: minimal identity fields only (no permissions or
 * settings), safe to expose to any authenticated user choosing who to share a card with.
 */
public record OverDriveShareUser(Long userId, String username, String name) {}
