package org.booklore.repository;

import org.booklore.model.entity.OverDriveAuditEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for per-user OverDrive activity history.
 */
@Repository
public interface OverDriveAuditRepository extends JpaRepository<OverDriveAuditEntity, Long> {

    List<OverDriveAuditEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
