package org.booklore.service.opds.optimization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.appsettings.AppSettingService;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Serves EPUB variants for the OPDS server. Depending on request/settings an EPUB may be
 * device-optimized for a preset and/or have its embedded cover replaced by BookLore's metadata
 * cover (when {@code opds_replace_cover} is enabled). Variants are cached on disk keyed by
 * (variant, book, file, content hash, and — when the cover is replaced — the cover's hash);
 * anything that is not an optimizable/rewritable EPUB (or exceeds the configured size cap, or
 * fails) falls back to the original file via {@link BookDownloadService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizedDownloadService {

    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[^a-zA-Z0-9._-]");
    /** Cache dir + variant-hash key used for cover-replaced (but not preset-optimized) EPUBs. */
    public static final String COVER_VARIANT = "cover";
    private static final String NO_HASH = "nohash";

    private final BookFileRepository bookFileRepository;
    private final BookDownloadService bookDownloadService;
    private final EpubDeviceOptimizer epubDeviceOptimizer;
    private final EpubCoverReplacer epubCoverReplacer;
    private final DevicePresetService devicePresetService;
    private final FileService fileService;
    private final OpdsVariantHashService variantHashService;
    private final AppSettingService appSettingService;

    /** Variant keys currently being warmed, so concurrent feed loads don't optimize the same file twice. */
    private final Set<String> inFlightPrewarms = ConcurrentHashMap.newKeySet();

    /**
     * Builds hold a whole unzipped EPUB in memory and run on the shared async executor, and one feed
     * page queues one per uncached book. A prewarm that finds no permit is skipped; nothing was cached,
     * so the next feed load asks again.
     */
    private final Semaphore prewarmPermits = new Semaphore(2);

    /**
     * Serve {@code fileId} for OPDS. When {@code preset} is non-null the EPUB is device-optimized;
     * when {@code opds_replace_cover} is enabled its embedded cover is replaced with the metadata
     * cover. Both transforms can apply together (cover replaced first, then optimized). Non-EPUB
     * files, oversized files, downloads that need no transform, and any failure transparently serve
     * the original bytes.
     */
    public ResponseEntity<StreamingResponseBody> downloadForOpds(Long bookId, Long fileId,
                                                                 DevicePreset preset, String presetId) {
        BookFileEntity bookFile = bookFileRepository.findByIdWithBookAndLibraryPath(fileId)
                .orElseThrow(() -> ApiError.FILE_NOT_FOUND.createException(fileId));

        if (!bookFile.getBook().getId().equals(bookId)) {
            throw ApiError.FILE_NOT_FOUND.createException(fileId);
        }

        // Only EPUBs are transformed; everything else streams unchanged.
        if (bookFile.getBookType() != BookFileType.EPUB) {
            return bookDownloadService.downloadBookFile(bookId, fileId);
        }

        boolean optimize = preset != null;
        boolean replaceCover = willReplaceCover(bookId);
        if (!optimize && !replaceCover) {
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

            Path cached = ensureVariant(source, bookId, fileId, preset, presetId, replaceCover, bookFile);
            return streamEpub(cached, bookFile.getFileName());
        } catch (Exception e) {
            log.warn("Failed to build EPUB variant for file {} (preset {}, replaceCover {}); serving original: {}",
                    fileId, presetId, replaceCover, e.getMessage());
            return bookDownloadService.downloadBookFile(bookId, fileId);
        }
    }

    /** Whether the metadata cover will be embedded for this book: setting enabled and a cover file exists. */
    public boolean willReplaceCover(Long bookId) {
        if (!appSettingService.getAppSettings().isOpdsReplaceCover()) {
            return false;
        }
        try {
            Path cover = Path.of(fileService.getCoverFile(bookId));
            return Files.exists(cover) && Files.size(cover) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Real byte size of the already-cached variant for {@code (bookId, fileId, preset)}, or empty when
     * the current one has not been built yet. A non-generating lookup of the exact name a download
     * would use, so a variant built from an older source, cover or preset setting never counts; use
     * {@link #prewarm} to populate the cache off the request thread.
     *
     * <p>{@code presetId} must be the canonical (configured) preset id; {@code preset} null means the
     * cover-only variant.
     */
    public OptionalLong cachedVariantSize(Long bookId, Long fileId, DevicePreset preset, String presetId) {
        if (bookId == null || fileId == null) {
            return OptionalLong.empty();
        }
        try {
            String hash = variantHashService.sourceHash(fileId).orElse(NO_HASH);
            Path coverFile = willReplaceCover(bookId) ? Path.of(fileService.getCoverFile(bookId)) : null;
            Path cached = variantName(bookId, fileId, hash, preset, presetId, coverFile).path();
            long size = Files.isRegularFile(cached) ? Files.size(cached) : 0;
            return size > 0 ? OptionalLong.of(size) : OptionalLong.empty();
        } catch (IOException e) {
            log.debug("Failed to inspect OPDS cache for book {} file {} preset {}: {}",
                    bookId, fileId, presetId, e.getMessage());
            return OptionalLong.empty();
        }
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
        if (bookId == null || fileId == null) {
            return;
        }
        String variant = presetId != null ? presetId : COVER_VARIANT;
        String key = variant + "/" + bookId + "_" + fileId;
        if (!inFlightPrewarms.add(key)) {
            return; // an identical variant is already being warmed
        }
        try {
            if (!prewarmPermits.tryAcquire()) {
                return;
            }
            try {
                servedSize(bookId, fileId, preset, presetId);
            } finally {
                prewarmPermits.release();
            }
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
        if (bookId == null || fileId == null) {
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
            boolean optimize = preset != null;
            boolean replaceCover = willReplaceCover(bookId);
            // Anything served unchanged (non-EPUB, oversized, or no transform requested) has the
            // original size; otherwise build the variant and measure it.
            if (bookFile.getBookType() != BookFileType.EPUB
                    || sourceSize > devicePresetService.maxSourceFileSizeBytes()
                    || (!optimize && !replaceCover)) {
                return OptionalLong.of(sourceSize);
            }
            Path cached = ensureVariant(source, bookId, fileId, preset, presetId, replaceCover, bookFile);
            return OptionalLong.of(Files.size(cached));
        } catch (Exception e) {
            log.debug("Could not determine served size for book {} file {} preset {}: {}",
                    bookId, fileId, presetId, e.getMessage());
            return OptionalLong.empty();
        }
    }

    /**
     * Build (or reuse from cache) the requested EPUB variant. {@code preset != null} optimizes;
     * {@code replaceCover} embeds the metadata cover first. The cache key is
     * {@code {variant}/{bookId}_{fileId}_{sourceHash}[_c{coverHash}].epub}, so a changed source
     * file or cover invalidates the cached copy.
     */
    private Path ensureVariant(Path source, Long bookId, Long fileId, DevicePreset preset, String presetId,
                               boolean replaceCover, BookFileEntity bookFile) throws Exception {
        boolean optimize = preset != null;
        String hash = bookFile.getCurrentHash() != null ? bookFile.getCurrentHash()
                : bookFile.getInitialHash() != null ? bookFile.getInitialHash() : NO_HASH;
        Path coverFile = replaceCover ? Path.of(fileService.getCoverFile(bookId)) : null;
        VariantName name = variantName(bookId, fileId, hash, preset, presetId, coverFile);
        String variantKey = name.variantKey();
        Path cacheDir = name.path().getParent();
        Files.createDirectories(cacheDir);
        Path cached = name.path();

        if (Files.exists(cached) && Files.size(cached) > 0) {
            // Registration is idempotent and skips work when already up to date, so it is safe
            // to (re)assert the mapping on a cache hit too (e.g. after a DB reset).
            variantHashService.register(bookFile, variantKey, hash, cached);
            return cached;
        }

        Path coverTemp = null;
        Path optTemp = null;
        try {
            Path working = source;
            if (replaceCover) {
                coverTemp = Files.createTempFile(cacheDir, "cov-", ".epub.tmp");
                if (epubCoverReplacer.replaceCover(source, Files.readAllBytes(coverFile), fileExtension(coverFile), coverTemp)) {
                    working = coverTemp;
                } else {
                    Files.deleteIfExists(coverTemp);
                    coverTemp = null; // replacement not applied; optimize/serve the source as-is
                }
            }

            Path result;
            if (optimize) {
                optTemp = Files.createTempFile(cacheDir, "opt-", ".epub.tmp");
                epubDeviceOptimizer.optimize(working, preset, optTemp);
                result = optTemp;
            } else {
                result = working;
            }

            if (result.equals(source)) {
                // No transform actually happened (e.g. cover replacement failed, no preset); cache a
                // copy so subsequent requests hit the cache instead of retrying. Copied beside the
                // target first: a concurrent request treats any non-empty file as complete.
                optTemp = Files.createTempFile(cacheDir, "copy-", ".epub.tmp");
                Files.copy(source, optTemp, StandardCopyOption.REPLACE_EXISTING);
                result = optTemp;
            }
            try {
                Files.move(result, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailed) {
                Files.move(result, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            // Map this variant's partial-MD5 to the book so KOReader sync can match the copy the
            // reader downloaded (its bytes differ from the library original).
            variantHashService.register(bookFile, variantKey, hash, cached);
            evictSuperseded(cached, bookId, fileId);
            return cached;
        } finally {
            if (coverTemp != null) Files.deleteIfExists(coverTemp);
            if (optTemp != null) Files.deleteIfExists(optTemp);
        }
    }

    /**
     * Where a variant is cached and the key its KOReader hash is registered under. Both carry every
     * input that changes the bytes — source hash, preset settings, cover version — so a change to any
     * of them is a new variant rather than a stale hit, and a reader's already-downloaded copy keeps
     * resolving under its own key.
     */
    private VariantName variantName(Long bookId, Long fileId, String hash, DevicePreset preset, String presetId,
                                    Path coverFile) throws IOException {
        boolean optimize = preset != null;
        String variant = optimize ? presetId : COVER_VARIANT;
        String presetSegment = optimize ? "_p" + presetSettingsTag(preset) : "";
        String coverSegment = coverFile != null ? "_c" + sanitize(coverToken(coverFile)) : "";
        Path cacheDir = Path.of(fileService.getOpdsCachePath(), sanitize(variant));
        String fileName = bookId + "_" + fileId + "_" + sanitize(hash) + presetSegment + coverSegment + ".epub";
        return new VariantName(cacheDir.resolve(fileName), variant + presetSegment + coverSegment);
    }

    private record VariantName(Path path, String variantKey) {}

    /** The preset fields the optimizer reads, so editing a preset invalidates what it built. */
    private static String presetSettingsTag(DevicePreset preset) {
        return String.format("%08x", Objects.hash(preset.getMaxWidth(), preset.getMaxHeight(),
                preset.getJpegQuality(), preset.isGrayscale(), preset.isAutoCrop()));
    }

    /**
     * Drop this file's other cached variants in the same directory: an older source hash, preset
     * setting or cover version that nothing will request again. Their KOReader hash rows stay, so
     * copies readers already downloaded still sync.
     */
    private void evictSuperseded(Path current, Long bookId, Long fileId) {
        String prefix = bookId + "_" + fileId + "_";
        try (var siblings = Files.list(current.getParent())) {
            siblings.filter(p -> !p.equals(current))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(".epub");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.debug("Could not evict superseded OPDS variant {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not list OPDS cache dir {}: {}", current.getParent(), e.getMessage());
        }
    }

    /** Cheap cache-busting token for the metadata cover: its size + last-modified time. */
    private static String coverToken(Path coverFile) throws IOException {
        return Files.size(coverFile) + "-" + Files.getLastModifiedTime(coverFile).toMillis();
    }

    private static String fileExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
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
