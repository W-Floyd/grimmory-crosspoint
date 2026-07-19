package org.booklore.repository;

import org.booklore.model.entity.OverDriveImportDestinationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for per-user OverDrive per-document-type import destinations.
 */
@Repository
public interface OverDriveImportDestinationRepository extends JpaRepository<OverDriveImportDestinationEntity, Long> {

    Optional<OverDriveImportDestinationEntity> findByUserId(Long userId);
}
