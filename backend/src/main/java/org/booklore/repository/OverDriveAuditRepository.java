package org.booklore.repository;

import org.booklore.model.entity.OverDriveAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for per-user OverDrive activity history.
 */
@Repository
public interface OverDriveAuditRepository extends JpaRepository<OverDriveAuditEntity, Long> {

    /**
     * One page of a user's history, newest first. Returns a {@link Page} rather than a list so the
     * total is available without a second query — the client needs it to size the paginator.
     */
    Page<OverDriveAuditEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * How many checkouts a card has taken since a moment, across every user who holds it.
     *
     * <p>Counts only the actions that actually consume a checkout: a plain borrow, and a
     * borrow-then-import. {@code IMPORT} is deliberately excluded — it means an existing loan was
     * resumed and fulfilled, which spends no new checkout and so does not count against a borrow rate.
     * Failures are excluded too: a refused borrow is not a borrow, and counting our own retries would
     * make a rate-limited card look busier the longer it stayed limited.
     *
     * <p>Scoped by identity rather than user because the library's limit belongs to the patron, and a
     * shared card's budget is spent by whoever borrows on it.
     */
    @Query("SELECT COUNT(a) FROM OverDriveAuditEntity a WHERE a.identity = :identity "
            + "AND a.success = true AND a.createdAt >= :since "
            + "AND a.action IN ('BORROW', 'BORROW_AND_IMPORT')")
    long countBorrowsOnCardSince(@Param("identity") String identity, @Param("since") java.time.Instant since);
}
