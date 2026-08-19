package org.booklore.model.dto.overdrive;

/**
 * A user's OverDrive automation opt-in.
 *
 * @param autoImportLoans import loans found on sync (including ones borrowed in the Libby app)
 * @param autoBorrowHolds borrow holds that have become available — implies {@code autoImportLoans},
 *                        since a borrow with nowhere to land just burns the hold
 */
public record OverDriveAutoSyncSettings(boolean autoImportLoans, boolean autoBorrowHolds) {
}
