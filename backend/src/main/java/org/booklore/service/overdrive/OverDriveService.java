package org.booklore.service.overdrive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.overdrive.*;
import org.booklore.model.dto.response.OverDriveApiResponse;
import org.booklore.model.entity.OverDriveLoanEntity;
import org.booklore.model.entity.OverDriveTokenEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.repository.OverDriveLoanRepository;
import org.booklore.repository.OverDriveTokenRepository;
import org.booklore.service.acsm.AcsmHandler;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.OverDriveParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Full-featured OverDrive/Libby service with chip token management,
 * borrowing, syncing, and loan persistence.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li><b>chip()</b> → get an initial identity token</li>
 *   <li><b>redeemSetupCode(code)</b> → exchange a Libby 8-digit setup code for persistent auth</li>
 *   <li><b>sync()</b> → get all loans/holds for the identity</li>
 *   <li><b>fulfill()</b> → download the ACSM fulfillment token for a loan</li>
 *   <li><b>return()</b> → return a book</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class OverDriveService {

    private final OverDriveLoanRepository loanRepository;
    private final AcsmHandler acsmHandler;

     @Value("${app.overdrive.sentry-base-url:https://sentry.libbyapp.com}")
    private String sentryBaseUrl;

     @Value("${app.overdrive.client-id:dewey}")
    private String clientId;

    private final RestClient restClient;
    private final OverDriveImportService overDriveImportService;
    private final OverDriveParser overDriveParser;
    private final OverDriveTokenRepository tokenRepository;
    private final AuthenticationService authenticationService;
    private final AppSettingService appSettingService;

    /** The authenticated Grimmory user's id, or throws if there is no authenticated user. */
    private Long currentUserId() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            throw new RestClientException("No authenticated user for OverDrive operation");
        }
        return user.getId();
    }

    /** Libby's private API rejects non-browser agents; mirror the web client. */
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; Grimmory)";
    private static final String REFERER = "https://libbyapp.com/";
    private static final int LOAN_PERIOD_DAYS = 21;

    // OverDrive ebook fulfillment format ids. "-open" variants are DRM-free (fulfilled directly);
    // "-adobe" variants yield an ACSM that requires the external ACSM handler tool.
    private static final String FORMAT_EPUB_OPEN = "ebook-epub-open";
    private static final String FORMAT_EPUB_ADOBE = "ebook-epub-adobe";
    private static final String FORMAT_PDF_OPEN = "ebook-pdf-open";
    private static final String FORMAT_PDF_ADOBE = "ebook-pdf-adobe";

    /** Format ids we know how to fulfill and import. */
    private static final List<String> SUPPORTED_FORMATS =
            List.of(FORMAT_EPUB_OPEN, FORMAT_EPUB_ADOBE, FORMAT_PDF_OPEN, FORMAT_PDF_ADOBE);

    /** Default preference: DRM-free EPUB, Adobe EPUB, DRM-free PDF, Adobe PDF. */
    private static final List<String> DEFAULT_FORMAT_PREFERENCE =
            List.of(FORMAT_EPUB_OPEN, FORMAT_EPUB_ADOBE, FORMAT_PDF_OPEN, FORMAT_PDF_ADOBE);

    private static boolean isOpenFormat(String formatId) {
        return formatId != null && formatId.endsWith("-open");
    }

    private static BookFileType bookFileType(String formatId) {
        return formatId != null && formatId.startsWith("ebook-pdf") ? BookFileType.PDF : BookFileType.EPUB;
    }

    private static String fileExtension(String formatId) {
        return bookFileType(formatId) == BookFileType.PDF ? "pdf" : "epub";
    }

    /** Standard Libby headers (browser UA + referer), with optional bearer auth. */
    private HttpHeaders libbyHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.REFERER, REFERER);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }

     // ── Chip / Auth ─────────────────────────────────────────────────────

      /**
       * POST /chip?client=dewey to obtain a fresh chip identity. The returned {@code identity} JWT is
       * the bearer token used for all subsequent authenticated Libby calls.
       */
      public ChipResult requestChip() {
        String url = sentryBaseUrl + "/chip?client=" + clientId;
        try {
            ResponseEntity<OverDriveChipResponse> resp = restClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(libbyHeaders(null)))
                    .retrieve()
                    .toEntity(OverDriveChipResponse.class);

            OverDriveChipResponse body = resp.getBody();
            String token = body != null ? body.getIdentity() : null;
            if (token == null || token.isBlank()) {
                log.error("Chip request returned no identity token");
                throw new RestClientException("OverDrive chip request failed: no identity token returned");
             }

            return new ChipResult(token, token, body.getAccess_token_expires_in());
         } catch (Exception e) {
            log.error("Failed to obtain OverDrive chip: {}", e.getMessage());
            throw new RestClientException("OverDrive chip request failed: " + e.getMessage());
         }
      }

      /**
       * Link a library card to a fresh chip identity using a Libby 8-digit setup code
       * (libbyapp.com → Settings → "Copy to another device" / setup-code). Returns the resolved
       * card id and the identity token, and persists the token for later use.
       */
      public TokenResult redeemSetupCode(String setupCode) {
        String code = setupCode != null ? setupCode.trim() : "";
        if (!code.matches("\\d{8}")) {
            throw new RestClientException("Invalid Libby setup code: expected 8 digits");
        }

        // 1. Fresh chip identity — this token authenticates everything that follows.
        String token = requestChip().token();

        // 2. Associate the card to this identity: POST /chip/clone/code (form-encoded code).
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        HttpHeaders headers = libbyHeaders(token);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            restClient.post()
                    .uri(sentryBaseUrl + "/chip/clone/code")
                    .headers(h -> h.addAll(headers))
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
         } catch (Exception e) {
            log.error("Failed to register Libby setup code: {}", e.getMessage());
            throw new RestClientException("OverDrive setup-code registration failed: " + e.getMessage());
         }

        // 3. Discover the card id (the "identity" we key loans/tokens by) from sync.
        String cardId = firstCardId(token);
        String identity = cardId != null ? cardId : "default";
        storeToken(identity, token);
        log.info("Libby account linked; card id: {}", identity);
        return new TokenResult(identity, token);
      }

      /** Fetch the first library card id from a sync, or null if none. */
      private String firstCardId(String token) {
        try {
            Map<?, ?> body = restClient.get()
                    .uri(sentryBaseUrl + "/chip/sync")
                    .headers(h -> h.addAll(libbyHeaders(token)))
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();
            if (body != null && body.get("cards") instanceof List<?> cards
                    && !cards.isEmpty() && cards.getFirst() instanceof Map<?, ?> card
                    && card.get("cardId") != null) {
                return card.get("cardId").toString();
            }
         } catch (Exception e) {
            log.warn("OverDrive: could not determine card id from sync: {}", e.getMessage());
         }
        return null;
      }

     // ── Sync / Loans ────────────────────────────────────────────────────

      /**
       * GET /chip/sync — fetches current loans and holds.
       * Persists loan state to the database.
       */
      public OverDriveSyncResponse sync(String identity, String authToken) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/chip/sync";
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            ResponseEntity<OverDriveSyncResponse> resp = restClient.get()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toEntity(OverDriveSyncResponse.class);

            OverDriveSyncResponse syncResp = resp.getBody();
            if (syncResp != null && syncResp.getLoans() != null) {
                 Long userId = currentUserId();
                 for (OverDriveLoan loan : syncResp.getLoans()) {
                    persistLoan(userId, identity, loan);
                 }
             }

            log.info("OverDrive sync: {} loans, {} holds",
                    syncResp != null && syncResp.getLoans() != null ? syncResp.getLoans().size() : 0,
                    syncResp != null && syncResp.getHolds() != null ? syncResp.getHolds().size() : 0);
            return syncResp;
         } catch (Exception e) {
            log.error("OverDrive sync failed: {}", e.getMessage());
            throw new RestClientException("OverDrive sync failed: " + e.getMessage());
         }
      }

      private void persistLoan(Long userId, String identity, OverDriveLoan loan) {
        OverDriveLoanEntity entity = loanRepository
                .findByUserIdAndOverdriveLoanId(userId, loan.getId())
                .orElseGet(() -> {
                    OverDriveLoanEntity e = new OverDriveLoanEntity();
                    e.setOverdriveLoanId(loan.getId());
                    e.setIdentity(identity);
                    e.setUserId(userId);
                    return e;
                });
        entity.setIdentity(identity);

        entity.setTitle(loan.getTitle());
        if (loan.getCreators() != null && !loan.getCreators().isEmpty()) {
            entity.setAuthor(loan.getCreators().stream()
                    .map(c -> c.getName())
                    .collect(Collectors.joining(", ")));
         }
        entity.setExpireDate(parseExpireDate(loan.getExpireDate()));
        entity.setFormatId(loan.getFormat() != null ? loan.getFormat().getId() : null);
        entity.setState("BORROWED");
        entity.setLastSync(Instant.now());

        loanRepository.save(entity);
      }

      /**
       * Parse an OverDrive expiry timestamp into an {@link Instant}, tolerating the
       * offset-based formats OverDrive can return. Returns null (rather than aborting
       * the whole sync) when the value is missing or unparseable.
       */
      private Instant parseExpireDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
         }
        try {
            return Instant.parse(value);
         } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
             } catch (DateTimeParseException ex) {
                log.warn("Unparseable OverDrive expireDate '{}', leaving null", value);
                return null;
             }
         }
      }

      /**
       * POST /card/{cardId}/loan/{loanId}/fulfill/ebook-epub-adobe
       * Returns the ACSM fulfillment token content as base64.
       */
      public String fulfill(String identity, String authToken, String loanId) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/card/" + identity + "/loan/" + loanId + "/fulfill/" + FORMAT_EPUB_ADOBE;
        HttpHeaders headers = libbyHeaders(authToken);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try {
            ResponseEntity<byte[]> resp = restClient.get()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toEntity(byte[].class);

            if (resp.getBody() == null) {
                log.warn("OverDrive fulfill returned empty body for loan {}", loanId);
                return null;
             }

             // Mark loan as fulfilled
            loanRepository.findByOverdriveLoanIdAndIdentity(loanId, identity)
                    .ifPresent(entity -> {
                        entity.setFulfilled(true);
                        loanRepository.save(entity);
                     });

            log.info("OverDrive loan fulfilled: {} bytes for loan {}", resp.getBody().length, loanId);
            return Base64.getEncoder().encodeToString(resp.getBody());
         } catch (Exception e) {
            log.error("OverDrive fulfill failed for loan {}: {}", loanId, e.getMessage());
            throw new RestClientException("OverDrive fulfill failed: " + e.getMessage());
         }
      }

      /**
       * POST /card/{cardId}/loan/{titleId} — borrow a title by its OverDrive title id.
       * Returns the created loan id.
       */
      public String borrow(String identity, String authToken, String titleId) {
        Object id = borrowLoan(identity, resolveToken(identity, authToken), titleId).get("id");
        return id != null ? id.toString() : null;
      }

      /** Borrow a title and return the raw loan object (which includes the available {@code formats}). */
      private Map<String, Object> borrowLoan(String cardId, String authToken, String titleId) {
        String url = sentryBaseUrl + "/card/" + cardId + "/loan/" + titleId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", LOAN_PERIOD_DAYS);
        body.put("units", "days");
        body.put("title_format", "ebook");

        HttpHeaders headers = libbyHeaders(authToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            Object raw = restClient.post()
                    .uri(url)
                    .body(new HttpEntity<>(body, headers))
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();

            @SuppressWarnings("unchecked")
            Map<String, Object> bodyMap = (Map<String, Object>) raw;
            if (bodyMap == null || bodyMap.get("id") == null) {
                throw new RestClientException("OverDrive borrow failed: no loan id returned");
             }
            log.info("OverDrive title borrowed: {} (title {})", bodyMap.get("id"), titleId);
            return bodyMap;
         } catch (Exception e) {
            log.error("OverDrive borrow failed: {}", e.getMessage());
            throw new RestClientException("OverDrive borrow failed: " + e.getMessage());
         }
      }

      /** Extract the fulfillment format ids advertised by a loan object. */
      private List<String> loanFormatIds(Map<String, Object> loan) {
        List<String> ids = new ArrayList<>();
        if (loan.get("formats") instanceof List<?> formats) {
            for (Object f : formats) {
                if (f instanceof Map<?, ?> fm && fm.get("id") != null) {
                    ids.add(fm.get("id").toString());
                }
            }
        }
        return ids;
      }

      /**
       * Fulfill a DRM-free open format (EPUB or PDF). The fulfill endpoint 302s to a fulfillment URL
       * that redirects again to the open-content CDN; the RestClient follows the chain and returns the
       * file bytes.
       */
      private byte[] fulfillOpen(String cardId, String authToken, String loanId, String formatId) {
        String url = sentryBaseUrl + "/card/" + cardId + "/loan/" + loanId + "/fulfill/" + formatId;
        HttpHeaders headers = libbyHeaders(authToken);
        headers.set(HttpHeaders.ACCEPT, "*/*");
        ResponseEntity<byte[]> resp = restClient.get()
                .uri(url)
                .headers(h -> h.addAll(headers))
                .retrieve()
                .toEntity(byte[].class);
        return resp.getBody();
      }

      /**
       * The preferred order of ebook fulfillment formats: the operator-configured list (filtered to
       * supported formats) if set, otherwise the built-in default.
       */
      private List<String> formatPreference() {
        MetadataProviderSettings settings = appSettingService.getAppSettings().getMetadataProviderSettings();
        List<String> configured = settings != null && settings.getOverdrive() != null
                ? settings.getOverdrive().getFormatPreference() : null;
        if (configured != null) {
            List<String> filtered = configured.stream().filter(SUPPORTED_FORMATS::contains).toList();
            if (!filtered.isEmpty()) {
                return filtered;
            }
        }
        return DEFAULT_FORMAT_PREFERENCE;
      }

      /**
       * Choose the first preferred format a loan actually offers and that we can fulfill. Open formats
       * are always fulfillable; Adobe formats need the external ACSM handler, so they are skipped when
       * no handler is configured. Returns null when nothing fulfillable matches.
       */
      private String chooseFormat(List<String> loanFormats) {
        return selectFormat(loanFormats, formatPreference(), acsmHandler.isConfigured());
      }

      /** Pure selection: first preferred format offered by the loan that is fulfillable. */
      static String selectFormat(List<String> loanFormats, List<String> preference, boolean acsmHandlerReady) {
        for (String preferred : preference) {
            if (!loanFormats.contains(preferred)) {
                continue;
            }
            if (!isOpenFormat(preferred) && !acsmHandlerReady) {
                continue; // Adobe format but no ACSM handler to procure it.
            }
            return preferred;
        }
        return null;
      }

      /** The built-in default format preference (exposed for tests). */
      static List<String> defaultFormatPreference() {
        return DEFAULT_FORMAT_PREFERENCE;
      }

      /**
       * Search the OverDrive catalog for borrowable titles. Backed by the read-only Thunder catalog API
       * (no auth); results carry the format id needed to {@link #borrowAndImport}.
       */
      public List<OverDriveCatalogItem> searchCatalog(String query) {
        return overDriveParser.searchCatalog(query).stream()
                .map(this::toCatalogItem)
                .filter(i -> i.title() != null && !i.title().isBlank())
                .toList();
      }

      /**
       * Borrow a title by id, fulfill it, and import the book (EPUB or PDF) into the given library.
       * The fulfillment format is chosen by the configured {@link #formatPreference() preference}
       * among the formats the title offers; DRM-free "open" formats import directly, while Adobe
       * formats are procured via the external ACSM handler (and are skipped if none is configured).
       *
       * @param titleId the OverDrive title id to borrow
       * @return the persisted {@link Book}
       */
      public Book borrowAndImport(String identity, String authToken, String titleId, long libraryId, long pathId,
                                  String title, String author, String coverUrl, String isbn) {
        authToken = resolveToken(identity, authToken);
        Map<String, Object> loan = borrowLoan(identity, authToken, titleId);
        String loanId = loan.get("id").toString();
        List<String> formats = loanFormatIds(loan);

        String chosenFormat = chooseFormat(formats);
        if (chosenFormat == null) {
            throw new RestClientException("No importable format for this title (loan " + loanId + "). "
                    + "Offered: " + formats + ". Open formats import directly; Adobe formats require a "
                    + "configured external ACSM handler.");
        }

        byte[] content;
        if (isOpenFormat(chosenFormat)) {
            // DRM-free: fulfill directly — no external tool required.
            content = fulfillOpen(identity, authToken, loanId, chosenFormat);
            if (content == null || content.length == 0) {
                throw new RestClientException("Open fulfillment returned no data for loan " + loanId);
            }
        } else {
            // Adobe format: hand the ACSM to the configured external tool to procure the book.
            byte[] acsm = getAcsm(identity, authToken, loanId, chosenFormat);
            if (acsm == null || acsm.length == 0) {
                throw new RestClientException("Could not fetch the ACSM for loan " + loanId);
            }
            content = acsmHandler.handle(acsm);
            if (content == null || content.length == 0) {
                throw new RestClientException("The external ACSM handler did not produce a book file for loan "
                        + loanId + ".");
            }
        }

        BookFileType fileType = bookFileType(chosenFormat);
        BookMetadata metadata = buildImportMetadata(title, author, coverUrl, isbn);
        Book book = overDriveImportService.importBook(
                content, buildFileName(title, loanId, fileExtension(chosenFormat)), libraryId, pathId, metadata, fileType);

        Long userId = currentUserId();
        OverDriveLoanEntity entity = loanRepository.findByUserIdAndOverdriveLoanId(userId, loanId)
                .orElseGet(() -> {
                    OverDriveLoanEntity e = new OverDriveLoanEntity();
                    e.setOverdriveLoanId(loanId);
                    e.setIdentity(identity);
                    e.setUserId(userId);
                    return e;
                });
        entity.setTitle(title);
        entity.setAuthor(author);
        entity.setFormatId(chosenFormat);
        entity.setState("ACTIVE");
        entity.setFulfilled(true);
        entity.setBookId(book.getId());
        entity.setLastSync(Instant.now());
        loanRepository.save(entity);

        log.info("OverDrive borrow-and-import complete: loan {} ({}) -> book {}", loanId, chosenFormat, book.getId());
        return book;
      }

      private String buildFileName(String title, String loanId, String extension) {
        String base = (title != null && !title.isBlank()) ? title : ("overdrive-" + loanId);
        return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + "." + extension;
      }

      /** Build the catalog-sourced metadata to layer onto the imported EPUB. */
      private BookMetadata buildImportMetadata(String title, String author, String coverUrl, String isbn) {
        String cleanedIsbn = isbn != null ? isbn.replaceAll("[^0-9Xx]", "") : null;
        String isbn13 = cleanedIsbn != null && cleanedIsbn.length() == 13 ? cleanedIsbn : null;
        String isbn10 = cleanedIsbn != null && cleanedIsbn.length() == 10 ? cleanedIsbn : null;
        return BookMetadata.builder()
                .title(title)
                .authors(author != null && !author.isBlank() ? List.of(author) : null)
                .thumbnailUrl(coverUrl != null && !coverUrl.isBlank() ? coverUrl : null)
                .isbn13(isbn13)
                .isbn10(isbn10)
                .build();
      }

      private OverDriveCatalogItem toCatalogItem(OverDriveApiResponse.Item item) {
        return new OverDriveCatalogItem(
                item.getId(),
                pickBorrowFormatId(item),
                item.getTitle(),
                extractPrimaryAuthor(item),
                extractCoverUrl(item),
                extractIsbn(item));
      }

      /** Prefer the Adobe EPUB format (what {@link #getAcsm} fulfills), else the first format id. */
      private String pickBorrowFormatId(OverDriveApiResponse.Item item) {
        if (item.getFormats() == null || item.getFormats().isEmpty()) {
            return null;
        }
        return item.getFormats().stream()
                .map(OverDriveApiResponse.Item.Format::getId)
                .filter(Objects::nonNull)
                .filter(id -> id.toLowerCase().contains("epub"))
                .findFirst()
                .orElseGet(() -> item.getFormats().getFirst().getId());
      }

      private String extractPrimaryAuthor(OverDriveApiResponse.Item item) {
        if (item.getCreators() == null || item.getCreators().isEmpty()) {
            return null;
        }
        return item.getCreators().stream()
                .filter(c -> c.getName() != null && c.getRole() != null && c.getRole().toLowerCase().contains("author"))
                .map(OverDriveApiResponse.Item.Creator::getName)
                .findFirst()
                .orElseGet(() -> item.getCreators().getFirst().getName());
      }

      private String extractCoverUrl(OverDriveApiResponse.Item item) {
        OverDriveApiResponse.Item.Covers covers = item.getCovers();
        if (covers == null) {
            return null;
        }
        for (OverDriveApiResponse.Item.Covers.Cover cover :
                Arrays.asList(covers.getCover510Wide(), covers.getCover300Wide(), covers.getCover150Wide())) {
            if (cover != null && cover.getHref() != null && !cover.getHref().isBlank()) {
                return cover.getHref();
            }
        }
        return null;
      }

      private String extractIsbn(OverDriveApiResponse.Item item) {
        if (item.getFormats() == null) {
            return null;
        }
        return item.getFormats().stream()
                .map(OverDriveApiResponse.Item.Format::getIsbn)
                .filter(isbn -> isbn != null && !isbn.isBlank())
                .findFirst()
                .orElse(null);
      }

      /**
       * DELETE /card/{cardId}/loan/{loanId} — return a book.
       */
      public void returnBook(String identity, String authToken, String loanId) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/card/" + identity + "/loan/" + loanId;
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            restClient.delete()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toBodilessEntity();

             // Mark loan as returned
            loanRepository.findByOverdriveLoanIdAndIdentity(loanId, identity)
                    .ifPresent(entity -> {
                        entity.setState("RETURNED");
                        loanRepository.save(entity);
                     });

            log.info("OverDrive book returned: {}", loanId);
             } catch (Exception e) {
               log.error("OverDrive return failed for loan {}: {}", loanId, e.getMessage());
               throw new RestClientException("OverDrive return failed: " + e.getMessage());
             }
           }

            private byte[] getAcsm(String identity, String authToken, String loanId, String formatId) {
               String url = sentryBaseUrl + "/card/" + identity + "/loan/" + loanId + "/fulfill/" + formatId;
               HttpHeaders headers = libbyHeaders(authToken);
               headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE);

               try {
                   ResponseEntity<byte[]> resp = restClient.get()
                            .uri(url)
                            .headers(h -> h.addAll(headers))
                            .retrieve()
                            .toEntity(byte[].class);

                   return (resp.getBody() != null) ? resp.getBody() : new byte[0];
                 } catch (Exception e) {
                   log.error("Failed to get ACSM for loan {}: {}", loanId, e.getMessage());
                   return null;
                 }
             }

            // ── Persistence Helpers ──────────────────────────────────────────────
      /**
       * GET /card/{cardId}/hold/{formatId} — place a hold.
       */
      public void placeHold(String identity, String authToken, String formatId) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/card/" + identity + "/hold/" + formatId;
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            restClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toBodilessEntity();

            log.info("OverDrive hold placed for format {}", formatId);
         } catch (Exception e) {
            log.error("OverDrive hold failed: {}", e.getMessage());
            throw new RestClientException("OverDrive hold failed: " + e.getMessage());
         }
      }

      /**
       * Cancel a hold.
       */
      public void cancelHold(String identity, String authToken, String formatId) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/card/" + identity + "/hold/" + formatId;
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            restClient.delete()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toBodilessEntity();

            log.info("OverDrive hold cancelled for format {}", formatId);
         } catch (Exception e) {
            log.error("OverDrive cancel hold failed: {}", e.getMessage());
            throw new RestClientException("OverDrive cancel hold failed: " + e.getMessage());
         }
      }

     // ── Token Management ─────────────────────────────────────────────────

      /** Store (or replace) the current user's token. Persisted so it survives restarts. */
      @Transactional
      public void storeToken(String identity, String token) {
        Long userId = currentUserId();
        OverDriveTokenEntity entity = tokenRepository.findByUserId(userId)
                .orElseGet(OverDriveTokenEntity::new);
        entity.setUserId(userId);
        entity.setIdentity(identity);
        entity.setToken(token);
        entity.setExpiresAt(Instant.now().getEpochSecond() + 3600);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        tokenRepository.save(entity);
        log.info("OverDrive token stored for user {} (card {})", userId, identity);
      }

      /** Remove the current user's stored token. */
      @Transactional
      public void removeToken(String identity) {
        tokenRepository.deleteByUserId(currentUserId());
        log.info("OverDrive token removed for user {}", currentUserId());
      }

      /** Whether the current user has a stored token. */
      public boolean hasToken(String identity) {
        return tokenRepository.existsByUserId(currentUserId());
      }

      /** The current user's linked card identity, if any (single-element list for API compatibility). */
      public List<String> listIdentities() {
        return tokenRepository.findByUserId(currentUserId())
                .map(t -> List.of(t.getIdentity()))
                .orElseGet(List::of);
      }

      /** The current user's stored token, or null. */
      public String getStoredToken(String identity) {
        return tokenRepository.findByUserId(currentUserId())
                .map(OverDriveTokenEntity::getToken)
                .orElse(null);
      }

      /** A specific user's stored token, or null. Used by background tasks that have no request user. */
      public String getStoredTokenForUser(Long userId) {
        if (userId == null) {
            return null;
        }
        return tokenRepository.findByUserId(userId)
                .map(OverDriveTokenEntity::getToken)
                .orElse(null);
      }

      /**
       * Resolve the token to use: the caller-supplied token when present, otherwise the current user's
       * stored token. Throws if neither is available.
       */
      private String resolveToken(String identity, String providedToken) {
        if (providedToken != null && !providedToken.isBlank()) {
            return providedToken;
        }
        String stored = getStoredToken(identity);
        if (stored == null || stored.isBlank()) {
            throw new RestClientException("No OverDrive token available; connect your Libby account first.");
        }
        return stored;
      }

     // ── Persistence Helpers ──────────────────────────────────────────────

      /** Get the current user's loans from the local database. */
      public List<OverDriveLoanEntity> getLoans(String identity) {
        return loanRepository.findByUserId(currentUserId());
      }

      /** Get the current user's active (non-returned/non-expired) loans. */
      public List<OverDriveLoanEntity> getActiveLoans(String identity) {
        return loanRepository.findByUserIdAndStateIn(currentUserId(), Arrays.asList("BORROWED", "ACTIVE"));
      }

     // ── Data Classes ─────────────────────────────────────────────────────

      /** Result from chip request. */
      public record ChipResult(String identity, String token, int expiresIn) {}

      /** Result from setup-code registration. */
      public record TokenResult(String identity, String token) {}
}