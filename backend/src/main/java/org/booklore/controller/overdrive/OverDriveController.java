package org.booklore.controller.overdrive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.APIException;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.overdrive.*;
import org.booklore.model.dto.settings.OverdriveProperties;
import org.booklore.service.acsm.AcsmHandler;
import org.booklore.service.acsm.AcsmHandlerConfig;
import org.booklore.service.audiobook.AudiobookHandler;
import org.booklore.service.metadata.parser.OverDriveItemExtractor;
import org.booklore.service.overdrive.OverDriveService;
import org.booklore.util.FileUtils;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.*;

/**
 * REST endpoints for OverDrive/Libby integration.
 * Manages the chip token lifecycle, loans, holds, and catalog search.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/overdrive")
@Tag(name = "OverDrive Integration", description = "Endpoints for managing OverDrive/Libby library loans and holds")
public class OverDriveController {

    private final OverDriveService overDriveService;
    private final AcsmHandler acsmHandler;
    private final AcsmHandlerConfig acsmHandlerConfig;
    private final AudiobookHandler audiobookHandler;
    private final OverdriveProperties overdriveProperties;

    /** Feature switch: reject when the operator has not enabled the OverDrive integration. */
    private void requireEnabled() {
        if (!overdriveProperties.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "OverDrive integration is disabled. Set app.overdrive.enabled=true to enable it.");
        }
    }

        // ── Auth / Chip ────────────────────────────────────────────────────

     /**
      * POST /api/overdrive/chip — obtain a new chip identity.
      * Returns a chipId that must be exchanged via a setup code.
      */
     @Operation(summary = "Obtain OverDrive chip identity",
                description = "POST /chip on the OverDrive sentry endpoint to get an initial identity token.")
     @ApiResponse(responseCode = "200", description = "Chip identity obtained successfully")
     @PostMapping("/chip")
    public ResponseEntity<OverDriveChipResult> chip() {
        requireEnabled();
        OverDriveService.ChipResult chip = overDriveService.requestChip();
        return ResponseEntity.ok(new OverDriveChipResult(chip.identity(), chip.token()));
     }

     /**
      * POST /api/overdrive/setup-code — link a Libby account (and all its cards) from an 8-digit setup
      * code. Get the code in Libby under Settings → "Copy to another device". A user may redeem several
      * codes to link multiple accounts. Returns the cards linked by this code.
      */
     @Operation(summary = "Register a Libby setup code",
                description = "Redeems a Libby 8-digit setup code and links all cards on that account for the current user.")
     @ApiResponse(responseCode = "200", description = "Setup code registered; linked cards returned")
     @ApiResponse(responseCode = "400", description = "Invalid setup code")
     @PostMapping("/setup-code")
    public ResponseEntity<List<OverDriveCard>> redeemSetupCode(
            @Parameter(description = "Libby 8-digit setup code") @RequestBody OverDriveSetupCodeRequest request
    ) {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.redeemSetupCode(request.getCode()));
    }

    /**
     * POST /api/overdrive/link-token — link by pasting a Libby identity token from a signed-in browser.
     * That token is the account's primary chip, so it can fulfill Adobe-DRM titles.
     */
    @Operation(summary = "Link by pasting a Libby identity token",
               description = "Links the cards on a Libby identity token copied from a signed-in browser (localStorage …:sentry.identity, or an Authorization: Bearer value). Primary chip → can fulfill Adobe-DRM titles.")
    @ApiResponse(responseCode = "200", description = "Token accepted; linked cards returned")
    @ApiResponse(responseCode = "400", description = "Token missing, expired, or linked no cards")
    @PostMapping("/link-token")
    public ResponseEntity<List<OverDriveCard>> linkToken(
            @RequestBody Map<String, String> body
    ) {
        requireEnabled();
        String token = body != null ? body.get("token") : null;
        if (token == null || token.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("token is required");
        }
        try {
            return ResponseEntity.ok(overDriveService.linkToken(token));
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OverDrive token link failed: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    e.getMessage() != null ? e.getMessage() : "OverDrive token link failed");
        }
    }

    /**
     * POST /api/overdrive/link-card — link a library card directly by number + PIN. Produces a
     * fulfillment-capable primary chip (unlike a setup code, which yields a browse-only secondary).
     */
    @Operation(summary = "Link a library card by number + PIN",
               description = "Links a card via the library's local sign-in (card number + PIN), yielding a primary chip that can fulfill Adobe-DRM titles. Optionally stores the credentials (encrypted) for silent re-link when the token expires.")
    @ApiResponse(responseCode = "200", description = "Card linked; linked cards returned")
    @ApiResponse(responseCode = "400", description = "Invalid credentials or unsupported library")
    @PostMapping("/link-card")
    public ResponseEntity<List<OverDriveCard>> linkCard(
            @RequestBody OverDriveLinkCardRequest request
    ) {
        requireEnabled();
        if (request == null || request.getLibraryKey() == null || request.getLibraryKey().isBlank()
                || request.getCardNumber() == null || request.getCardNumber().isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("libraryKey and cardNumber are required");
        }
        try {
            return ResponseEntity.ok(overDriveService.linkCard(
                    request.getLibraryKey(), request.getCardNumber(), request.getPin()));
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OverDrive card link failed: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    e.getMessage() != null ? e.getMessage() : "OverDrive card link failed");
        }
    }

    /**
     * GET /api/overdrive/cards — the current user's linked library cards.
     */
    @Operation(summary = "List the current user's linked Libby cards",
               description = "Returns every library card the user has linked across their setup codes.")
    @ApiResponse(responseCode = "200", description = "Cards listed successfully")
    @GetMapping("/cards")
    public ResponseEntity<List<OverDriveCard>> listCards() {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.listCards());
    }

    /**
     * PUT /api/overdrive/{identity}/default-library — remember a card's default destination library +
     * path for borrow &amp; import. Omit both params to clear it (imports then fall back to Bookdrop).
     */
    @Operation(summary = "Set a card's default import library",
               description = "Stores the default destination library + path for the card; omit both to clear.")
    @ApiResponse(responseCode = "204", description = "Default library saved")
    @PutMapping("/{identity}/default-library")
    public ResponseEntity<Void> setDefaultLibrary(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @RequestParam(required = false) Long libraryId,
            @RequestParam(required = false) Long pathId) {
        requireEnabled();
        overDriveService.setDefaultLibrary(identity, libraryId, pathId);
        return ResponseEntity.noContent().build();
    }

    /**
     * PUT /api/overdrive/{identity}/label — set a card's display label. Omit the param to clear it
     * back to the library-derived default name.
     */
    @Operation(summary = "Set a card's display label",
               description = "Stores a friendly label for the card; omit the name to clear it.")
    @ApiResponse(responseCode = "204", description = "Label saved")
    @PutMapping("/{identity}/label")
    public ResponseEntity<Void> setCardLabel(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @RequestParam(required = false) String name) {
        requireEnabled();
        overDriveService.setCardLabel(identity, name);
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/overdrive/history — the current user's recent OverDrive activity (newest first).
     */
    @Operation(summary = "List the current user's OverDrive activity history")
    @ApiResponse(responseCode = "200", description = "History listed")
    @GetMapping("/history")
    public ResponseEntity<List<OverDriveAuditEntry>> history() {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.listHistory());
    }

    /**
     * GET /api/overdrive/import-destinations — the current user's per-document-type import destinations.
     */
    @Operation(summary = "Get per-document-type import destinations")
    @ApiResponse(responseCode = "200", description = "Destinations returned")
    @GetMapping("/import-destinations")
    public ResponseEntity<OverDriveImportDestinations> getImportDestinations() {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.getImportDestinations());
    }

    /**
     * PUT /api/overdrive/import-destinations — set the current user's per-document-type import
     * destinations (ebook vs audiobook). A null library/path for a type falls back to Bookdrop.
     */
    @Operation(summary = "Set per-document-type import destinations")
    @ApiResponse(responseCode = "204", description = "Destinations saved")
    @PutMapping("/import-destinations")
    public ResponseEntity<Void> setImportDestinations(@RequestBody OverDriveImportDestinations destinations) {
        requireEnabled();
        overDriveService.setImportDestinations(destinations != null ? destinations
                : new OverDriveImportDestinations(null, null, null, null, null, null));
        return ResponseEntity.noContent().build();
    }

    // ── Card Sharing ────────────────────────────────────────────────────

    /**
     * GET /api/overdrive/shareable-users — candidate users to share a card with (everyone but you).
     * Minimal identity fields only; available to any authenticated user for the share picker.
     */
    @Operation(summary = "List users a card can be shared with")
    @ApiResponse(responseCode = "200", description = "Users listed")
    @GetMapping("/shareable-users")
    public ResponseEntity<List<OverDriveShareUser>> shareableUsers() {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.shareableUsers());
    }

    /**
     * GET /api/overdrive/{identity}/shares — users a card is currently shared with (owner or admin).
     */
    @Operation(summary = "List a card's shares")
    @ApiResponse(responseCode = "200", description = "Shares listed")
    @GetMapping("/{identity}/shares")
    public ResponseEntity<List<OverDriveShareUser>> listShares(
            @Parameter(description = "Library card id") @PathVariable String identity) {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.listShares(identity));
    }

    /**
     * PUT /api/overdrive/{identity}/shares — replace the set of users a card is shared with (owner or
     * admin). Body: {@code {"userIds": [1, 2]}}; an empty/absent list revokes all shares.
     */
    @Operation(summary = "Set a card's shares",
               description = "Replaces the users this card is shared with; owner or admin only.")
    @ApiResponse(responseCode = "204", description = "Shares saved")
    @PutMapping("/{identity}/shares")
    public ResponseEntity<Void> setShares(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @RequestBody(required = false) OverDriveShareRequest request) {
        requireEnabled();
        overDriveService.setShares(identity, request != null ? request.userIds() : null);
        return ResponseEntity.noContent().build();
    }

    /** Request body for {@link #setShares}: the full set of user ids to share a card with. */
    record OverDriveShareRequest(List<Long> userIds) {}

    // ── Sync ────────────────────────────────────────────────────────────

    /**
     * GET /api/overdrive/sync — sync loans and holds.
     */
    @Operation(summary = "Sync OverDrive loans and holds",
               description = "GET /chip/sync to retrieve current loans and holds for the authenticated library.")
    @ApiResponse(responseCode = "200", description = "Sync successful")
    @GetMapping("/sync")
    public ResponseEntity<OverDriveSyncResult> sync(
            @Parameter(description = "Library identity (card ID)") @RequestParam String identity
    ) {
        requireEnabled();
        OverDriveSyncResponse sync = overDriveService.sync(identity);
        return ResponseEntity.ok(convertSync(identity, sync));
    }

    // ── Loans ────────────────────────────────────────────────────────────

    /**
     * POST /api/overdrive/{identity}/borrow — borrow a book.
     */
    @Operation(summary = "Borrow a book from OverDrive",
               description = "POST /card/{cardId}/loan/{titleId} to borrow a title by its OverDrive title id.")
    @ApiResponse(responseCode = "200", description = "Book borrowed successfully")
    @PostMapping("/{identity}/borrow")
    public ResponseEntity<OverDriveBorrowResult> borrow(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @Parameter(description = "OverDrive title id to borrow") @RequestBody Map<String, String> body
    ) {
        requireEnabled();
        String titleId = body.get("titleId");
        if (titleId == null || titleId.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("titleId is required");
        }

        // Media-type hint so audiobooks borrow as "audiobook" (defaults to ebook when omitted).
        String loanId = overDriveService.borrow(identity, titleId, body.get("titleFormat"));
        return ResponseEntity.ok(new OverDriveBorrowResult(loanId));
    }

    /**
     * GET /api/overdrive/capabilities — report which OverDrive features are available in this deployment.
     */
    @Operation(summary = "OverDrive capabilities",
               description = "Reports whether an external ACSM handler is configured (required to import Adobe-DRM formats).")
    @ApiResponse(responseCode = "200", description = "Capabilities returned")
    @GetMapping("/capabilities")
    public ResponseEntity<OverDriveCapabilities> capabilities() {
        return ResponseEntity.ok(new OverDriveCapabilities(
                acsmHandler.isConfigured(), overDriveService.credentialStorageEnabled(),
                overDriveService.audiobookHandlerConfigured(), overDriveService.magazineHandlerConfigured(),
                overDriveService.ebookHandlerConfigured()));
    }

    /**
     * GET /api/overdrive/diagnostics — a passive, read-only snapshot of current state (config, linked
     * cards with decoded chip identity, and locally-recorded loans). Makes no live Libby calls and
     * takes no input, so it never triggers fulfillment/borrow or affects rate-limiting.
     */
    @Operation(summary = "OverDrive diagnostics snapshot",
               description = "Reports current state only — feature flags, linked cards (chip primary/secondary, credential storage), and locally recorded loans. Read-only: no live OverDrive calls, no inputs.")
    @ApiResponse(responseCode = "200", description = "Diagnostics report returned")
    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", java.time.Instant.now().toString());
        report.put("enabled", overdriveProperties.isEnabled());
        Map<String, Object> acsm = new LinkedHashMap<>();
        acsm.put("enabled", acsmHandlerConfig.isEnabled());
        acsm.put("toolPathPresent", acsmHandlerConfig.getToolPath() != null && !acsmHandlerConfig.getToolPath().isBlank());
        acsm.put("toolArgs", acsmHandlerConfig.getToolArgs());
        acsm.put("timeoutSeconds", acsmHandlerConfig.getTimeoutSeconds());
        report.put("acsm", acsm);
        try {
            report.putAll(overDriveService.diagnose());
        } catch (Exception e) {
            report.put("error", e.getMessage());
        }
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/overdrive/resolve-library — validate an OverDrive library key and return its name.
     * Not gated on the lending feature flag so the admin can validate the key while configuring it.
     */
    @Operation(summary = "Resolve an OverDrive library key",
               description = "Looks the key up in the Thunder library directory to validate it and return the library's display name.")
    @ApiResponse(responseCode = "200", description = "Resolution result returned (valid=false when the key does not resolve)")
    @GetMapping("/resolve-library")
    public ResponseEntity<OverDriveLibraryResolution> resolveLibrary(
            @Parameter(description = "OverDrive library key (preferredKey, e.g. lapl)") @RequestParam String key
    ) {
        return ResponseEntity.ok(overDriveService.resolveLibrary(key));
    }

    /**
     * GET /api/overdrive/search — search the OverDrive catalog for borrowable titles.
     */
    @Operation(summary = "Search the OverDrive catalog",
               description = "Search the configured library's OverDrive catalog for borrowable ebook titles.")
    @ApiResponse(responseCode = "200", description = "Search results returned")
    @GetMapping("/search")
    public ResponseEntity<List<OverDriveCatalogItem>> search(
            @Parameter(description = "Search query (title/author/ISBN)") @RequestParam String query,
            @Parameter(description = "Card ids to scope the search to (their libraries); at least one is required")
            @RequestParam(required = false) List<String> cards,
            @Parameter(description = "Restrict the medium server-side: \"ebook\" or \"audiobook\" (blank = both)")
            @RequestParam(required = false) String mediaTypes,
            @Parameter(description = "Return only titles borrowable now (server-side showOnlyAvailable)")
            @RequestParam(required = false, defaultValue = "false") boolean availableOnly,
            @Parameter(description = "Restrict to an ISO language code (e.g. \"en\") server-side")
            @RequestParam(required = false) String language,
            @Parameter(description = "Max results to return; the UI grows this on \"load more\"")
            @RequestParam(required = false, defaultValue = "60") int limit
    ) {
        requireEnabled();
        if (query == null || query.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("query is required");
        }
        if (cards == null || cards.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("at least one card is required to search");
        }
        return ResponseEntity.ok(overDriveService.searchCatalog(query, cards, mediaTypes, availableOnly, language, limit));
    }

    /**
     * GET /api/overdrive/title/{titleId}/availability — check a title's availability across the given
     * cards' libraries, so the Holds tab can surface whether a held title is borrowable elsewhere.
     */
    @Operation(summary = "Check a title's availability across the user's libraries")
    @ApiResponse(responseCode = "200", description = "Per-library availability returned")
    @GetMapping("/title/{titleId}/availability")
    public ResponseEntity<List<OverDriveLibraryAvailability>> titleAvailability(
            @Parameter(description = "OverDrive title id") @PathVariable String titleId,
            @Parameter(description = "Card ids whose libraries to check") @RequestParam(required = false) List<String> cards
    ) {
        requireEnabled();
        return ResponseEntity.ok(overDriveService.titleAvailability(titleId, cards));
    }

    /**
     * POST /api/overdrive/titles/availability — batch per-library availability for many titles at once,
     * keyed by title id. Backs the Holds tab's "check all other libraries" with one lightweight call per
     * library instead of a full media fetch per title × library.
     */
    @Operation(summary = "Batch-check availability for many titles across the user's libraries")
    @ApiResponse(responseCode = "200", description = "Per-title, per-library availability returned")
    @PostMapping("/titles/availability")
    public ResponseEntity<Map<String, List<OverDriveLibraryAvailability>>> titlesAvailability(
            @RequestBody TitlesAvailabilityRequest request
    ) {
        requireEnabled();
        List<String> titleIds = request != null ? request.titleIds() : null;
        List<String> cards = request != null ? request.cards() : null;
        return ResponseEntity.ok(overDriveService.availabilityForTitles(
                titleIds != null ? titleIds : List.of(),
                cards != null ? cards : List.of()));
    }

    record TitlesAvailabilityRequest(List<String> titleIds, List<String> cards) {}

    /**
     * POST /api/overdrive/{identity}/borrow-and-import — borrow a title, fulfill it, and import the EPUB.
     */
    @Operation(summary = "Borrow an OverDrive title and import it into a library",
               description = "Borrows the title, fulfills the EPUB (prefers the DRM-free open format; falls back to the Adobe/ACSM format which needs a configured external ACSM handler), and imports it into the target library.")
    @ApiResponse(responseCode = "200", description = "Book borrowed and imported")
    @ApiResponse(responseCode = "400", description = "Invalid request, or Adobe/ACSM-only title with no ACSM handler configured")
    @PostMapping("/{identity}/borrow-and-import")
    public ResponseEntity<Book> borrowAndImport(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @RequestBody OverDriveBorrowImportRequest request
    ) {
        requireEnabled();
        if (request.getTitleId() == null || request.getTitleId().isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("titleId is required");
        }
        // libraryId/pathId are optional: when omitted (or the library won't keep the format), the
        // fulfilled book is dropped into Bookdrop for review instead of imported into a library.

        try {
            Book book = overDriveService.borrowAndImport(
                    identity, request.getTitleId(),
                    request.getLibraryId(), request.getPathId(),
                    request.getTitle(), request.getAuthor(),
                    request.getCoverUrl(), request.getIsbn(),
                    request.getFormatId(), request.getTitleFormat());
            return ResponseEntity.ok(book);
        } catch (APIException e) {
            throw e; // already a clean, user-facing error (e.g. duplicate file, bad library)
        } catch (Exception e) {
            // Borrow/fulfill failures surface as readable 400s rather than opaque 500s.
            log.warn("OverDrive borrow-and-import failed: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    e.getMessage() != null ? e.getMessage() : "OverDrive borrow & import failed");
        }
    }

    /**
     * POST /api/overdrive/{identity}/fulfill — download a borrowed book.
     * Returns the ACSM fulfillment token (base64).
     */
    @Operation(summary = "Fulfill a loan to download its ACSM fulfillment token",
               description = "POST /card/{cardId}/loan/{loanId}/fulfill/ebook-epub-adobe to get the ACSM content.")
    @ApiResponse(responseCode = "200", description = "Loan fulfilled, ACSM returned (base64)")
    @PostMapping("/{identity}/fulfill/{loanId}")
    public ResponseEntity<OverDriveFulfillResult> fulfill(
            @Parameter(description = "Library identity") @PathVariable String identity,
            @Parameter(description = "Loan ID to fulfill") @PathVariable String loanId
    ) {
        requireEnabled();
        try {
            String acsmBase64 = overDriveService.fulfill(identity, loanId);
            return ResponseEntity.ok(new OverDriveFulfillResult(acsmBase64));
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            // Surface fulfill failures as a readable 400 rather than an opaque 500.
            log.warn("OverDrive fulfill failed: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    e.getMessage() != null ? e.getMessage() : "OverDrive fulfill failed");
        }
         }

             /**
              * POST /api/overdrive/{identity}/fulfill/{loanId}/download —
              * Full flow: fetch the loan's ACSM and hand it to the external ACSM handler to procure the book.
              * Returns the resulting book file as a download.
              */
             @Operation(summary = "Fulfill via ACSM handler: download the book file",
                       description = "Fetches the loan's ACSM, then hands it to the configured external ACSM handler tool which procures the book file. Returns that file.")
             @ApiResponse(responseCode = "200", description = "Book file returned")
             @ApiResponse(responseCode = "400", description = "ACSM handler not configured or failed")
             @PostMapping(value = "/{identity}/fulfill/{loanId}/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        public ResponseEntity<byte[]> downloadViaAcsm(
                 @Parameter(description = "Library card id") @PathVariable String identity,
                 @Parameter(description = "Loan ID to fulfill") @PathVariable String loanId,
                 HttpServletResponse response
          ) {
             requireEnabled();
             if (!acsmHandlerConfig.isEnabled()) {
                 log.warn("ACSM handler not enabled. Configure app.acsm.enabled=true");
                 throw ApiError.GENERIC_BAD_REQUEST.createException("ACSM handler not enabled");
                  }

             // Step 1: Fetch the ACSM from OverDrive.
             byte[] acsmBytes = getAcsmBytes(identity, loanId);
             if (acsmBytes == null || acsmBytes.length == 0) {
                 log.warn("Failed to get ACSM for loan {}", loanId);
                 throw ApiError.GENERIC_BAD_REQUEST.createException("Failed to fetch ACSM");
                  }

             log.info("Got {} bytes ACSM for loan {}, handing to ACSM handler", acsmBytes.length, loanId);

             // Step 2: Procure the book via the external tool.
             byte[] bookBytes = acsmHandler.handle(acsmBytes);
             if (bookBytes == null || bookBytes.length == 0) {
                 log.error("ACSM handler produced no output");
                 throw ApiError.GENERIC_BAD_REQUEST.createException("ACSM handler failed to produce a book file");
                  }

             log.info("ACSM handler produced {} bytes for loan {}", bookBytes.length, loanId);
             return new ResponseEntity<>(bookBytes, HttpStatus.OK);
          }

             private byte[] getAcsmBytes(String identity, String loanId) {
                 try {
                     String acsmBase64 = overDriveService.fulfill(identity, loanId);
                     if (acsmBase64 == null) return null;
                     return java.util.Base64.getDecoder().decode(acsmBase64);
                  } catch (Exception e) {
                     log.error("Failed to get ACSM for loan {}: {}", loanId, e.getMessage());
                     return null;
                   }
                 }

    /**
     * POST /api/overdrive/{identity}/fulfill/{loanId}/download-audiobook —
     * The audiobook analogue of {@link #downloadViaAcsm}: hands the loan to the external audiobook tool,
     * which authenticates itself and assembles the audiobook file, and streams that file back. The tool
     * picks the container, so the download name uses the extension it reports.
     *
     * <p>The file is streamed straight off disk rather than buffered into a {@code byte[]} — an
     * assembled audiobook is routinely 200+ MB. The scratch directory holding it is deleted once the
     * response body has been written (or the client has given up), and eagerly on any earlier failure.
     */
    @Operation(summary = "Download an audiobook loan via the external audiobook handler",
               description = "Hands the audiobook loan to the configured external audiobook tool, which fulfills and assembles the file, and returns it as a download.")
    @ApiResponse(responseCode = "200", description = "Audiobook file returned")
    @ApiResponse(responseCode = "400", description = "Audiobook handler not configured or failed")
    @PostMapping(value = "/{identity}/fulfill/{loanId}/download-audiobook", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> downloadAudiobook(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @Parameter(description = "Loan ID to fulfill") @PathVariable String loanId,
            @Parameter(description = "Audiobook format id (optional; discovered from the loan when omitted)") @RequestParam(required = false) String formatId
    ) {
        requireEnabled();
        if (!audiobookHandler.isConfigured()) {
            log.warn("Audiobook handler not configured; cannot download audiobook for loan {}", loanId);
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook handler not configured");
        }
        Path workDir;
        try {
            workDir = Files.createTempDirectory("overdrive-audiobook-download-");
        } catch (IOException e) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Could not create a temporary directory for the download: " + e.getMessage());
        }
        try {
            AudiobookHandler.Result result = overDriveService.downloadAudiobook(identity, loanId, formatId, workDir);
            String filename = loanId + "." + result.extension();
            StreamingResponseBody body = out -> {
                try {
                    Files.copy(result.file(), out);
                } finally {
                    FileUtils.deleteDirectoryQuietly(workDir);
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(Files.size(result.file()))
                    .body(body);
        } catch (APIException e) {
            FileUtils.deleteDirectoryQuietly(workDir);
            throw e;
        } catch (Exception e) {
            FileUtils.deleteDirectoryQuietly(workDir);
            log.warn("OverDrive audiobook download failed for loan {}: {}", loanId, e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    e.getMessage() != null ? e.getMessage() : "OverDrive audiobook download failed");
        }
    }

    /**
     * POST /api/overdrive/{identity}/return — return a book.
     */
    @Operation(summary = "Return a borrowed book",
               description = "DELETE /card/{cardId}/loan/{loanId} to return a book.")
    @ApiResponse(responseCode = "200", description = "Book returned successfully")
    @PostMapping("/{identity}/return/{loanId}")
    public ResponseEntity<Void> returnBook(
            @Parameter(description = "Library identity") @PathVariable String identity,
            @Parameter(description = "Loan ID to return") @PathVariable String loanId
    ) {
        requireEnabled();
        overDriveService.returnBook(identity, loanId);
        return ResponseEntity.ok().build();
    }

    // ── Holds ────────────────────────────────────────────────────────────

    /**
     * POST /api/overdrive/{identity}/hold/{titleId} — place a hold on a title.
     */
    @Operation(summary = "Place a hold on a title",
               description = "POST /card/{cardId}/hold/{titleId} to place a hold.")
    @ApiResponse(responseCode = "200", description = "Hold placed successfully")
    @PostMapping("/{identity}/hold/{titleId}")
    public ResponseEntity<Void> placeHold(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @Parameter(description = "OverDrive title id to hold") @PathVariable String titleId
    ) {
        requireEnabled();
        overDriveService.placeHold(identity, titleId);
        return ResponseEntity.ok().build();
    }

    /**
     * DELETE /api/overdrive/{identity}/hold/{titleId} — cancel a hold.
     */
    @Operation(summary = "Cancel a hold",
               description = "DELETE /card/{cardId}/hold/{titleId} to cancel a hold.")
    @ApiResponse(responseCode = "200", description = "Hold cancelled successfully")
    @DeleteMapping("/{identity}/hold/{titleId}")
    public ResponseEntity<Void> cancelHold(
            @Parameter(description = "Library card id") @PathVariable String identity,
            @Parameter(description = "OverDrive title id") @PathVariable String titleId
    ) {
        requireEnabled();
        overDriveService.cancelHold(identity, titleId);
        return ResponseEntity.ok().build();
    }

    // ── Token Management ─────────────────────────────────────────────────

    @Operation(summary = "Store an OverDrive auth token",
               description = "Store a token obtained from setup for later use.")
    @ApiResponse(responseCode = "200", description = "Token stored successfully")
    @PostMapping("/token")
    public ResponseEntity<Void> storeToken(
            @RequestBody StoreTokenRequest request
    ) {
        requireEnabled();
        // Token is carried in the body (never a query param) so it can't leak into access/proxy logs.
        if (request == null || request.identity() == null || request.identity().isBlank()
                || request.token() == null || request.token().isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("identity and token are required");
        }
        overDriveService.storeToken(request.identity(), null, null, request.token());
        return ResponseEntity.ok().build();
    }

    /** Request body for {@link #storeToken}; token stays out of the query string. */
    record StoreTokenRequest(String identity, String token) {}

    @Operation(summary = "Remove a stored token",
               description = "Remove a previously stored token.")
    @ApiResponse(responseCode = "200", description = "Token removed successfully")
    @DeleteMapping("/token")
    public ResponseEntity<Void> removeToken(
            @Parameter(description = "Identity") @RequestParam String identity
    ) {
        requireEnabled();
        overDriveService.removeToken(identity);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/overdrive/{identity}/refresh — re-link a card from its stored (encrypted) card+PIN
     * credentials to mint a fresh token. Only works for card+PIN links with a credential key set.
     */
    @Operation(summary = "Refresh a card's token",
               description = "Re-links a card from its stored encrypted card+PIN credentials to obtain a fresh token (for expired/blocked tokens). Fails if the card has no stored credentials.")
    @ApiResponse(responseCode = "200", description = "Card refreshed")
    @ApiResponse(responseCode = "400", description = "No stored credentials, or re-link failed")
    @PostMapping("/{identity}/refresh")
    public ResponseEntity<Void> refreshCard(
            @Parameter(description = "Library card id") @PathVariable String identity
    ) {
        requireEnabled();
        try {
            overDriveService.refreshCard(identity);
            return ResponseEntity.ok().build();
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OverDrive card refresh failed: {}", e.getMessage());
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    e.getMessage() != null ? e.getMessage() : "OverDrive card refresh failed");
        }
    }

    @Operation(summary = "List stored token identities",
               description = "List all library identities that have stored tokens.")
    @ApiResponse(responseCode = "200", description = "Identities listed successfully")
    @GetMapping("/tokens")
    public ResponseEntity<List<String>> listTokens() {
        return ResponseEntity.ok(overDriveService.listIdentities());
    }

    // ── DTO Converters ───────────────────────────────────────────────────

    private OverDriveSyncResult convertSync(String identity, OverDriveSyncResponse sync) {
        List<OverDriveLoan> loans = sync.getLoans() != null ? sync.getLoans() : List.of();
        List<OverDriveHold> holds = sync.getHolds() != null ? sync.getHolds() : List.of();
        List<OverDriveLibrary> libraries = sync.getLibraries() != null ? sync.getLibraries() : List.of();

        // Enrich loans/holds with narrator/edition/duration in one catalog call — the sync feed carries
        // these only sparsely, if at all.
        List<String> titleIds = java.util.stream.Stream.concat(
                        loans.stream().map(OverDriveLoan::getId),
                        holds.stream().map(OverDriveHold::getId))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, OverDriveService.MediaExtras> extras = overDriveService.mediaExtras(titleIds);

        List<OverDriveLoanDto> loanDtos = loans.stream()
                .map(loan -> loanToDto(loan, extras.get(loan.getId())))
                .toList();

        List<OverDriveHoldDto> holdDtos = holds.stream()
                .map(hold -> holdToDto(hold, extras.get(hold.getId())))
                .toList();

        // The card's loan/hold usage vs. its limits, so the UI can show "5 of 10" and stop at the cap.
        Integer loanCount = null;
        Integer loanLimit = null;
        Integer holdCount = null;
        Integer holdLimit = null;
        boolean canPlaceHolds = true;
        if (sync.getCards() != null && !sync.getCards().isEmpty()) {
            OverDriveSyncResponse.Card card = sync.getCards().stream()
                    .filter(c -> identity != null && identity.equals(c.getCardId()))
                    .findFirst()
                    .orElse(sync.getCards().getFirst());
            if (card.getCounts() != null) {
                loanCount = card.getCounts().getLoan();
                holdCount = card.getCounts().getHold();
            }
            if (card.getLimits() != null) {
                loanLimit = card.getLimits().getLoan();
                holdLimit = card.getLimits().getHold();
            }
            canPlaceHolds = card.getCanPlaceHolds() == null || card.getCanPlaceHolds();
        }

        return new OverDriveSyncResult(loanDtos, holdDtos, libraries,
                loanCount, loanLimit, holdCount, holdLimit, canPlaceHolds);
    }

    private OverDriveLoanDto loanToDto(OverDriveLoan loan, OverDriveService.MediaExtras extras) {
        return new OverDriveLoanDto(
                loan.getId(),
                loan.getTitle(),
                loan.getSubtitle(),
                loan.getExpireDate(),
                loan.getCheckoutDate(),
                loan.getFirstCreatorName(),
                loanCoverUrl(loan.getCovers()),
                loan.getCreators(),
                loanFormatId(loan),
                loan.getFormats(),
                overDriveService.resolveLoanBookId(loan.getId(), loanIsbn(loan), loanAsin(loan)),
                extras != null ? extras.narrator() : null,
                extras != null ? extras.edition() : null,
                extras != null ? extras.duration() : null,
                extras != null && extras.audiobook(),
                extras != null && extras.magazine()
        );
    }

    /**
     * The format a loan is actually in: the locked-in format the loan committed to (what was
     * fulfilled), else the format grimmory would import, else the loan's primary format. Avoids
     * mislabelling a loan with an arbitrary offered format (e.g. Kindle/OverDrive Read) when the
     * fulfilled/importable format is really Adobe EPUB.
     */
    private String loanFormatId(OverDriveLoan loan) {
        if (loan.getFormats() != null && !loan.getFormats().isEmpty()) {
            String locked = loan.getFormats().stream()
                    .filter(f -> Boolean.TRUE.equals(f.getIsLockedIn()) && f.getId() != null)
                    .map(OverDriveFormat::getId)
                    .findFirst()
                    .orElse(null);
            if (locked != null) {
                return locked;
            }
            String importable = overDriveService.chooseImportFormat(loan.getFormats().stream()
                    .map(OverDriveFormat::getId)
                    .filter(id -> id != null)
                    .toList());
            if (importable != null) {
                return importable;
            }
        }
        return loan.getFormat() != null ? loan.getFormat().getId() : null;
    }

    /** First usable ISBN a loan carries (single format, then the format list), or null. */
    private String loanIsbn(OverDriveLoan loan) {
        if (loan.getFormat() != null && loan.getFormat().getIsbn() != null && !loan.getFormat().getIsbn().isBlank()) {
            return loan.getFormat().getIsbn();
        }
        if (loan.getFormats() != null) {
            return loan.getFormats().stream()
                    .map(OverDriveFormat::getIsbn)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /** The loan's ASIN identifier, if the sync provided one — used to match audiobooks lacking an ISBN. */
    private String loanAsin(OverDriveLoan loan) {
        if (loan.getIdentifiers() == null) {
            return null;
        }
        return loan.getIdentifiers().stream()
                .filter(id -> id != null && id.getType() != null && id.getType().equalsIgnoreCase("ASIN")
                        && id.getValue() != null && !id.getValue().isBlank())
                .map(id -> id.getValue().trim())
                .findFirst()
                .orElse(null);
    }

    /** Href of the largest available cover rendition from a sync loan/hold's covers, or null. */
    private String loanCoverUrl(OverDriveCover covers) {
        if (covers == null) {
            return null;
        }
        // Prefer the largest available so the hover preview is crisp; the 40px thumbnail downscales fine.
        OverDriveCoverDetail best = null;
        int bestWidth = -1;
        for (Map.Entry<String, OverDriveCoverDetail> entry : covers.getVariants().entrySet()) {
            OverDriveCoverDetail detail = entry.getValue();
            if (detail == null || detail.getHref() == null || detail.getHref().isBlank()) {
                continue;
            }
            int width = OverDriveItemExtractor.coverWidth(entry.getKey(), detail.getWidth() > 0 ? detail.getWidth() : null);
            if (width > bestWidth) {
                bestWidth = width;
                best = detail;
            }
        }
        return best != null ? OverDriveItemExtractor.encodeCoverUrl(best.getHref()) : null;
    }

    private OverDriveHoldDto holdToDto(OverDriveHold hold, OverDriveService.MediaExtras extras) {
        return new OverDriveHoldDto(
                hold.getId(),
                hold.getTitle(),
                hold.getSubtitle(),
                hold.getFirstCreatorName(),
                loanCoverUrl(hold.getCovers()),
                hold.getCreators(),
                hold.getEstimatedWaitDays(),
                Boolean.TRUE.equals(hold.getAvailable()),
                hold.getExpireDate(),
                hold.getPlacedDate(),
                extras != null ? extras.narrator() : null,
                extras != null ? extras.edition() : null,
                extras != null ? extras.duration() : null,
                extras != null && extras.audiobook(),
                extras != null && extras.magazine()
        );
    }

    // ── Response DTOs ────────────────────────────────────────────────────

    record OverDriveCapabilities(boolean acsmHandlerConfigured, boolean credentialStorageEnabled,
                                 boolean audiobookHandlerConfigured, boolean magazineHandlerConfigured,
                                 boolean ebookHandlerConfigured) {}

    record OverDriveChipResult(String identity, String token) {}

    record OverDriveSyncResult(
            List<OverDriveLoanDto> loans,
            List<OverDriveHoldDto> holds,
            List<OverDriveLibrary> libraries,
            Integer loanCount,
            Integer loanLimit,
            Integer holdCount,
            Integer holdLimit,
            boolean canPlaceHolds
    ) {}

    record OverDriveLoanDto(
            String id,
            String title,
            String subtitle,
            String expireDate,
            String checkoutDate,
            String firstCreatorName,
            String coverUrl,
            List<OverDriveCreator> creators,
            String formatId,
            List<OverDriveFormat> formats,
            Long bookId,
            String narrator,
            String edition,
            String duration,
            boolean audiobook,
            boolean magazine
    ) {}

    record OverDriveHoldDto(
            String id,
            String title,
            String subtitle,
            String firstCreatorName,
            String coverUrl,
            List<OverDriveCreator> creators,
            String estimatedWaitDays,
            boolean ready,
            String expireDate,
            String placedDate,
            String narrator,
            String edition,
            String duration,
            boolean audiobook,
            boolean magazine
    ) {}

    record OverDriveBorrowResult(String loanId) {}

    record OverDriveFulfillResult(String acsmBase64) {}
}