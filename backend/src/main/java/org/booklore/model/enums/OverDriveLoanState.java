package org.booklore.model.enums;

public enum OverDriveLoanState {
    BORROWED,
    RETURNED,
    EXPIRED;

    /** We no longer hold this loan: the row is history, not a live checkout. */
    public boolean isTerminal() {
        return this != BORROWED;
    }
}
