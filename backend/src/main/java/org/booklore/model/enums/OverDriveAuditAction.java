package org.booklore.model.enums;

/**
 * The kinds of OverDrive activity recorded in a user's history ({@code overdrive_audit}).
 * Stored by {@code name()} as a string, so values may be added freely without a schema change.
 */
public enum OverDriveAuditAction {
    BORROW,
    BORROW_AND_IMPORT,
    IMPORT,
    /** An unattended borrow of a hold that came in (per-user opt-in). Only failures are logged here —
      * a successful automatic borrow records BORROW_AND_IMPORT like any other. */
    AUTO_BORROW,
    /** An unattended import of an existing loan. As above, only failures use this action. */
    AUTO_IMPORT,
    /** An unattended return of a loan held past its configured age. Failures only; a success is RETURN. */
    AUTO_RETURN,
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
