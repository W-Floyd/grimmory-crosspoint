package org.booklore.repository;

import org.booklore.model.entity.OverDriveBookbagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * A user's queue of wanted titles.
 *
 * <p>Always scoped by user id: the bag is personal, and an unscoped lookup by title id would match
 * every user who wants the same book.
 */
@Repository
public interface OverDriveBookbagRepository extends JpaRepository<OverDriveBookbagEntity, Long> {

    List<OverDriveBookbagEntity> findByUserIdOrderByPositionAscIdAsc(Long userId);

    Optional<OverDriveBookbagEntity> findByUserIdAndTitleId(Long userId, String titleId);

    /** Users with at least one queued title — they need polling even with every switch off. */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT b.userId FROM OverDriveBookbagEntity b")
    List<Long> findDistinctUserIds();
}
