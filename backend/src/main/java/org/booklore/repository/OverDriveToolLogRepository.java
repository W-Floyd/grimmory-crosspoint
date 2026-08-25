package org.booklore.repository;

import org.booklore.model.entity.OverDriveToolLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Handler output per run; see the entity for why it is one row rather than one per line. */
@Repository
public interface OverDriveToolLogRepository extends JpaRepository<OverDriveToolLogEntity, Long> {

    /** The latest run for a title, which is the only one anybody asks for. */
    Optional<OverDriveToolLogEntity> findFirstByUserIdAndTitleIdOrderByStartedAtDesc(Long userId, String titleId);

    /**
     * Drop runs older than a cutoff. Handler output is diagnostic, not a record: it is worth keeping
     * long enough to explain a failure somebody noticed, and not a day longer.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM OverDriveToolLogEntity l WHERE l.startedAt < :cutoff")
    int deleteStartedBefore(@Param("cutoff") Instant cutoff);
}
