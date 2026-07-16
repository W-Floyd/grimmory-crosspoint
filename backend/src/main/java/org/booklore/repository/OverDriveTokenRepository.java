package org.booklore.repository;

import org.booklore.model.entity.OverDriveTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for persisted OverDrive auth tokens, one per Grimmory user.
 */
@Repository
public interface OverDriveTokenRepository extends JpaRepository<OverDriveTokenEntity, Long> {

    Optional<OverDriveTokenEntity> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    void deleteByUserId(Long userId);
}
