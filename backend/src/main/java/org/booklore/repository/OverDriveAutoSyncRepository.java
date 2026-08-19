package org.booklore.repository;

import org.booklore.model.entity.OverDriveAutoSyncEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for per-user OverDrive automation opt-in.
 */
@Repository
public interface OverDriveAutoSyncRepository extends JpaRepository<OverDriveAutoSyncEntity, Long> {

    Optional<OverDriveAutoSyncEntity> findByUserId(Long userId);

    /**
     * Every user who opted into at least one automated action. This is the poller's work list — users
     * with no row, or with both flags off, are never touched.
     */
    @Query("SELECT a FROM OverDriveAutoSyncEntity a WHERE a.autoImportLoans = true OR a.autoBorrowHolds = true")
    List<OverDriveAutoSyncEntity> findAllOptedIn();
}
