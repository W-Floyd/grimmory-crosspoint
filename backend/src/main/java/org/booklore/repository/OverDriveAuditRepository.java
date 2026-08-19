package org.booklore.repository;

import org.booklore.model.entity.OverDriveAuditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
}
