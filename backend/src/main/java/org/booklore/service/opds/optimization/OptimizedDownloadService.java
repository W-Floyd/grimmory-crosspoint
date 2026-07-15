package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.book.BookDownloadService;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves device-optimized EPUB files for the OPDS server. Optimized copies are cached on
 * disk keyed by (preset, book, file, content hash); anything that is not an optimizable
 * EPUB (or exceeds the configured size cap, or fails to optimize) falls back to the
 * original file via {@link BookDownloadService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizedDownloadService {

    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[^a-zA-Z0-9._-]");

    private final BookFileRepository bookFileRepository;
    private final BookDownloadService bookDownloadService;
    private final EpubDeviceOptimizer epubDeviceOptimizer;
    private final DevicePresetService devicePresetService;
    private final FileService fileService;
    private final OpdsVariantHashService variantHashService;

    /** Variant keys currently being warmed, so concurrent feed loads don't optimize the same file twice. */
    private final Set<String> inFlightPrewarms = ConcurrentHashMap.newKeySet();

    /**
     * Download {@code fileId} optimized for {@code preset}. Non-EPUB files, oversized files,
     * and optimization failures transparently serve the original bytes.
     */
    public ResponseEntity<StreamingResponseBody> downloadOptimized(Long bookId, Long fileId,
                                                                   DevicePreset preset, String presetId) {
        BookFileEntity bookFile = bookFileRepository.findByIdWithBookAndLibraryPath(fileId)
                .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException(fileId));

        if (!bookFile.getBook().getId().equals(bookId)) {
            throw ApiError.FILE_NOT_FOUND.createException(fileId);
        }

        // Only EPUBs are optimized; everything else streams unchanged.
        if (bookFile.getBookType() != BookFileType.EPUB) {
            return bookDownloadService.downloadBookFile(bookId, fileId);
        }

        Path libraryRoot = Path.of(bookFile.getBook().getLibraryPath().getPath());
        Path source = FileUtils.requirePathWithinBase(bookFile.getFullFilePath(), libraryRoot);
        if (!Files.exists(source)) {
            throw ApiError.FAILED_TO_DOWNLOAD_FILE.createException(fileId);
        }

        try {
            long size = Files.size(source);
            if (size > devicePresetService.maxSourceFileSizeBytes()) {
                log.info("EPUB {} ({} bytes) exceeds OPDS optimize size cap; serving original", fileId, size);
                return bookDownloadService.downloadBookFile(bookId, fileId);
            }

            Path cached = ensureCached(source, bookId, fileId, preset, presetId, bookFile);
            return streamEpub(cached, bookFile.getFileName());
        } catch (Exception e) {
            log.warn("Failed to optimize EPUB {} for preset {}; serving original: {}", fileId, presetId, e.getMessage());
            return bookDownloadService.downloadBookFile(bookId, fileId);
        }
    }

    /**
     * Real byte size of the already-cached optimized variant for {@code (bookId, fileId, presetId)},
     * or empty when none has been generated yet. A fast, non-generating disk lookup safe to call
     * during feed rendering; use {@link #prewarm} to populate the cache off the request thread.
     *
     * <p>{@code presetId} must be the canonical (configured) preset id so the cache key matches
     * the one used by {@link #downloadOptimized}.
     */
    public OptionalLong cachedVariantSize(Long bookId, Long fileId, String presetId) {
        if (bookId == null || fileId == null || presetId == null || presetId.isBlank()) {
            return OptionalLong.empty();
        }
        Path cacheDir = Path.of(fileService.getOpdsCachePath(), sanitize(presetId));
        if (!Files.isDirectory(cacheDir)) {
            return OptionalLong.empty();
        }
        String prefix = bookId + "_" + fileId + "_";
        try (var stream = Files.list(cacheDir)) {
            Optional<Path> newest = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".epub");
                    })
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(OptimizedDownloadService::lastModifiedMillis));
            if (newest.isPresent()) {
                long size = Files.size(newest.get());
                if (size > 0) {
                    return OptionalLong.of(size);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to inspect OPDS cache for book {} file {} preset {}: {}",
                    bookId, fileId, presetId, e.getMessage());
        }
        return OptionalLong.empty();
    }

    /**
     * Generate and cache the optimized EPUB variant for {@code (bookId, fileId, presetId)} off the
     * request thread so its real size can be advertised on a later feed load. Fire-and-forget:
     * de-duplicates concurrent requests for the same variant and swallows all failures (they are
     * already handled by the download fallback).
     *
     * <p>{@code presetId} must be the canonical (configured) preset id.
     */
    @Async("taskExecutor")
    public void prewarm(Long bookId, Long fileId, DevicePreset preset, String presetId) {
        if (bookId == null || fileId == null || preset == null || presetId == null) {
            return;
        }
        String key = presetId + "/" + bookId + "_" + fileId;
        if (!inFlightPrewarms.add(key)) {
            return; // an identical variant is already being warmed
        }
        try {
            servedSize(bookId, fileId, preset, presetId);
        } finally {
            inFlightPrewarms.remove(key);
        }
    }

    /**
     * Size in bytes of the file the optimized-download endpoint would serve for
     * {@code (bookId, fileId, presetId)}, generating the optimized EPUB variant when it does not
     * exist yet. Returns the original file size when the target is not an optimizable EPUB or
     * exceeds the configured size cap (both are served unchanged), and empty when the size cannot
     * be determined.
     *
     * <p>Runs the optimization synchronously; call it from {@link #prewarm} rather than the
     * request thread. {@code presetId} must be the canonical (configured) preset id.
     */
    public OptionalLong servedSize(Long bookId, Long fileId, DevicePreset preset, String presetId) {
        if (bookId == null || fileId == null || preset == null) {
            return OptionalLong.empty();
        }
        BookFileEntity bookFile = bookFileRepository.findByIdWithBookAndLibraryPath(fileId).orElse(null);
        if (bookFile == null || bookFile.getBook() == null || !bookFile.getBook().getId().equals(bookId)) {
            return OptionalLong.empty();
        }
        try {
            Path libraryRoot = Path.of(bookFile.getBook().getLibraryPath().getPath());
            Path source = FileUtils.requirePathWithinBase(bookFile.getFullFilePath(), libraryRoot);
            if (!Files.exists(source)) {
                return OptionalLong.empty();
            }
            long sourceSize = Files.size(source);
            // Non-EPUBs and oversized files are streamed unchanged, so the served size is the original.
            if (bookFile.getBookType() != BookFileType.EPUB
                    || sourceSize > devicePresetService.maxSourceFileSizeBytes()) {
                return OptionalLong.of(sourceSize);
            }
            Path cached = ensureCached(source, bookId, fileId, preset, presetId, bookFile);
            return OptionalLong.of(Files.size(cached));
        } catch (Exception e) {
            log.debug("Could not determine optimized size for book {} file {} preset {}: {}",
                    bookId, fileId, presetId, e.getMessage());
            return OptionalLong.empty();
        }
    }

    private Path ensureCached(Path source, Long bookId, Long fileId, DevicePreset preset,
                              String presetId, BookFileEntity bookFile) throws Exception {
        String hash = bookFile.getCurrentHash() != null ? bookFile.getCurrentHash()
                : bookFile.getInitialHash() != null ? bookFile.getInitialHash() : "nohash";

        Path cacheDir = Path.of(fileService.getOpdsCachePath(), sanitize(presetId));
        Files.createDirectories(cacheDir);
        Path cached = cacheDir.resolve(bookId + "_" + fileId + "_" + sanitize(hash) + ".epub");

        if (Files.exists(cached) && Files.size(cached) > 0) {
            // Registration is idempotent and skips work when already up to date, so it is safe
            // to (re)assert the mapping on a cache hit too (e.g. after a DB reset).
            variantHashService.register(bookFile, presetId, hash, cached);
            return cached;
        }

        Path temp = Files.createTempFile(cacheDir, "opt-", ".epub.tmp");
        try {
            epubDeviceOptimizer.optimize(source, preset, temp);
            try {
                Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailed) {
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            // Map this variant's partial-MD5 to the book so KOReader sync can match the
            // optimized copy the reader downloaded (its bytes differ from the original).
            variantHashService.register(bookFile, presetId, hash, cached);
            return cached;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private ResponseEntity<StreamingResponseBody> streamEpub(Path file, String fileName) throws Exception {
        long length = Files.size(file);
        StreamingResponseBody body = outputStream -> {
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/epub+zip"))
                .contentLength(length)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName))
                .body(body);
    }

    private String contentDisposition(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "book.epub" : fileName;
        return ContentDisposition.builder("attachment")
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private static String sanitize(String value) {
        return UNSAFE_FILENAME.matcher(value).replaceAll("_");
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
