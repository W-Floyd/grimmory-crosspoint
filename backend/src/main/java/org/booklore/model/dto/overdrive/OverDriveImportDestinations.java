package org.booklore.model.dto.overdrive;

/**
 * A user's per-document-type OverDrive import destinations. Each {@code (libraryId, pathId)} pair is a
 * Grimmory library + path; a null library or path means "not set" (falls back to Bookdrop). Ebooks
 * (EPUB/PDF) use the ebook pair; audiobooks use the audiobook pair; magazines (PDF/EPUB) use the
 * magazine pair.
 */
public record OverDriveImportDestinations(
        Long ebookLibraryId, Long ebookPathId,
        Long audiobookLibraryId, Long audiobookPathId,
        Long magazineLibraryId, Long magazinePathId) {}
