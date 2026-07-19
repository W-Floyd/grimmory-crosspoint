package org.booklore.repository;

import org.booklore.model.entity.OverDriveCardShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for OverDrive card shares — grants of borrow/hold/view access to another user.
 */
@Repository
public interface OverDriveCardShareRepository extends JpaRepository<OverDriveCardShareEntity, Long> {

    List<OverDriveCardShareEntity> findByTokenId(Long tokenId);

    List<OverDriveCardShareEntity> findBySharedWithUserId(Long sharedWithUserId);

    int countByTokenId(Long tokenId);

    void deleteByTokenId(Long tokenId);
}
