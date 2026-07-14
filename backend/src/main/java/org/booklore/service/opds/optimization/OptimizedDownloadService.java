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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private Path ensureCached(Path source, Long bookId, Long fileId, DevicePreset preset,
                              String presetId, BookFileEntity bookFile) throws Exception {
        String hash = bookFile.getCurrentHash() != null ? bookFile.getCurrentHash()
                : bookFile.getInitialHash() != null ? bookFile.getInitialHash() : "nohash";

        Path cacheDir = Path.of(fileService.getOpdsCachePath(), sanitize(presetId));
        Files.createDirectories(cacheDir);
        Path cached = cacheDir.resolve(bookId + "_" + fileId + "_" + sanitize(hash) + ".epub");

        if (Files.exists(cached) && Files.size(cached) > 0) {
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
}
