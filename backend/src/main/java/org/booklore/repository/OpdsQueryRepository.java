package org.booklore.repository;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.ReadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** The fork's OPDS feed queries (format and read-status facets, continue reading), kept out of upstream's repositories. */
public interface OpdsQueryRepository extends Repository<BookEntity, Long> {

    /** Distinct book formats present in the given libraries, for building format facets. */
    @Query("""
            SELECT DISTINCT bf.bookType FROM BookEntity b
            JOIN b.bookFiles bf
            WHERE b.library.id IN :libraryIds
              AND (b.deleted IS NULL OR b.deleted = false)
              AND bf.isBookFormat = true
            ORDER BY bf.bookType
            """)
    List<BookFileType> findDistinctBookTypesByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    @Query("""
            SELECT DISTINCT b.id FROM BookEntity b
            JOIN b.bookFiles bf
            WHERE b.library.id IN :libraryIds
              AND (b.deleted IS NULL OR b.deleted = false)
              AND bf.isBookFormat = true
              AND bf.bookType = :bookType
            ORDER BY b.addedOn DESC
            """)
    Page<Long> findBookIdsByLibraryIdsAndBookType(@Param("libraryIds") Collection<Long> libraryIds, @Param("bookType") BookFileType bookType, Pageable pageable);

    /**
     * Paginated variant of {@link UserBookProgressRepository#findTopContinueReadingBookIds}: in-progress (non-audiobook)
     * book IDs ordered by most recently read, scoped to the given libraries, with a total count
     * for OPDS pagination.
     */
    @Query(value = """
            SELECT DISTINCT ubp.book.id FROM UserBookProgressEntity ubp
            JOIN ubp.book b
            JOIN b.bookFiles bf
            WHERE ubp.user.id = :userId
              AND ubp.readStatus IN (org.booklore.model.enums.ReadStatus.READING, org.booklore.model.enums.ReadStatus.RE_READING)
              AND (b.deleted IS NULL OR b.deleted = false)
              AND bf.isBookFormat = true
              AND bf.bookType <> org.booklore.model.enums.BookFileType.AUDIOBOOK
              AND b.library.id IN :libraryIds
              AND ubp.lastReadTime IS NOT NULL
            ORDER BY ubp.lastReadTime DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT ubp.book.id) FROM UserBookProgressEntity ubp
            JOIN ubp.book b
            JOIN b.bookFiles bf
            WHERE ubp.user.id = :userId
              AND ubp.readStatus IN (org.booklore.model.enums.ReadStatus.READING, org.booklore.model.enums.ReadStatus.RE_READING)
              AND (b.deleted IS NULL OR b.deleted = false)
              AND bf.isBookFormat = true
              AND bf.bookType <> org.booklore.model.enums.BookFileType.AUDIOBOOK
              AND b.library.id IN :libraryIds
              AND ubp.lastReadTime IS NOT NULL
            """)
    Page<Long> findContinueReadingBookIds(
            @Param("userId") Long userId,
            @Param("libraryIds") Collection<Long> libraryIds,
            Pageable pageable);

    /**
     * Book IDs for the given read statuses, scoped to the given libraries, ordered by most
     * recently read. Backs OPDS read-status facets.
     */
    @Query(value = """
            SELECT DISTINCT ubp.book.id FROM UserBookProgressEntity ubp
            JOIN ubp.book b
            WHERE ubp.user.id = :userId
              AND ubp.readStatus IN :statuses
              AND (b.deleted IS NULL OR b.deleted = false)
              AND b.library.id IN :libraryIds
            ORDER BY ubp.lastReadTime DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT ubp.book.id) FROM UserBookProgressEntity ubp
            JOIN ubp.book b
            WHERE ubp.user.id = :userId
              AND ubp.readStatus IN :statuses
              AND (b.deleted IS NULL OR b.deleted = false)
              AND b.library.id IN :libraryIds
            """)
    Page<Long> findBookIdsByReadStatus(
            @Param("userId") Long userId,
            @Param("libraryIds") Collection<Long> libraryIds,
            @Param("statuses") Collection<ReadStatus> statuses,
            Pageable pageable);
}
