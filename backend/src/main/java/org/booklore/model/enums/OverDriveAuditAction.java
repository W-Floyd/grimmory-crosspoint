package org.booklore.model.enums;

/**
 * The kinds of OverDrive activity recorded in a user's history ({@code overdrive_audit}).
 * Stored by {@code name()} as a string, so values may be added freely without a schema change.
 *
 * <p>These say <em>what happened</em>, not who caused it. Whether a row was the scheduled automation
 * or a person clicking is the separate {@code automated} flag, set for every action alike — which is
 * why the {@code AUTO_} values below are vestigial rather than a parallel set.
 */
public enum OverDriveAuditAction {
    BORROW,
    BORROW_AND_IMPORT,
    IMPORT,
    /** No longer written; the {@code automated} flag distinguishes an unattended borrow. Kept so a
      * history row on disk that still names it keeps its meaning. */
    AUTO_BORROW,
    /** An automatic import that found the title already in the library and linked to the existing book
      * rather than fetching it again. Success only — an auto-import that actually fetches records
      * IMPORT, and one that fails records an IMPORT failure. */
    AUTO_IMPORT,
    /** A failed unattended return of a loan held past its configured age. Failures only: a successful
      * automatic return records RETURN like any other. */
    AUTO_RETURN,
    RETURN,
    HOLD_PLACED,
    HOLD_CANCELLED,
    DOWNLOAD,
    CARD_LINKED,
    CARD_UNLINKED,
    CARD_RELABELED,
    CARD_REFRESHED,
    /** A card stopped completely: OverDrive refused or suspended it, or a person froze it. See
      * OverDriveService#freezeCard. */
    CARD_FROZEN,
    /** A frozen card put back in service, by hand or by a person re-linking it. */
    CARD_UNFROZEN,
    /** Borrows and returns paused after OverDrive reported too much churn, with the rate that tripped it. */
    CARD_COOLDOWN,
    /** A card's borrow ceilings changed. */
    CARD_LIMITS_CHANGED,
    /** Cards moved onto one Libby account so they sync together. */
    CARDS_UNIFIED,
    SHARE_UPDATED
}
