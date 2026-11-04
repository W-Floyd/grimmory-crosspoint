package org.booklore.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
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

    /** A token as stored, bypassing the converter: for encrypting rows written before a key existed. */
    interface StoredToken {
        Long getId();

        String getToken();
    }

    @Query(value = "SELECT id, token FROM overdrive_token WHERE token NOT LIKE 'enc:v1:%'", nativeQuery = true)
    List<StoredToken> findStoredUnencryptedTokens();

    @Modifying
    @Query(value = "UPDATE overdrive_token SET token = :token WHERE id = :id", nativeQuery = true)
    int writeStoredToken(@Param("id") Long id, @Param("token") String token);
}
