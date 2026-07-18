package org.booklore.repository;

import org.booklore.model.entity.OverDriveLoanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for OverDrive loan entities.
 */
@Repository
public interface OverDriveLoanRepository extends JpaRepository<OverDriveLoanEntity, Long> {

     List<OverDriveLoanEntity> findByIdentity(String identity);

     Optional<OverDriveLoanEntity> findByOverdriveLoanIdAndIdentity(String overdriveLoanId, String identity);

     Optional<OverDriveLoanEntity> findByUserIdAndOverdriveLoanId(Long userId, String overdriveLoanId);

     List<OverDriveLoanEntity> findByUserId(Long userId);

     List<OverDriveLoanEntity> findByUserIdAndStateIn(Long userId, List<String> states);

     List<OverDriveLoanEntity> findByState(String state);

     List<OverDriveLoanEntity> findByStateIn(List<String> states);

     List<OverDriveLoanEntity> findByIdentityAndStateIn(String identity, List<String> states);

     @Query("SELECT o FROM OverDriveLoanEntity o WHERE o.bookId = :bookId AND o.state IN ('BORROWED', 'ACTIVE')")
     Optional<OverDriveLoanEntity> findActiveLoanForBook(@Param("bookId") Long bookId);
}