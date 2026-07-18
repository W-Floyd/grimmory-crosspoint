package org.booklore.service.overdrive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.FileProcessResult;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.settings.LibraryFile;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.service.event.BookAddedEvent;
import org.booklore.service.file.FileMovingHelper;
import org.booklore.service.fileprocessor.BookFileProcessor;
import org.booklore.service.fileprocessor.BookFileProcessorRegistry;
import org.booklore.service.metadata.MetadataRefreshService;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.FileUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Imports an EPUB obtained from OverDrive (borrowed and fulfilled) into a Grimmory
 * library, reusing the same file-processing pipeline as the bookdrop finalize flow: the bytes are
 * written into the target library path under its naming pattern, then handed to the EPUB
 * {@link BookFileProcessor} which creates and persists the {@code BookEntity}.
 *
 * <p>Library monitoring is unregistered around the write so the filesystem watcher does not race to
 * import the same file, then re-registered afterward.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class OverDriveImportService {

    private final LibraryRepository libraryRepository;
    private final BookRepository bookRepository;
    private final FileMovingHelper fileMovingHelper;
    private final BookFileProcessorRegistry processorRegistry;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final MetadataRefreshService metadataRefreshService;
    private final ApplicationEventPublisher eventPublisher;
    private final org.booklore.mapper.BookMapper bookMapper;
    private final AppProperties appProperties;

    /**
     * Write the given book bytes into the target library/path, process them into a persisted book, and
     * apply the supplied OverDrive catalog metadata (cover, ISBN, etc.) on top of what the file carries.
     *
     * @param bookBytes         the fulfilled book content (EPUB or PDF)
     * @param suggestedFileName a base filename (used for the naming pattern / extension); e.g. "Some Title.epub"
     * @param libraryId         the target library
     * @param pathId            the target library path within that library
     * @param metadata          OverDrive catalog metadata to apply (title/authors/isbn13/thumbnailUrl); may be null
     * @param fileType          the book file type (EPUB or PDF)
     * @return the persisted {@link Book}
     */
    // Transactional so the persistence session stays open while we re-fetch and map the persisted book
    // (reloadCompleteBook → BookMapper touches lazily-loaded metadata.authors). With OSIV disabled
    // (spring.jpa.open-in-view: false) that mapping would otherwise throw LazyInitializationException
    // after the book was already written — and the catch below would then delete the imported file.
    @Transactional
    /**
     * Whether a library would keep a file of the given type. A library with no explicit allowed-formats
     * list accepts everything; otherwise only listed types survive — importing another type just gets it
     * purged on the next scan, so callers should route it elsewhere (e.g. Bookdrop) instead.
     */
    public boolean acceptsFormat(long libraryId, BookFileType fileType) {
        return libraryRepository.findById(libraryId)
                .map(lib -> {
                    List<BookFileType> allowed = lib.getAllowedFormats();
                    return allowed == null || allowed.isEmpty() || allowed.contains(fileType);
                })
                .orElse(true);
    }

    public Book importBook(byte[] bookBytes, String suggestedFileName, long libraryId, long pathId,
                           BookMetadata metadata, BookFileType fileType) {
        if (bookBytes == null || bookBytes.length == 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No book content to import");
        }

        LibraryEntity library = libraryRepository.findByIdWithPaths(libraryId)
                .orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(libraryId));

        LibraryPathEntity path = library.getLibraryPaths().stream()
                .filter(p -> p.getId().equals(pathId))
                .findFirst()
                .orElseThrow(() -> ApiError.INVALID_LIBRARY_PATH.createException(libraryId));

        BookMetadata namingMetadata = metadata != null ? metadata : BookMetadata.builder().build();
        String pattern = fileMovingHelper.getFileNamingPattern(library);
        Path target = fileMovingHelper.generateNewFilePath(path.getPath(), namingMetadata, pattern, suggestedFileName);
        File targetFile = target.toFile();

        if (targetFile.exists()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "A file already exists at the target location: " + targetFile.getName());
        }

        // Keep the watcher from importing the file we are about to write.
        monitoringRegistrationService.unregisterLibrary(libraryId);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bookBytes);
            log.info("OverDrive import: wrote {} bytes to {}", bookBytes.length, target);

            Book book = processFileInLibrary(targetFile.getName(), library, path, targetFile, fileType);
            applyOverDriveMetadata(book, metadata);
            log.info("OverDrive import: created book id={}", book.getId());

            // The import is done at this point (file written, book + metadata persisted). Re-fetching the
            // complete book and sending the live "book added" push is a best-effort nicety — its failure
            // must NOT abort the import and delete the just-written file (which would leave an orphaned
            // library row with no file). Without the push, the book simply appears on the next reload.
            try {
                Book completeBook = reloadCompleteBook(book);
                eventPublisher.publishEvent(new BookAddedEvent(completeBook));
                return completeBook;
            } catch (RuntimeException e) {
                log.warn("OverDrive import: book id={} imported, but the add-notification failed: {}",
                        book.getId(), e.getMessage());
                return book;
            }
        } catch (IOException e) {
            cleanupTargetFile(target);
            throw ApiError.GENERIC_BAD_REQUEST.createException("Failed to write imported book: " + e.getMessage());
        } catch (RuntimeException e) {
            cleanupTargetFile(target);
            throw e;
        } finally {
            reregister(libraryId);
        }
    }

    /**
     * Write a fulfilled OverDrive book into the Bookdrop folder for the operator to review and finalize,
     * used when no destination library/path was chosen. Writes to a temporary {@code .part} file first
     * (ignored by the Bookdrop watcher, which ingests only known book extensions) then atomically moves
     * it to its final name, so the watcher never sees a partially written file.
     */
    public void dropToBookdrop(byte[] bookBytes, String suggestedFileName) {
        if (bookBytes == null || bookBytes.length == 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No book content to import");
        }
        try {
            Path dropFolder = Path.of(appProperties.getBookdropFolder()).toAbsolutePath().normalize();
            Files.createDirectories(dropFolder);
            Path target = uniqueBookdropTarget(dropFolder, suggestedFileName);
            Path temp = Files.createTempFile(dropFolder, "overdrive-", ".part");
            Files.write(temp, bookBytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("OverDrive import: dropped {} bytes into Bookdrop at {}", bookBytes.length, target);
        } catch (IOException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Failed to write imported book to Bookdrop: " + e.getMessage());
        }
    }

    /** A non-colliding target path in the bookdrop folder for the given (already sanitized) file name. */
    private Path uniqueBookdropTarget(Path dropFolder, String fileName) {
        String safe = (fileName == null || fileName.isBlank()) ? "overdrive-book.epub" : fileName;
        Path target = dropFolder.resolve(safe);
        if (!Files.exists(target)) {
            return target;
        }
        String base = safe;
        String ext = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot);
        }
        for (int i = 2; ; i++) {
            Path candidate = dropFolder.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /** Re-fetch and map the fully-persisted book so the add-notification carries a complete payload. */
    private Book reloadCompleteBook(Book book) {
        // Fetch metadata.authors too — the mapper reads it, and this may run outside an active session.
        return bookRepository.findByIdWithBookFilesAndMetadata(book.getId())
                .map(entity -> bookMapper.toBookWithDescription(entity, false))
                .orElse(book);
    }

    /**
     * Layer the full OverDrive catalog metadata over the EPUB-extracted metadata: {@code REPLACE_WHEN_PROVIDED}
     * lets every field OverDrive supplies win, while a field OverDrive omits keeps its EPUB value (never
     * wiped). {@code updateThumbnail=false} means the <b>cover is never replaced</b> — the EPUB's embedded
     * cover always wins.
     */
    private void applyOverDriveMetadata(Book book, BookMetadata metadata) {
        if (metadata == null) {
            return;
        }
        BookEntity bookEntity = bookRepository.findByIdWithBookFiles(book.getId()).orElse(null);
        if (bookEntity == null) {
            return;
        }
        // Diagnostic: shows whether the OverDrive overlay actually differs from the EPUB-extracted
        // metadata, and whether the target fields are locked (locked fields are skipped by the updater).
        var existing = bookEntity.getMetadata();
        log.info("OverDrive metadata overlay for book id={}: title '{}' -> '{}' (locked={}), author -> {} (locked={}), isbn13 '{}' -> '{}', cover preserved",
                book.getId(),
                existing != null ? existing.getTitle() : null, metadata.getTitle(),
                existing != null ? existing.getTitleLocked() : null,
                metadata.getAuthors(),
                existing != null ? existing.getAuthorsLocked() : null,
                existing != null ? existing.getIsbn13() : null, metadata.getIsbn13());
        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(metadata)
                        .build())
                .updateThumbnail(false)
                .mergeCategories(false)
                .replaceMode(MetadataReplaceMode.REPLACE_WHEN_PROVIDED)
                .mergeMoods(true)
                .mergeTags(true)
                .build();
        metadataRefreshService.updateBookMetadata(context);
    }

    private Book processFileInLibrary(String fileName, LibraryEntity library, LibraryPathEntity path, File file, BookFileType fileType) {
        LibraryFile libraryFile = LibraryFile.builder()
                .libraryEntity(library)
                .libraryPathEntity(path)
                .fileSubPath(FileUtils.getRelativeSubPath(path.getPath(), file.toPath()))
                .bookFileType(fileType)
                .fileName(fileName)
                .build();

        BookFileProcessor processor = processorRegistry.getProcessorOrThrow(fileType);
        FileProcessResult result = processor.processFile(libraryFile);
        if (result == null || result.getBook() == null || result.getBook().getId() == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Book could not be created from the imported file");
        }
        return result.getBook();
    }

    private void reregister(long libraryId) {
        try {
            libraryRepository.findByIdWithPaths(libraryId).ifPresent(library ->
                    library.getLibraryPaths().forEach(p ->
                            monitoringRegistrationService.registerLibraryPaths(libraryId, Path.of(p.getPath()))));
        } catch (Exception e) {
            log.warn("OverDrive import: failed to re-register monitoring for library {}: {}", libraryId, e.getMessage());
        }
    }

    private void cleanupTargetFile(Path target) {
        try {
            if (Files.deleteIfExists(target)) {
                log.info("OverDrive import: cleaned up partially imported file {}", target);
            }
        } catch (IOException e) {
            log.warn("OverDrive import: failed to clean up file {}: {}", target, e.getMessage());
        }
    }
}
