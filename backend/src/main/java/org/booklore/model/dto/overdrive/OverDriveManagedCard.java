package org.booklore.model.dto.overdrive;

/**
 * A linked card as seen by a cross-user card manager: the same shape as {@link OverDriveCard}, minus
 * the viewer-relative fields (nothing here is "owned by" or "shared with" the caller), plus the owner.
 *
 * <p>{@code ownerUserId} is what management calls pass back as {@code userId} to name the target row:
 * {@code (user_id, identity)} is unique, so the same {@code cardId} can appear once per user who linked
 * it, and the identity alone is not enough to pick one.
 */
public record OverDriveManagedCard(String cardId, String name, String libraryKey, boolean credentialsStored,
                                   Long defaultLibraryId, Long defaultPathId, int sharedWithCount,
                                   boolean canAutoRenew, Long tokenExpiresAt,
                                   Long ownerUserId, String ownerName) {}
