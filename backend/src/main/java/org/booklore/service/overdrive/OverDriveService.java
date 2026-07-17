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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Duration;
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
    private final OverDriveCredentialCipher credentialCipher;

    /** The authenticated Grimmory user's id, or throws if there is no authenticated user. */
    private Long currentUserId() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            throw new RestClientException("No authenticated user for OverDrive operation");
        }
        return user.getId();
    }

    // Mirror the Libby web client exactly (verified against a working browser HAR): a normal desktop
    // browser UA, plus Accept: application/json and Origin on API calls. The fulfill endpoint returns
    // JSON ({"fulfill":{"href":...}}), 403 {"result":"missing_chip"} until the chip is registered, and
    // 403 {"result":"whoa"} when the server refuses the fulfillment (exact cause not pinned down — the
    // whoa response is logged with full headers so a Retry-After, if present, confirms rate-limiting).
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0";
    private static final String REFERER = "https://libbyapp.com/";
    private static final String ORIGIN = "https://libbyapp.com";
    /** Dewey (Libby) client version sent to the chip endpoint (c=d:<version>), per the web client. */
    private static final String DEWEY_VERSION = "22.0.2";
    private static final int LOAN_PERIOD_DAYS = 21;

    private static final java.util.regex.Pattern RESULT_PATTERN =
            java.util.regex.Pattern.compile("\"result\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern HREF_PATTERN =
            java.util.regex.Pattern.compile("\"href\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern CHIP_ID_PATTERN =
            java.util.regex.Pattern.compile("\"chip\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"");

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
        headers.set(HttpHeaders.ORIGIN, ORIGIN);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.set(HttpHeaders.PRAGMA, "no-cache");
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
       * Re-mint the chip identity by POSTing {@code /chip} <b>with the current identity as the bearer</b>.
       * Libby returns a fresh {@code identity} JWT that reflects the chip's linked cards. This is
       * required before fulfillment: the original anonymous identity (its {@code cards} claim is null)
       * is accepted by sync/borrow but rejected with a 403 by the fulfill endpoint. Falls back to the
       * supplied token on any failure.
       */
      private String refreshIdentity(String token) {
        if (token == null || token.isBlank()) {
            return token;
        }
        try {
            // Mirror the web client's acquireChip: POST /chip?c=d:<ver>&s=0&v=<chip-id-prefix> with the
            // current identity as the bearer. Libby re-mints the identity registered for fulfillment.
            String uri = sentryBaseUrl + "/chip?c=d:" + DEWEY_VERSION + "&s=0";
            String chipId = chipIdOf(token);
            if (chipId != null) {
                uri += "&v=" + chipId.split("-")[0];
            }
            String finalUri = uri;
            ResponseEntity<OverDriveChipResponse> resp = restClient.post()
                    .uri(finalUri)
                    .headers(h -> h.addAll(libbyHeaders(token)))
                    .retrieve()
                    .toEntity(OverDriveChipResponse.class);
            OverDriveChipResponse body = resp.getBody();
            String refreshed = body != null ? body.getIdentity() : null;
            return refreshed != null && !refreshed.isBlank() ? refreshed : token;
        } catch (Exception e) {
            log.warn("OverDrive: identity refresh failed, using existing token: {}", e.getMessage());
            return token;
        }
      }

      /** Extract the chip id from an identity JWT's payload ({@code chip.id}), or null. */
      private static String chipIdOf(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])),
                    StandardCharsets.UTF_8);
            var m = CHIP_ID_PATTERN.matcher(payload);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
      }

      private static String padBase64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return s + "=".repeat(pad);
      }

      /**
       * Link a Libby account to a fresh chip identity using an 8-digit setup code
       * (libbyapp.com → Settings → "Copy to another device"). All cards on that identity are linked and
       * stored for the current user (each with the shared token); a user may redeem several codes to
       * link multiple accounts. Returns the cards linked by this code.
       */
      public List<OverDriveCard> redeemSetupCode(String setupCode) {
        String code = setupCode != null ? setupCode.trim() : "";
        if (!code.matches("\\d{8}")) {
            throw new RestClientException("Invalid Libby setup code: expected 8 digits");
        }

        // 1. Fresh chip identity — this token authenticates everything that follows.
        String token = requestChip().token();

        // 2. Associate the account to this identity: POST /chip/clone/code (form-encoded code).
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

        // 2b. Re-mint the identity now that cards are linked, so the stored token is card-bound
        // (the pre-clone identity is rejected by the fulfill endpoint — see refreshIdentity).
        token = refreshIdentity(token);

        // 3. Enumerate all cards on this identity and persist a token row per card for the user.
        List<OverDriveCard> cards = fetchCards(token);
        if (cards.isEmpty()) {
            throw new RestClientException("Setup code linked no library cards; check the code and try again.");
        }
        for (OverDriveCard card : cards) {
            storeToken(card.cardId(), card.name(), card.libraryKey(), token);
        }
        log.info("Libby account linked for user {}: {} card(s)", currentUserId(), cards.size());
        return cards;
      }

      /**
       * Link by pasting a Libby <b>identity token</b> copied from a signed-in browser (localStorage key
       * {@code …:sentry.identity}, or any request's {@code Authorization: Bearer} value). This is the
       * account's primary chip, so it can fulfill Adobe-DRM titles. Tolerates a leading {@code Bearer }
       * and surrounding quotes. The token is validated via sync and stored per card. No credentials are
       * stored, so it can't be auto-re-linked — when it expires, paste a fresh one.
       */
      public List<OverDriveCard> linkToken(String token) {
        String t = token != null ? token.trim() : "";
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.isEmpty()) {
            throw new RestClientException("A Libby identity token is required.");
        }
        List<OverDriveCard> cards = fetchCards(t);
        if (cards.isEmpty()) {
            throw new RestClientException("That token linked no library cards; it may be expired or invalid.");
        }
        for (OverDriveCard card : cards) {
            storeToken(card.cardId(), card.name(), card.libraryKey(), t);
        }
        log.info("Libby identity token linked for user {}: {} card(s)", currentUserId(), cards.size());
        return cards;
      }

      /**
       * Link a library card directly by card number + PIN (the Libby "add a library card" flow). Unlike
       * a setup-code clone — which yields a secondary chip that OverDrive refuses to fulfill Adobe DRM
       * for — this produces a <b>primary</b> chip that can fulfill. Steps mirror the web client:
       * anonymous chip → {@code POST /auth/link/{websiteId}} with the credentials → re-mint → sync.
       *
       * <p>When a credential key is configured the card number/PIN are stored (encrypted) so the token
       * can be silently re-linked on expiry; otherwise only the token is kept.
       *
       * @param libraryKey the OverDrive advantage key (e.g. "jocolibrary")
       * @return the linked cards
       */
      public List<OverDriveCard> linkCard(String libraryKey, String cardNumber, String pin) {
        String key = libraryKey != null ? libraryKey.trim() : "";
        String cn = cardNumber != null ? cardNumber.trim() : "";
        if (key.isEmpty() || cn.isEmpty()) {
            throw new RestClientException("Library and card number are required to link a card.");
        }
        String websiteId = overDriveParser.fetchWebsiteId(key);
        if (websiteId == null) {
            throw new RestClientException("Could not resolve OverDrive library '" + key + "'. Check the library key.");
        }
        String ilsName = fetchIlsName(websiteId);
        if (ilsName == null) {
            throw new RestClientException("Could not read the sign-in form for this library; card+PIN link unsupported.");
        }

        String token = requestChip().token();
        submitLocalAuthentication(websiteId, ilsName, cn, pin, token);
        token = refreshIdentity(token);

        List<OverDriveCard> cards = fetchCards(token);
        if (cards.isEmpty()) {
            throw new RestClientException("Card linked but no library card was returned; check the number and PIN.");
        }
        String encCard = credentialCipher.encrypt(cn);
        String encPin = credentialCipher.encrypt(pin);
        for (OverDriveCard card : cards) {
            storeToken(card.cardId(), card.name(), card.libraryKey(), token);
            storeCardCredentials(card.cardId(), websiteId, ilsName, encCard, encPin);
        }
        log.info("Libby card linked by number for user {}: {} card(s){}", currentUserId(), cards.size(),
                credentialCipher.isEnabled() ? " (credentials stored for auto-relink)" : "");
        return cards;
      }

      /** Read the library's local-auth ILS name from {@code GET /auth/forms/{websiteId}}, or null. */
      private String fetchIlsName(String websiteId) {
        try {
            String body = restClient.get()
                    .uri(sentryBaseUrl + "/auth/forms/" + websiteId)
                    .headers(h -> h.addAll(libbyHeaders(null)))
                    .retrieve()
                    .body(String.class);
            if (body == null) {
                return null;
            }
            // Pick the first non-ghost ilsName (the local sign-in form).
            var m = java.util.regex.Pattern.compile("\"ilsName\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            while (m.find()) {
                if (!"_ghost".equals(m.group(1))) {
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            log.warn("OverDrive: could not fetch auth forms for websiteId {}: {}", websiteId, e.getMessage());
        }
        return null;
      }

      /** POST /auth/link/{websiteId} with {ils, username, password} to link a card to the chip. */
      private void submitLocalAuthentication(String websiteId, String ilsName, String cardNumber, String pin,
                                             String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ils", ilsName);
        body.put("username", cardNumber);
        body.put("password", pin);
        HttpHeaders headers = libbyHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restClient.post()
                    .uri(sentryBaseUrl + "/auth/link/" + websiteId)
                    .headers(h -> h.addAll(headers))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("OverDrive card link failed for websiteId {}: {}", websiteId, e.getMessage());
            throw new RestClientException("OverDrive card link failed (check the card number and PIN): "
                    + e.getMessage());
        }
      }

      /** Persist the library ids + encrypted credentials onto the stored token row for a card. */
      @Transactional
      public void storeCardCredentials(String identity, String websiteId, String ilsName, String encCard,
                                       String encPin) {
        tokenRepository.findByUserIdAndIdentity(currentUserId(), identity).ifPresent(entity -> {
            entity.setWebsiteId(websiteId);
            entity.setIlsName(ilsName);
            entity.setCredCard(encCard);
            entity.setCredPin(encPin);
            tokenRepository.save(entity);
        });
      }

      /**
       * Silently re-link a card from its stored (encrypted) credentials to mint a fresh primary token,
       * updating the stored row. Returns the new token, or null when no usable credentials are stored
       * (no credential key configured, or card linked via setup code). Never throws.
       */
      /**
       * Refresh a card's stored token by re-linking from its stored credentials (card+PIN). Throws a
       * clear error when the card has no usable stored credentials (setup-code / pasted-token links, or
       * no credential key configured) — those should be cleared and re-linked instead.
       */
      public void refreshCard(String identity) {
        if (relinkCard(identity) == null) {
            throw new RestClientException("Couldn't refresh this card — it has no stored card+PIN "
                    + "credentials (set OVERDRIVE_CREDENTIAL_KEY and link by card + PIN), or re-linking "
                    + "failed. Unlink it and link again.");
        }
      }

      private String relinkCard(String cardId) {
        if (!credentialCipher.isEnabled()) {
            return null;
        }
        var row = tokenRepository.findByUserIdAndIdentity(currentUserId(), cardId).orElse(null);
        if (row == null || row.getCredCard() == null || row.getWebsiteId() == null || row.getIlsName() == null) {
            return null;
        }
        String cn = credentialCipher.decrypt(row.getCredCard());
        String pin = credentialCipher.decrypt(row.getCredPin());
        if (cn == null) {
            return null;
        }
        try {
            String token = requestChip().token();
            submitLocalAuthentication(row.getWebsiteId(), row.getIlsName(), cn, pin, token);
            token = refreshIdentity(token);
            row.setToken(token);
            row.setExpiresAt(Instant.now().getEpochSecond() + 3600);
            tokenRepository.save(row);
            log.info("OverDrive: re-linked card {} from stored credentials", cardId);
            return token;
        } catch (Exception e) {
            log.warn("OverDrive: auto-relink failed for card {}: {}", cardId, e.getMessage());
            return null;
        }
      }

      /** Read all library cards from a sync as {@link OverDriveCard}s (id + best-effort display name). */
      private List<OverDriveCard> fetchCards(String token) {
        List<OverDriveCard> cards = new ArrayList<>();
        try {
            Map<?, ?> body = restClient.get()
                    .uri(sentryBaseUrl + "/chip/sync")
                    .headers(h -> h.addAll(libbyHeaders(token)))
                    .retrieve()
                    .toEntity(Map.class)
                    .getBody();
            if (body != null && body.get("cards") instanceof List<?> rawCards) {
                for (Object raw : rawCards) {
                    if (raw instanceof Map<?, ?> card && card.get("cardId") != null) {
                        String libraryKey = advantageKey(card);
                        // Prefer the authoritative library name from Thunder; fall back to the sync payload.
                        String resolved = libraryKey != null ? overDriveParser.fetchLibraryName(libraryKey) : null;
                        String name = resolved != null ? resolved : cardDisplayName(card);
                        cards.add(new OverDriveCard(card.get("cardId").toString(), name, libraryKey, false));
                    }
                }
            }
         } catch (Exception e) {
            log.warn("OverDrive: could not read cards from sync: {}", e.getMessage());
         }
        return cards;
      }

      /** Best-effort friendly name for a card from the sync payload (library/advantage name, else the id). */
      private String cardDisplayName(Map<?, ?> card) {
        Object library = card.get("library");
        if (library instanceof Map<?, ?> lib && lib.get("name") != null) {
            return lib.get("name").toString();
        }
        Object advantageKey = card.get("advantageKey");
        if (advantageKey != null) {
            return advantageKey.toString();
        }
        Object name = card.get("cardName");
        return name != null ? name.toString() : card.get("cardId").toString();
      }

      /** The OverDrive library advantage key for a card (the Thunder library key), or null. */
      private String advantageKey(Map<?, ?> card) {
        Object advantageKey = card.get("advantageKey");
        if (advantageKey != null) {
            return advantageKey.toString();
        }
        if (card.get("library") instanceof Map<?, ?> lib && lib.get("advantageKey") != null) {
            return lib.get("advantageKey").toString();
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
        authToken = refreshIdentity(resolveToken(identity, authToken));
        byte[] body = fetchFulfillment(identity, authToken, loanId, FORMAT_EPUB_ADOBE);
        if (body == null || body.length == 0) {
            log.warn("OverDrive fulfill returned empty body for loan {}", loanId);
            return null;
        }

        // Mark loan as fulfilled
        loanRepository.findByOverdriveLoanIdAndIdentity(loanId, identity)
                .ifPresent(entity -> {
                    entity.setFulfilled(true);
                    loanRepository.save(entity);
                });

        log.info("OverDrive loan fulfilled: {} bytes for loan {}", body.length, loanId);
        return Base64.getEncoder().encodeToString(body);
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
                    // Apply headers via .headers(): RestClient does not unwrap an HttpEntity passed to
                    // .body(), so the bearer token / browser UA / Referer would otherwise be dropped
                    // and Sentry 403s the request.
                    .headers(h -> h.addAll(headers))
                    .body(body)
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
       * Fulfill a DRM-free open format (EPUB or PDF). Delegates to {@link #fetchFulfillment}, which
       * follows the fulfill → CDN redirect chain the way the endpoint requires.
       */
      private byte[] fulfillOpen(String cardId, String authToken, String loanId, String formatId) {
        return fetchFulfillment(cardId, authToken, loanId, formatId);
      }

      /** A resolved loan: the id to fulfill against and the fulfillment format ids it advertises. */
      private record LoanRef(String loanId, List<String> formatIds) {}

      /**
       * Look for a title already on loan for this card (via sync) so borrow-and-import can resume
       * without borrowing again — a prior attempt may have borrowed it but failed later. Returns null
       * when there is no matching active loan, or when it advertises no usable formats (the caller then
       * borrows normally). Never throws.
       */
      private LoanRef findActiveLoan(String cardId, String authToken, String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }
        try {
            OverDriveSyncResponse synced = sync(cardId, authToken);
            if (synced == null || synced.getLoans() == null) {
                return null;
            }
            for (OverDriveLoan l : synced.getLoans()) {
                if (!titleId.equals(l.getId())) {
                    continue;
                }
                List<String> fmts = new ArrayList<>();
                if (l.getFormats() != null) {
                    for (OverDriveFormat f : l.getFormats()) {
                        if (f.getId() != null && !fmts.contains(f.getId())) {
                            fmts.add(f.getId());
                        }
                    }
                }
                if (l.getFormat() != null && l.getFormat().getId() != null && !fmts.contains(l.getFormat().getId())) {
                    fmts.add(l.getFormat().getId());
                }
                // Found the loan but can't tell which format to fulfill — let the caller borrow instead.
                return fmts.isEmpty() ? null : new LoanRef(l.getId(), fmts);
            }
        } catch (Exception e) {
            log.warn("OverDrive: could not check for an existing loan on title {}: {}", titleId, e.getMessage());
        }
        return null;
      }

      // Plain JDK clients for the fulfill hop (Spring's RestClient trips the fulfillment WAF/redirects).
      private static final HttpClient FULFILL_CLIENT_FOLLOW = HttpClient.newBuilder()
              .followRedirects(HttpClient.Redirect.NORMAL)
              .connectTimeout(Duration.ofSeconds(30))
              .build();

      /** Browser-like Libby API request (Accept: application/json + Origin), optional bearer. */
      private static HttpRequest.Builder apiRequest(String url, String bearerToken) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Origin", ORIGIN)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        return b;
      }

      /**
       * Fetch a loan's fulfillment content (ACSM for Adobe, or the file for open formats), following
       * the Libby web client's contract verified against a live browser capture:
       * <ol>
       *   <li>{@code GET card/{card}/loan/{loan}/fulfill/{format}} with {@code Accept: application/json}
       *       + {@code Origin}.</li>
       *   <li>A {@code 403 {"result":"missing_chip"}} means the chip isn't registered yet: re-mint it
       *       ({@link #refreshIdentity}) and retry once.</li>
       *   <li>A {@code 403 {"result":"whoa"}} = the server refused fulfillment: fail fast (no retry) and
       *       log the full response (a {@code Retry-After} header, if present, would confirm rate-limiting).</li>
       *   <li>On success the JSON body carries {@code fulfill.href} — a pre-signed content URL we then
       *       GET (without auth) to obtain the bytes.</li>
       * </ol>
       */
      private byte[] fetchFulfillment(String cardId, String authToken, String loanId, String formatId) {
        String url = sentryBaseUrl + "/card/" + cardId + "/loan/" + loanId + "/fulfill/" + formatId;
        try {
            HttpResponse<byte[]> resp = FULFILL_CLIENT_FOLLOW.send(
                    apiRequest(url, authToken).build(), HttpResponse.BodyHandlers.ofByteArray());
            String body = resp.body() != null ? new String(resp.body(), StandardCharsets.UTF_8) : "";
            String result = firstMatch(RESULT_PATTERN, body);
            log.info("OverDrive fulfill {} loan {}: status {}{}", formatId, loanId, resp.statusCode(),
                    result != null ? " result=" + result : "");

            if ("missing_chip".equals(result)) {
                // Chip not yet registered for fulfillment — re-mint and retry once (as the web client does).
                String refreshed = refreshIdentity(authToken);
                resp = FULFILL_CLIENT_FOLLOW.send(
                        apiRequest(url, refreshed).build(), HttpResponse.BodyHandlers.ofByteArray());
                body = resp.body() != null ? new String(resp.body(), StandardCharsets.UTF_8) : "";
                result = firstMatch(RESULT_PATTERN, body);
                log.info("OverDrive fulfill {} loan {} (retry): status {}{}", formatId, loanId, resp.statusCode(),
                        result != null ? " result=" + result : "");
            }

            if ("missing_chip".equals(result)) {
                // Still unregistered — the stored token/chip is likely dead. Re-link from stored
                // credentials (if available) to mint a fresh primary token, then retry once more.
                String relinked = relinkCard(cardId);
                if (relinked != null) {
                    resp = FULFILL_CLIENT_FOLLOW.send(
                            apiRequest(url, relinked).build(), HttpResponse.BodyHandlers.ofByteArray());
                    body = resp.body() != null ? new String(resp.body(), StandardCharsets.UTF_8) : "";
                    result = firstMatch(RESULT_PATTERN, body);
                    log.info("OverDrive fulfill {} loan {} (post-relink): status {}{}", formatId, loanId,
                            resp.statusCode(), result != null ? " result=" + result : "");
                }
            }

            if ("whoa".equals(result)) {
                // Capture the response detail so we can tell what "whoa" actually is: a Retry-After
                // header would confirm rate-limiting; its absence points elsewhere (loan state, etc.).
                String retryAfter = resp.headers().firstValue("retry-after").orElse(null);
                String reqId = resp.headers().firstValue("x-request-id").orElse(null);
                String date = resp.headers().firstValue("date").orElse(null);
                log.warn("OverDrive fulfill \"whoa\" — loan {}, format {}, status {}; retry-after={}, "
                        + "x-request-id={}, date={}; body={}; headers={}",
                        loanId, formatId, resp.statusCode(), retryAfter, reqId, date, body, resp.headers().map());
                throw new RestClientException("OverDrive refused this fulfillment (\"whoa\") for loan " + loanId
                        + (retryAfter != null ? " (Retry-After: " + retryAfter + ")" : "")
                        + ". Wait and retry, or try a freshly-borrowed title; see the server log for details.");
            }

            String href = firstMatch(HREF_PATTERN, body);
            if (href != null && !href.isBlank()) {
                return downloadContent(href, loanId);
            }
            // Some formats may return the bytes directly rather than a JSON href.
            if (resp.statusCode() < 300 && resp.body() != null && resp.body().length > 0 && !body.startsWith("{")) {
                return resp.body();
            }
            throw new RestClientException("OverDrive fulfill failed (status " + resp.statusCode()
                    + (result != null ? ", result=" + result : "") + ") for loan " + loanId + bodySnippet(resp.body()));
        } catch (IOException e) {
            log.error("OverDrive fulfill IO error for loan {}: {}", loanId, e.getMessage());
            throw new RestClientException("OverDrive fulfill failed for loan " + loanId + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestClientException("OverDrive fulfill interrupted for loan " + loanId);
        }
      }

      /** GET a pre-signed fulfillment content URL (ACSM/open file) without auth; returns the bytes. */
      private byte[] downloadContent(String href, String loanId) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(href))
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<byte[]> resp = FULFILL_CLIENT_FOLLOW.send(req, HttpResponse.BodyHandlers.ofByteArray());
        log.info("OverDrive content download ({}): status {}", safeHost(href), resp.statusCode());
        if (resp.statusCode() >= 400 || resp.body() == null || resp.body().length == 0) {
            throw new RestClientException("OverDrive content download failed (" + resp.statusCode()
                    + ") for loan " + loanId);
        }
        return resp.body();
      }

      private static String firstMatch(java.util.regex.Pattern p, String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        var m = p.matcher(s);
        return m.find() ? m.group(1) : null;
      }

      /** A short, single-line snippet of a response body for error messages (empty string if blank). */
      private static String bodySnippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
        if (text.isBlank()) {
            return "";
        }
        return ": " + (text.length() > 300 ? text.substring(0, 300) + "…" : text);
      }

      private static String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "?";
        }
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
       * Search the OverDrive catalog for borrowable titles across the admin-configured library and any
       * libraries the current user has cards for. Backed by the read-only Thunder catalog API (no auth);
       * results carry the title id needed to {@link #borrowAndImport}. Deduplicated by title id.
       */
      public List<OverDriveCatalogItem> searchCatalog(String query) {
        // Admin library first, then the user's card libraries (distinct, order-preserving).
        Set<String> libraryKeys = new LinkedHashSet<>();
        String adminKey = adminLibraryKey();
        if (adminKey != null && !adminKey.isBlank()) {
            libraryKeys.add(adminKey);
        }
        libraryKeys.addAll(userLibraryKeys());

        Map<String, OverDriveCatalogItem> byTitleId = new LinkedHashMap<>();
        for (String libraryKey : libraryKeys) {
            for (OverDriveApiResponse.Item item : overDriveParser.searchLibrary(libraryKey, query)) {
                OverDriveCatalogItem mapped = toCatalogItem(item);
                if (mapped.title() == null || mapped.title().isBlank() || mapped.titleId() == null) {
                    continue;
                }
                // Dedupe across libraries by title id, but prefer a copy that is available to borrow
                // now over an identical title that is only holdable elsewhere.
                OverDriveCatalogItem existing = byTitleId.get(mapped.titleId());
                if (existing == null || (!existing.available() && mapped.available())) {
                    byTitleId.put(mapped.titleId(), mapped);
                }
            }
        }
        return new ArrayList<>(byTitleId.values());
      }

      /**
       * Resolve an OverDrive library key against the Thunder library directory to validate it and
       * obtain the library's display name. Read-only, no auth. A blank key or one that does not resolve
       * yields {@code valid=false}.
       */
      public OverDriveLibraryResolution resolveLibrary(String libraryKey) {
        String key = libraryKey != null ? libraryKey.trim() : "";
        if (key.isEmpty()) {
            return new OverDriveLibraryResolution(false, key, null);
        }
        String name = overDriveParser.fetchLibraryName(key);
        return new OverDriveLibraryResolution(name != null, key, name);
      }

      /**
       * A passive, read-only diagnostics snapshot of what has already happened: configuration/feature
       * flags, the current user's linked cards (with the chip identity decoded from the stored token —
       * primary vs secondary, account group, credential storage), and the locally-recorded loans. It
       * makes <b>no</b> live Libby calls and takes no input, so it never triggers fulfillment or
       * re-minting (and can't contribute to rate-limiting). Never throws.
       */
      public Map<String, Object> diagnose() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sentryBaseUrl", sentryBaseUrl);
        r.put("clientId", clientId);
        r.put("adminLibraryKey", adminLibraryKey());
        r.put("formatPreference", formatPreference());
        r.put("acsmHandlerConfigured", acsmHandler.isConfigured());
        r.put("credentialStorageEnabled", credentialCipher.isEnabled());

        Long userId = currentUserId();

        List<Map<String, Object>> cards = new ArrayList<>();
        for (OverDriveTokenEntity t : tokenRepository.findByUserId(userId)) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("cardId", t.getIdentity());
            cm.put("name", t.getCardName());
            cm.put("libraryKey", t.getLibraryKey());
            cm.put("websiteId", t.getWebsiteId());
            cm.put("ilsName", t.getIlsName());
            cm.put("hasToken", t.getToken() != null && !t.getToken().isBlank());
            cm.put("tokenLength", t.getToken() != null ? t.getToken().length() : 0);
            cm.put("expiresAt", t.getExpiresAt());
            cm.put("credentialsStored", t.getCredCard() != null);
            cm.put("chip", chipSummary(t.getToken()));
            cards.add(cm);
        }
        r.put("cards", cards);

        List<Map<String, Object>> loans = new ArrayList<>();
        for (OverDriveLoanEntity l : loanRepository.findByUserId(userId)) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("loanId", l.getOverdriveLoanId());
            lm.put("title", l.getTitle());
            lm.put("author", l.getAuthor());
            lm.put("state", l.getState());
            lm.put("formatId", l.getFormatId());
            lm.put("fulfilled", l.getFulfilled());
            lm.put("bookId", l.getBookId());
            lm.put("expireDate", l.getExpireDate() != null ? l.getExpireDate().toString() : null);
            lm.put("lastSync", l.getLastSync() != null ? l.getLastSync().toString() : null);
            loans.add(lm);
        }
        r.put("loans", loans);
        return r;
      }

      /**
       * Decode the chip identity from a stored JWT (no network): whether it's a primary chip
       * ({@code pri == id}, fulfillment-capable) vs a secondary, its account group, and whether the
       * token carries cards. Purely reports the token's current state.
       */
      private Map<String, Object> chipSummary(String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (token == null || token.isBlank()) {
            return m;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return m;
            }
            String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])), StandardCharsets.UTF_8);
            String id = firstMatch(CHIP_ID_PATTERN, payload);
            String pri = firstMatch(java.util.regex.Pattern.compile("\"pri\"\\s*:\\s*\"([^\"]+)\""), payload);
            String ag = firstMatch(java.util.regex.Pattern.compile("\"ag\"\\s*:\\s*(null|\\d+)"), payload);
            String prbn = firstMatch(java.util.regex.Pattern.compile("\"prbn\"\\s*:\\s*\"([^\"]+)\""), payload);
            m.put("chipId", id);
            m.put("primary", id != null && id.equals(pri));
            m.put("accountGroup", "null".equals(ag) ? null : ag);
            m.put("hasCards", payload.matches("(?s).*\"cards\"\\s*:\\s*\\[\\[.*"));
            // prbn is the chip provenance: "i" = identity-only (browse/borrow; can hand back an ebook
            // ACSM but NOT fulfill audiobooks), "v" = bona-fide (mints via the real Libby web app;
            // fully fulfillment-capable). See docs/OverDrive-Testing.md.
            m.put("prbn", prbn);
            m.put("fulfillmentCapable", "v".equals(prbn));
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
      }

      /** Whether card credentials can be stored (auto-relink); false unless a credential key is set. */
      public boolean credentialStorageEnabled() {
        return credentialCipher.isEnabled();
      }

      /** The admin-configured OverDrive library key from metadata provider settings, or null. */
      private String adminLibraryKey() {
        var appSettings = appSettingService.getAppSettings();
        MetadataProviderSettings settings = appSettings != null ? appSettings.getMetadataProviderSettings() : null;
        return settings != null && settings.getOverdrive() != null ? settings.getOverdrive().getLibraryKey() : null;
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
        // Re-mint to a card-bound identity so the fulfill step is authorized (see refreshIdentity).
        authToken = refreshIdentity(resolveToken(identity, authToken));

        // Resume an already-borrowed title rather than borrowing again: a prior attempt may have
        // borrowed the title but failed at fulfill/import, leaving the loan (and a consumed checkout
        // slot) in place. Borrowing is not automatically retried; we only pick up the existing loan.
        LoanRef loan = findActiveLoan(identity, authToken, titleId);
        if (loan != null) {
            log.info("OverDrive: resuming existing loan {} for title {} (skipping re-borrow)", loan.loanId(), titleId);
        } else {
            Map<String, Object> borrowed = borrowLoan(identity, authToken, titleId);
            loan = new LoanRef(borrowed.get("id").toString(), loanFormatIds(borrowed));
        }
        String loanId = loan.loanId();
        List<String> formats = loan.formatIds();

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
                extractIsbn(item),
                Boolean.TRUE.equals(item.getAvailable()),
                Boolean.TRUE.equals(item.getHoldable()),
                item.getAvailableCopies(),
                item.getOwnedCopies(),
                item.getHoldsCount(),
                item.getEstimatedWaitDays(),
                Boolean.TRUE.equals(item.getPreRelease()));
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
               return fetchFulfillment(identity, authToken, loanId, formatId);
             }

            // ── Persistence Helpers ──────────────────────────────────────────────
      /**
       * GET /card/{cardId}/hold/{formatId} — place a hold.
       */
      public void placeHold(String identity, String authToken, String titleId) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/card/" + identity + "/hold/" + titleId;
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            restClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toBodilessEntity();

            log.info("OverDrive hold placed for title {}", titleId);
         } catch (Exception e) {
            log.error("OverDrive hold failed: {}", e.getMessage());
            throw new RestClientException("OverDrive hold failed: " + e.getMessage());
         }
      }

      /**
       * Cancel a hold on a title.
       */
      public void cancelHold(String identity, String authToken, String titleId) {
        authToken = resolveToken(identity, authToken);
        String url = sentryBaseUrl + "/card/" + identity + "/hold/" + titleId;
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            restClient.delete()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toBodilessEntity();

            log.info("OverDrive hold cancelled for title {}", titleId);
         } catch (Exception e) {
            log.error("OverDrive cancel hold failed: {}", e.getMessage());
            throw new RestClientException("OverDrive cancel hold failed: " + e.getMessage());
         }
      }

     // ── Token Management ─────────────────────────────────────────────────

      /** Store (or replace) the current user's token for a specific card. Persisted across restarts. */
      @Transactional
      public void storeToken(String identity, String cardName, String libraryKey, String token) {
        Long userId = currentUserId();
        OverDriveTokenEntity entity = tokenRepository.findByUserIdAndIdentity(userId, identity)
                .orElseGet(OverDriveTokenEntity::new);
        entity.setUserId(userId);
        entity.setIdentity(identity);
        entity.setCardName(cardName);
        entity.setLibraryKey(libraryKey);
        entity.setToken(token);
        entity.setExpiresAt(Instant.now().getEpochSecond() + 3600);
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        tokenRepository.save(entity);
        log.info("OverDrive token stored for user {} (card {})", userId, identity);
      }

      /** Remove the current user's stored token for a specific card. */
      @Transactional
      public void removeToken(String identity) {
        tokenRepository.deleteByUserIdAndIdentity(currentUserId(), identity);
        log.info("OverDrive card {} removed for user {}", identity, currentUserId());
      }

      /** Whether the current user has a stored token for the given card. */
      public boolean hasToken(String identity) {
        return tokenRepository.existsByUserIdAndIdentity(currentUserId(), identity);
      }

      /** The current user's linked cards (id + display name + library key). */
      public List<OverDriveCard> listCards() {
        return tokenRepository.findByUserId(currentUserId()).stream()
                .map(t -> new OverDriveCard(t.getIdentity(), t.getCardName(), t.getLibraryKey(), t.getCredCard() != null))
                .toList();
      }

      /** Distinct OverDrive library keys for the current user's linked cards. */
      private List<String> userLibraryKeys() {
        return tokenRepository.findByUserId(currentUserId()).stream()
                .map(OverDriveTokenEntity::getLibraryKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .toList();
      }

      /** The current user's linked card ids. */
      public List<String> listIdentities() {
        return tokenRepository.findByUserId(currentUserId()).stream()
                .map(OverDriveTokenEntity::getIdentity)
                .toList();
      }

      /** The current user's stored token for a specific card, or null. */
      public String getStoredToken(String identity) {
        return tokenRepository.findByUserIdAndIdentity(currentUserId(), identity)
                .map(OverDriveTokenEntity::getToken)
                .orElse(null);
      }

      /** A specific (user, card) stored token, or null. Used by background tasks with no request user. */
      public String getStoredToken(Long userId, String identity) {
        if (userId == null || identity == null) {
            return null;
        }
        return tokenRepository.findByUserIdAndIdentity(userId, identity)
                .map(OverDriveTokenEntity::getToken)
                .orElse(null);
      }

      /**
       * Resolve the token to use for a card: the caller-supplied token when present, otherwise the
       * current user's stored token for that card. Throws if neither is available.
       */
      private String resolveToken(String identity, String providedToken) {
        if (providedToken != null && !providedToken.isBlank()) {
            return providedToken;
        }
        String stored = getStoredToken(identity);
        if (stored == null || stored.isBlank()) {
            throw new RestClientException(
                    "No OverDrive token available for card " + identity + "; connect your Libby account first.");
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