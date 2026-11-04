package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.OpdsVariantHashEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OpdsVariantHashRepository extends JpaRepository<OpdsVariantHashEntity, Long> {

    /**
     * Source books for an optimized-variant hash (KOReader document id), oldest registration first.
     * Not unique: presets with identical settings produce byte-identical variants.
     */
    @Query("SELECT v.book FROM OpdsVariantHashEntity v WHERE v.variantHash = :hash ORDER BY v.id")
    List<BookEntity> findBooksByVariantHash(@Param("hash") String hash);

    Optional<OpdsVariantHashEntity> findByBookFile_IdAndPreset(Long bookFileId, String preset);

    /** The source hash a variant of this file is named after, without loading the file entity. */
    @Query("SELECT COALESCE(f.currentHash, f.initialHash) FROM BookFileEntity f WHERE f.id = :id")
    Optional<String> findSourceHash(@Param("id") Long bookFileId);
}
