package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** The fork's book queries, kept out of upstream's BookRepository so merges don't conflict on it. */
public interface OverDriveBookRepository extends Repository<BookEntity, Long> {

    // Like findByIdWithBookFiles but also eager-fetches metadata.authors (+ other metadata collections),
    // so callers that map the full book outside an active session (e.g. building an add-notification
    // payload) don't trip a LazyInitializationException on authors.
    @EntityGraph(attributePaths = { "metadata", "metadata.authors", "metadata.categories", "metadata.moods", "metadata.tags", "metadata.comicMetadata", "shelves", "libraryPath", "library", "bookFiles" })
    @Query("SELECT b FROM BookEntity b WHERE b.id = :id AND (b.deleted IS NULL OR b.deleted = false)")
    Optional<BookEntity> findByIdWithBookFilesAndMetadata(@Param("id") Long id);

    /** Ids of non-deleted books matching an ISBN (used to link OverDrive titles to existing library books). */
    @Query("SELECT b.id FROM BookEntity b WHERE b.metadata.isbn13 = :isbn AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByIsbn13(@Param("isbn") String isbn);

    @Query("SELECT b.id FROM BookEntity b WHERE b.metadata.isbn10 = :isbn AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByIsbn10(@Param("isbn") String isbn);

    /** Ids of non-deleted books matching an ASIN (links OverDrive titles — e.g. audiobooks — with no
     *  matching ISBN to existing library books). */
    @Query("SELECT b.id FROM BookEntity b WHERE upper(b.metadata.asin) = upper(:asin) AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByAsin(@Param("asin") String asin);

    // Library-scoped variants: match only within the caller's accessible libraries, so a cross-reference
    // (e.g. OverDrive loan → existing book) can't reveal a book id from a library the user cannot access.
    @Query("SELECT b.id FROM BookEntity b WHERE b.metadata.isbn13 = :isbn AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByIsbn13AndLibraryIdIn(@Param("isbn") String isbn, @Param("libraryIds") java.util.Collection<Long> libraryIds);

    @Query("SELECT b.id FROM BookEntity b WHERE b.metadata.isbn10 = :isbn AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByIsbn10AndLibraryIdIn(@Param("isbn") String isbn, @Param("libraryIds") java.util.Collection<Long> libraryIds);

    @Query("SELECT b.id FROM BookEntity b WHERE upper(b.metadata.asin) = upper(:asin) AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByAsinAndLibraryIdIn(@Param("asin") String asin, @Param("libraryIds") java.util.Collection<Long> libraryIds);

    /**
     * Ids of non-deleted books imported from a given OverDrive title. The exact key: an OverDrive id
     * identifies one edition, so a hit means this is the same file we would download again, not merely
     * the same work. ISBN and ASIN matching stay as fallbacks for titles that carry no recorded id.
     */
    @Query("SELECT b.id FROM BookEntity b WHERE b.metadata.overdriveId = :overdriveId AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByOverdriveId(@Param("overdriveId") String overdriveId);

    @Query("SELECT b.id FROM BookEntity b WHERE b.metadata.overdriveId = :overdriveId AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Long> findIdsByOverdriveIdAndLibraryIdIn(@Param("overdriveId") String overdriveId, @Param("libraryIds") java.util.Collection<Long> libraryIds);

    /**
     * Whether this OverDrive edition was ever imported, including into a book since deleted.
     *
     * <p>Deliberately ignores the deleted flag, which the linking queries above must not. A removed
     * book cannot be linked to — but a soft delete leaves its file where it was, so the fact that it
     * once existed is what says a re-import would land on a file already on disk. Without this, the
     * only way to discover that is to download the whole book and collide at the last step.
     */
    @Query("SELECT COUNT(b) FROM BookEntity b WHERE b.metadata.overdriveId = :overdriveId")
    long countEverImportedFromOverdriveId(@Param("overdriveId") String overdriveId);

    /**
     * OverDrive id paired with the book's title, for a batch of ids. Lets the history view name a title
     * whose audit row recorded only an id, in one query per page rather than one per row.
     */
    @Query("SELECT b.metadata.overdriveId, b.metadata.title FROM BookEntity b "
            + "WHERE b.metadata.overdriveId IN :overdriveIds AND b.metadata.title IS NOT NULL "
            + "AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Object[]> findOverdriveIdTitlePairs(@Param("overdriveIds") java.util.Collection<String> overdriveIds);

    /**
     * OverDrive id paired with the book's title and its library id, for a batch of ids. The history view
     * needs both to repair a row written before we stamped them: the title to name it, the id to link it.
     */
    @Query("SELECT b.metadata.overdriveId, b.metadata.title, b.id FROM BookEntity b "
            + "WHERE b.metadata.overdriveId IN :overdriveIds "
            + "AND (b.deleted IS NULL OR b.deleted = false)")
    java.util.List<Object[]> findOverdriveIdBookRefs(@Param("overdriveIds") java.util.Collection<String> overdriveIds);
}
