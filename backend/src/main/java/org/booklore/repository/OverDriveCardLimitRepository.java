package org.booklore.repository;

import org.booklore.model.entity.OverDriveCardLimitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** Borrow-rate ceilings per card identity. Absent row (or null column) means no known ceiling. */
@Repository
public interface OverDriveCardLimitRepository extends JpaRepository<OverDriveCardLimitEntity, String> {

    List<OverDriveCardLimitEntity> findByIdentityIn(Collection<String> identities);
}
