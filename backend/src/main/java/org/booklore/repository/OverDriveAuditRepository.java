package org.booklore.repository;

import org.booklore.model.entity.OverDriveAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

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
     * <p>Callers pass the actions that count. For a borrow rate that is a plain borrow and a
     * borrow-then-import; {@code IMPORT} is deliberately excluded — it means an existing loan was
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

    /**
     * All five window counts for a card, for one kind of action, in one query.
     *
     * <p>Checking a ceiling used to be one query per window, which was cheap while almost no card had
     * one configured. Defaults changed that: every card now has all five, and the check runs inside
     * per-title, per-library loops, so a large bookbag turned a poll into hundreds of round trips.
     * Conditional aggregation over the widest window answers all of them at once.
     *
     * @return one row: counts for the last minute, hour, day, week and thirty days
     */
    @Query("SELECT "
            + "SUM(CASE WHEN a.createdAt >= :minute THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.createdAt >= :hour THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.createdAt >= :day THEN 1 ELSE 0 END), "
            + "SUM(CASE WHEN a.createdAt >= :week THEN 1 ELSE 0 END), "
            + "COUNT(a) "
            + "FROM OverDriveAuditEntity a WHERE a.identity = :identity AND a.success = true "
            + "AND a.createdAt >= :month AND a.action IN :actions")
    List<Object[]> countActionsPerWindow(@Param("identity") String identity,
                                         @Param("actions") java.util.Collection<String> actions,
                                         @Param("minute") java.time.Instant minute,
                                         @Param("hour") java.time.Instant hour,
                                         @Param("day") java.time.Instant day,
                                         @Param("week") java.time.Instant week,
                                         @Param("month") java.time.Instant month);

    /**
     * When this card last took a copy out or gave one back, across every user who holds it.
     *
     * <p>Pacing has to be a property of the card, not of a pass. A shared card is one account at the
     * library however many people hold it, and each of their passes starts its own counter — so
     * without this, two users could act on the same card back to back and each believe it was their
     * first action.
     *
     * <p>Durable for the same reason: an in-memory counter forgets everything on restart, and a
     * restart is exactly when a pass is likely to start.
     */
    @Query("SELECT MAX(a.createdAt) FROM OverDriveAuditEntity a WHERE a.identity = :identity "
            + "AND a.success = true AND a.action IN :actions")
    java.time.Instant lastActionOnCard(@Param("identity") String identity,
                                       @Param("actions") java.util.Collection<String> actions);
}
