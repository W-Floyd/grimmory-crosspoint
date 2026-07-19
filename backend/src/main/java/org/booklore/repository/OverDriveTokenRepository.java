package org.booklore.repository;

import org.booklore.model.entity.OverDriveTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisted OverDrive auth tokens — one per linked (user, card).
 */
@Repository
public interface OverDriveTokenRepository extends JpaRepository<OverDriveTokenEntity, Long> {

    List<OverDriveTokenEntity> findByUserId(Long userId);

    /** All rows carrying this OverDrive identity (admin card-share management across owners). */
    List<OverDriveTokenEntity> findByIdentity(String identity);

    Optional<OverDriveTokenEntity> findByUserIdAndIdentity(Long userId, String identity);

    boolean existsByUserIdAndIdentity(Long userId, String identity);

    void deleteByUserIdAndIdentity(Long userId, String identity);
}
