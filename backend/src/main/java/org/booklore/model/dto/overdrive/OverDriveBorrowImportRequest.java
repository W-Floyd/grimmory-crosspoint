package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Request body for borrowing an OverDrive title and importing it into a library.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveBorrowImportRequest {
    /** OverDrive title id to borrow (from catalog search). */
    private String titleId;
    /** Target library id. */
    private Long libraryId;
    /** Target library path id within that library. */
    private Long pathId;
    /** Optional book title (used for the on-disk filename / naming pattern). */
    private String title;
    /** Optional primary author (used for the naming pattern). */
    private String author;
    /** Optional cover image URL from the catalog result (applied as the book thumbnail). */
    private String coverUrl;
    /** Optional ISBN from the catalog result (applied to the book metadata). */
    private String isbn;
    /**
     * Optional format id the user selected (e.g. {@code ebook-epub-adobe}). Honored only when the loan
     * offers it and it's importable; otherwise the operator's format preference decides.
     */
    private String formatId;
    /**
     * Optional media-type hint ("audiobook"/"ebook"/"magazine") so the borrow request's
     * {@code title_format} matches the title (audiobooks must borrow as "audiobook"). Defaults to ebook.
     */
    private String titleFormat;
}
