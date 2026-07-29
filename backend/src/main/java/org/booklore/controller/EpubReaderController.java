package org.booklore.controller;

import org.booklore.config.security.annotation.CheckBookAccess;
import org.booklore.model.dto.response.EpubBookInfo;
import org.booklore.service.ByteRangeSource;
import org.booklore.service.FileStreamingService;
import org.booklore.service.reader.EpubReaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/v1/epub")
@RequiredArgsConstructor
@Tag(name = "EPUB Reader", description = "Endpoints for reading EPUB format books with streaming support")
public class EpubReaderController {

    /**
     * EPUB resources are immutable for the lifetime of the book file, and the ETag forces
     * revalidation once this expires — so repeated reads of the same chapter audio stay local.
     */
    private static final String EPUB_RESOURCE_CACHE_CONTROL = "private, max-age=3600";

    private final EpubReaderService epubReaderService;
    private final FileStreamingService fileStreamingService;

    @Operation(summary = "Get EPUB book info",
            description = "Retrieve parsed metadata, spine, manifest, and TOC for an EPUB book.")
    @ApiResponse(responseCode = "200", description = "Book info returned successfully")
    @CheckBookAccess(bookIdParam = "bookId")
    @GetMapping("/{bookId}/info")
    public ResponseEntity<EpubBookInfo> getBookInfo(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @Parameter(description = "Optional book type for alternative format (e.g., EPUB)") @RequestParam(required = false) String bookType,
            @Parameter(description = "Optional exact book-file id; disambiguates two files of the same format") @RequestParam(required = false) Long fileId) {
        return ResponseEntity.ok(epubReaderService.getBookInfo(bookId, bookType, fileId));
    }

    @Operation(summary = "Get file from EPUB", description = "Retrieve a specific file from within the EPUB archive (HTML, CSS, images, fonts, etc.).")
    @ApiResponse(responseCode = "200", description = "File content returned successfully")
    @CheckBookAccess(bookIdParam = "bookId")
    @GetMapping("/{bookId}/file/{*filePath}")
    public void getFile(
            @Parameter(description = "ID of the book") @PathVariable Long bookId,
            @PathVariable String filePath,
            @Parameter(description = "Optional book type for alternative format (e.g., EPUB)") @RequestParam(required = false) String bookType,
            @Parameter(description = "Optional exact book-file id; disambiguates two files of the same format") @RequestParam(required = false) Long fileId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        String cleanPath = filePath.startsWith("/") ? filePath.substring(1) : filePath;
        cleanPath = URLDecoder.decode(cleanPath, StandardCharsets.UTF_8);

        String contentType = epubReaderService.getContentType(bookId, bookType, fileId, cleanPath);
        response.setContentType(contentType);

        if (contentType.startsWith("font/") ||
                "application/font-woff".equals(contentType) ||
                "application/font-woff2".equals(contentType) ||
                "application/vnd.ms-fontobject".equals(contentType)) {
            response.setHeader("Access-Control-Allow-Origin", "*");
        }
        // Defense in depth for untrusted EPUB resources. See Foliate's security guidance:
        // https://github.com/johnfactotum/foliate-js#security
        response.setHeader("Content-Security-Policy", "script-src 'none'");

        // Range support matters for embedded media overlay audio: without it the reader has to
        // buffer a whole chapter's audio before playback starts and cannot seek within it.
        try {
            ByteRangeSource source = epubReaderService.openRangeSource(bookId, bookType, fileId, cleanPath);
            fileStreamingService.streamWithRangeSupport(
                    source, contentType, EPUB_RESOURCE_CACHE_CONTROL, cleanPath, request, response);
        } catch (FileNotFoundException e) {
            if (response.isCommitted()) {
                // Missing entry discovered mid-transfer: the status is already on the wire,
                // so the truncated body is all the client can be told.
                log.warn("EPUB entry vanished while streaming: {}", cleanPath, e);
                return;
            }
            response.reset();
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
