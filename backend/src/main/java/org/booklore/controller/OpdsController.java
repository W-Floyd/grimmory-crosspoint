package org.booklore.controller;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.service.book.BookDownloadService;
import org.booklore.service.book.BookService;
import org.booklore.service.opds.OpdsBookService;
import org.booklore.service.opds.OpdsFeedService;
import org.booklore.service.opds.optimization.DevicePresetService;
import org.booklore.service.opds.optimization.OpdsCoverService;
import org.booklore.service.opds.optimization.OptimizedDownloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Tag(name = "OPDS", description = "Endpoints for OPDS catalog feeds, book downloads, covers, and search description")
@Slf4j
@RestController
@RequestMapping("/api/v1/opds")
@RequiredArgsConstructor
public class OpdsController {

    private static final String OPDS_CATALOG_MEDIA_TYPE = "application/atom+xml;profile=opds-catalog;kind=navigation;charset=utf-8";
    private static final String OPDS_ACQUISITION_MEDIA_TYPE = "application/atom+xml;profile=opds-catalog;kind=acquisition;charset=utf-8";

    private final OpdsFeedService opdsFeedService;
    private final BookService bookService;
    private final BookDownloadService bookDownloadService;
    private final OpdsBookService opdsBookService;
    private final AuthenticationService authenticationService;
    private final DevicePresetService devicePresetService;
    private final OptimizedDownloadService optimizedDownloadService;
    private final OpdsCoverService opdsCoverService;

    @Operation(summary = "Download book file", description = "Download the book file by its ID. Optionally specify a fileId to download a specific format.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Book file downloaded successfully"),
        @ApiResponse(responseCode = "404", description = "Book not found")
    })
    @GetMapping("/{bookId}/download")
    public ResponseEntity<StreamingResponseBody> downloadBook(
            @Parameter(description = "ID of the book to download") @PathVariable("bookId") Long bookId,
            @Parameter(description = "Optional ID of a specific file format to download") @RequestParam(required = false) Long fileId,
            @Parameter(description = "Optional device preset id (e.g. X3, X4) for on-the-fly EPUB optimization") @RequestParam(required = false) String preset) {
        opdsBookService.validateBookContentAccess(bookId, getOpdsUserId());
        String resolvedPreset = effectivePreset(preset);

        if (resolvedPreset != null && !resolvedPreset.isBlank()) {
            DevicePreset devicePreset = devicePresetService.resolve(resolvedPreset)
                    .orElseThrow(() -> ApiError.GENERIC_BAD_REQUEST.createException("Unknown device preset: " + resolvedPreset));
            // Optimization targets a specific EPUB file; without a fileId fall back to the primary file unchanged.
            if (fileId != null) {
                String presetId = devicePresetService.resolveId(resolvedPreset).orElse(resolvedPreset);
                return optimizedDownloadService.downloadOptimized(bookId, fileId, devicePreset, presetId);
            }
        }

        if (fileId != null) {
            return bookDownloadService.downloadBookFile(bookId, fileId);
        }
        return bookService.downloadBook(bookId);
    }

    @Operation(summary = "Get book cover image", description = "Retrieve the cover image for a book by its ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cover image returned successfully"),
        @ApiResponse(responseCode = "404", description = "Book or cover not found")
    })
    @GetMapping("/{bookId}/cover")
    public ResponseEntity<Resource> getBookCover(
            @Parameter(description = "ID of the book") @PathVariable long bookId,
            @Parameter(description = "Optional device preset id (e.g. X3, X4) to serve a constrained-device-friendly baseline JPEG cover") @RequestParam(required = false) String preset) {
        opdsBookService.validateBookContentAccess(bookId, getOpdsUserId());
        preset = effectivePreset(preset);
        Resource coverImage = bookService.getBookThumbnail(bookId);

        if (coverImage == null) {
            // Fall back to audiobook if possible
            coverImage = bookService.getAudiobookCover(bookId);
        }

        if (coverImage == null) {
            return ResponseEntity.notFound().build();
        }

        // For a known preset, re-encode into a baseline JPEG that constrained e-ink decoders
        // (e.g. picojpeg) can display. Unknown presets are ignored here (serve original) so a
        // stray value never breaks cover rendering while browsing.
        if (preset != null && !preset.isBlank()) {
            Resource original = coverImage;
            coverImage = devicePresetService.resolve(preset)
                    .flatMap(p -> opdsCoverService.reencode(original, p))
                    .orElse(original);
        }

        String contentType = "image/jpeg";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + coverImage.getFilename() + "\"")
                .body(coverImage);
    }

    @Operation(summary = "Get OPDS root catalog", description = "Retrieve the OPDS root navigation feed.")
    @ApiResponse(responseCode = "200", description = "Root OPDS catalog returned successfully")
    @GetMapping(produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getRootCatalog(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateRootNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS libraries navigation", description = "Retrieve the OPDS libraries navigation feed.")
    @ApiResponse(responseCode = "200", description = "Libraries navigation feed returned successfully")
    @GetMapping(value = "/libraries", produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getLibrariesNavigation(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateLibrariesNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS shelves navigation", description = "Retrieve the OPDS shelves navigation feed.")
    @ApiResponse(responseCode = "200", description = "Shelves navigation feed returned successfully")
    @GetMapping(value = "/shelves", produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getShelvesNavigation(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateShelvesNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS magic shelves navigation", description = "Retrieve the OPDS magic shelves navigation feed.")
    @ApiResponse(responseCode = "200", description = "Magic shelves navigation feed returned successfully")
    @GetMapping(value = "/magic-shelves", produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getMagicShelvesNavigation(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateMagicShelvesNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS authors navigation", description = "Retrieve the OPDS authors navigation feed.")
    @ApiResponse(responseCode = "200", description = "Authors navigation feed returned successfully")
    @GetMapping(value = "/authors", produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getAuthorsNavigation(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateAuthorsNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS series navigation", description = "Retrieve the OPDS series navigation feed.")
    @ApiResponse(responseCode = "200", description = "Series navigation feed returned successfully")
    @GetMapping(value = "/series", produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getSeriesNavigation(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateSeriesNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS devices navigation", description = "Retrieve the OPDS navigation feed of configured device-optimization presets.")
    @ApiResponse(responseCode = "200", description = "Devices navigation feed returned successfully")
    @GetMapping(value = "/devices", produces = OPDS_CATALOG_MEDIA_TYPE)
    public ResponseEntity<String> getDevicesNavigation(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateDevicesNavigation(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_CATALOG_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS catalog feed", description = "Retrieve the OPDS acquisition catalog feed.")
    @ApiResponse(responseCode = "200", description = "Catalog feed returned successfully")
    @GetMapping(value = "/catalog", produces = OPDS_ACQUISITION_MEDIA_TYPE)
    public ResponseEntity<byte[]> getCatalog(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateCatalogFeed(request);
        byte[] payload = feed.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_ACQUISITION_MEDIA_TYPE))
                .body(payload);
    }

    @Operation(summary = "Get recent books feed", description = "Retrieve the OPDS feed for recently added books.")
    @ApiResponse(responseCode = "200", description = "Recent books feed returned successfully")
    @GetMapping(value = "/recent", produces = OPDS_ACQUISITION_MEDIA_TYPE)
    public ResponseEntity<byte[]> getRecentBooks(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateRecentFeed(request);
        byte[] payload = feed.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_ACQUISITION_MEDIA_TYPE))
                .body(payload);
    }

    @Operation(summary = "Get continue reading feed", description = "Retrieve the OPDS feed for in-progress books, most recently read first.")
    @ApiResponse(responseCode = "200", description = "Continue reading feed returned successfully")
    @GetMapping(value = "/continue-reading", produces = OPDS_ACQUISITION_MEDIA_TYPE)
    public ResponseEntity<byte[]> getContinueReadingFeed(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateContinueReadingFeed(request);
        byte[] payload = feed.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_ACQUISITION_MEDIA_TYPE))
                .body(payload);
    }

    @Operation(summary = "Get surprise feed", description = "Retrieve the OPDS feed for surprise/random books.")
    @ApiResponse(responseCode = "200", description = "Surprise feed returned successfully")
    @GetMapping(value = "/surprise", produces = OPDS_ACQUISITION_MEDIA_TYPE)
    public ResponseEntity<String> getSurpriseFeed(@Parameter(hidden = true) HttpServletRequest request) {
        String feed = opdsFeedService.generateSurpriseFeed(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(OPDS_ACQUISITION_MEDIA_TYPE))
                .body(feed);
    }

    @Operation(summary = "Get OPDS search description", description = "Retrieve the OpenSearch description document for OPDS search.")
    @ApiResponse(responseCode = "200", description = "OpenSearch description returned successfully")
    @GetMapping(value = "/search.opds", produces = {
            "application/opensearchdescription+xml",
            "application/atom+xml",
            "application/xml",
            "text/xml"
    })
    public ResponseEntity<String> getSearchDescription() {
        String searchDoc = opdsFeedService.getOpenSearchDescription();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/opensearchdescription+xml;charset=utf-8"))
                .body(searchDoc);
    }

    private Long getOpdsUserId() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        return details != null && details.getOpdsUserV2() != null
                ? details.getOpdsUserV2().getUserId()
                : null;
    }

    /**
     * Resolve the preset to apply to a direct download/cover request: an explicit {@code preset}
     * param wins, otherwise fall back to the authenticated OPDS user's default preset. Feed links
     * already carry an explicit param, so this only affects clients hitting these endpoints directly.
     */
    private String effectivePreset(String preset) {
        if (preset != null && !preset.isBlank()) {
            return preset;
        }
        OpdsUserDetails details = authenticationService.getOpdsUser();
        return details != null && details.getOpdsUserV2() != null
                ? details.getOpdsUserV2().getDefaultPreset()
                : null;
    }
}
