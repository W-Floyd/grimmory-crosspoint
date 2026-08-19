package org.booklore.model.dto.overdrive;

/**
 * A user's OverDrive automation opt-in.
 *
 * @param autoImportLoans  import loans found on sync (including ones borrowed in the Libby app)
 * @param autoBorrowHolds  borrow holds that have become available — implies {@code autoImportLoans},
 *                         since a borrow with nowhere to land just burns the hold
 * @param autoReturnEnabled            return loans automatically once held long enough
 * @param autoReturnMinAgeDays         how long a loan must be held before it is eligible
 * @param autoReturnMaxDelayHours      width of the random window after that age; 0 returns exactly
 *                                     at the minimum age
 * @param autoReturnPromptWhenWaitlisted skip that random delay when the title has holds queued, so
 *                                     people waiting are not held up for the sake of looking organic
 */
public record OverDriveAutoSyncSettings(
        boolean autoImportLoans,
        boolean autoBorrowHolds,
        boolean autoReturnEnabled,
        int autoReturnMinAgeDays,
        int autoReturnMaxDelayHours,
        boolean autoReturnPromptWhenWaitlisted) {
}
