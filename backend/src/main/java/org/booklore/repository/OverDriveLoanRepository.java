package org.booklore.repository;

import org.booklore.model.entity.OverDriveLoanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for OverDrive loan entities.
 *
 * <p><b>Always scope loan lookups by user id.</b> A shared card caches the same loan under multiple
 * users (owner + each sharee), so any query keyed on {@code identity} or {@code overdriveLoanId}
 * alone can match several rows — a scalar/Optional return would throw NonUniqueResultException, and a
 * List return would leak other users' loans into the wrong user's view. Deliberately no
 * {@code findByIdentity*} / {@code findByOverdriveLoanIdAndIdentity} / {@code findActiveLoanForBook}
 * finders exist for that reason.
 */
@Repository
public interface OverDriveLoanRepository extends JpaRepository<OverDriveLoanEntity, Long> {

     Optional<OverDriveLoanEntity> findByUserIdAndOverdriveLoanId(Long userId, String overdriveLoanId);

     List<OverDriveLoanEntity> findByUserId(Long userId);

     List<OverDriveLoanEntity> findByUserIdAndStateIn(Long userId, List<String> states);
}
