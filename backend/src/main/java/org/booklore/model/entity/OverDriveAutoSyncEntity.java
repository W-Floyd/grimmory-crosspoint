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
}
