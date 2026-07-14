package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.OpdsVariantHashEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OpdsVariantHashRepository extends JpaRepository<OpdsVariantHashEntity, Long> {

    /** Resolve the source book for a given optimized-variant hash (KOReader document id). */
    @Query("SELECT v.book FROM OpdsVariantHashEntity v WHERE v.variantHash = :hash")
    Optional<BookEntity> findBookByVariantHash(@Param("hash") String hash);

    Optional<OpdsVariantHashEntity> findByBookFile_IdAndPreset(Long bookFileId, String preset);
}
