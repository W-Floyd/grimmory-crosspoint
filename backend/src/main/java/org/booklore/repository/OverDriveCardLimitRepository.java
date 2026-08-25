package org.booklore.repository;

import org.booklore.model.entity.OverDriveCardLimitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Borrow-rate ceilings per card identity. An absent row, or a null column, means this card sets no
 * ceiling of its own for that window and takes the deployment default; -1 means it has explicitly
 * opted out of one.
 */
@Repository
public interface OverDriveCardLimitRepository extends JpaRepository<OverDriveCardLimitEntity, String> {

    List<OverDriveCardLimitEntity> findByIdentityIn(Collection<String> identities);
}
