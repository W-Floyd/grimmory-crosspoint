package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A user's opt-in for unattended OverDrive activity. Absent row = opted out, so the poller acts only
 * for users who deliberately turned this on.
 */
@Entity
@Table(name = "overdrive_auto_sync")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveAutoSyncEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Import loans found on sync — including titles borrowed in the Libby app — into the library. */
    @Column(name = "auto_import_loans", nullable = false)
    @Builder.Default
    private boolean autoImportLoans = false;

    /**
     * Borrow holds that have become available. Borrowing without importing would just consume the hold
     * and leave the book nowhere, so this implies {@link #autoImportLoans} — enforced at the service
     * layer rather than in the schema so the two flags stay independently readable.
     */
    @Column(name = "auto_borrow_holds", nullable = false)
    @Builder.Default
    private boolean autoBorrowHolds = false;

    /** Return loans automatically once they have been held long enough. */
    @Column(name = "auto_return_enabled", nullable = false)
    @Builder.Default
    private boolean autoReturnEnabled = false;

    /** How long a loan must be held before it is eligible for automatic return. */
    @Column(name = "auto_return_min_age_days", nullable = false)
    @Builder.Default
    private int autoReturnMinAgeDays = 14;

    /**
     * Width of the random window after the minimum age within which the return actually happens.
     * 0 returns exactly at the minimum age.
     */
    @Column(name = "auto_return_max_delay_hours", nullable = false)
    @Builder.Default
    private int autoReturnMaxDelayHours = 48;

    /** Skip the random delay when the title has holds queued, so waiters are not held up. */
    @Column(name = "auto_return_prompt_when_waitlisted", nullable = false)
    @Builder.Default
    private boolean autoReturnPromptWhenWaitlisted = true;
}
