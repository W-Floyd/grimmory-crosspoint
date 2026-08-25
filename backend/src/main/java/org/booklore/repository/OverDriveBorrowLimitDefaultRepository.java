package org.booklore.repository;

import org.booklore.model.entity.OverDriveBorrowLimitDefaultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** The single row of deployment-wide borrow ceilings; see the entity for why there is only one. */
@Repository
public interface OverDriveBorrowLimitDefaultRepository
        extends JpaRepository<OverDriveBorrowLimitDefaultEntity, Byte> {
}
