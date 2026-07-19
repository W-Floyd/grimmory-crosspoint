package org.booklore.model.enums;

/**
 * The kinds of OverDrive activity recorded in a user's history ({@code overdrive_audit}).
 * Stored by {@code name()} as a string, so values may be added freely without a schema change.
 */
public enum OverDriveAuditAction {
    BORROW,
    BORROW_AND_IMPORT,
    IMPORT,
    RETURN,
    HOLD_PLACED,
    HOLD_CANCELLED,
    DOWNLOAD,
    CARD_LINKED,
    CARD_UNLINKED,
    CARD_RELABELED,
    CARD_REFRESHED,
    SHARE_UPDATED
}
