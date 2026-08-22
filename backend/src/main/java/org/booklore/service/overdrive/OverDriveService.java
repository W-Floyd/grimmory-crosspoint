package org.booklore.service.overdrive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.overdrive.*;
import org.booklore.model.dto.response.OverDriveApiResponse;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.OverDriveAuditEntity;
import org.booklore.model.entity.OverDriveBookbagEntity;
import org.booklore.model.entity.OverDriveCardLimitEntity;
import org.booklore.model.entity.OverDriveAutoSyncEntity;
import org.booklore.model.entity.OverDriveCardShareEntity;
import org.booklore.model.entity.OverDriveImportDestinationEntity;
import org.booklore.model.entity.OverDriveLoanEntity;
import org.booklore.model.entity.OverDriveTokenEntity;
import org.booklore.model.enums.OverDriveAuditAction;
import org.booklore.model.enums.BookFileType;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.repository.BookRepository;
import org.booklore.repository.OverDriveAuditRepository;
import org.booklore.repository.OverDriveAutoSyncRepository;
import org.booklore.repository.OverDriveCardShareRepository;
import org.booklore.repository.OverDriveImportDestinationRepository;
import org.booklore.repository.OverDriveLoanRepository;
import org.booklore.repository.OverDriveTokenRepository;
import org.booklore.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.booklore.service.acsm.AcsmHandler;
import org.booklore.model.websocket.Topic;
import org.booklore.service.audiobook.AudiobookHandler;
import org.booklore.service.ebook.EbookHandler;
import org.booklore.service.magazine.MagazineHandler;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.OverDriveItemExtractor;
import org.booklore.service.metadata.parser.OverDriveParser;
import org.booklore.util.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final BookRepository bookRepository;
    private final AcsmHandler acsmHandler;
    private final AudiobookHandler audiobookHandler;
    private final MagazineHandler magazineHandler;
    private final EbookHandler ebookHandler;
    private final org.booklore.service.book.BookService bookService;

     @Value("${app.overdrive.sentry-base-url:https://sentry.libbyapp.com}")
    private String sentryBaseUrl;

     @Value("${app.overdrive.client-id:dewey}")
    private String clientId;

    private final RestClient restClient;
    private final OverDriveImportService overDriveImportService;
    private final OverDriveParser overDriveParser;
    private final OverDriveTokenRepository tokenRepository;
    private final OverDriveCardShareRepository cardShareRepository;
    private final OverDriveAuditRepository auditRepository;
    private final org.booklore.repository.OverDriveCardLimitRepository cardLimitRepository;
    private final org.booklore.repository.OverDriveBookbagRepository bookbagRepository;
    private final OverDriveImportDestinationRepository importDestinationRepository;
    private final OverDriveAutoSyncRepository autoSyncRepository;
    /**
     * The fulfill hop uses a plain JDK client rather than Spring's {@link RestClient}, which trips the
     * fulfillment WAF and mishandles its redirects. This is the application-wide {@code HttpClient}
     * bean (HTTP/2, virtual-thread executor, 10s connect timeout, follows redirects) rather than one
     * built here, so OverDrive picks up the same outbound settings as every other integration.
     */
    private final HttpClient httpClient;
    private final UserRepository userRepository;
    private final AuthenticationService authenticationService;
    private final AppSettingService appSettingService;
    private final OverDriveCredentialCipher credentialCipher;
    private final org.booklore.service.NotificationService notificationService;
    private final org.booklore.service.book.BookFileAttachmentService bookFileAttachmentService;

    /**
     * Titles currently being borrow-and-imported, keyed {@code userId:titleId} — see
     * {@link #borrowAndImport}. Node-local: Grimmory is a single instance, so a plain in-memory set is
     * the right scope (a DB lock would only matter behind a multi-instance deployment).
     */
    private final Set<String> importsInFlight = ConcurrentHashMap.newKeySet();

    /**
     * Set for the duration of one user's automation pass, so history entries written by the ordinary
     * borrow/import/return paths can be marked as unattended.
     *
     * <p>A thread-local rather than a parameter: the automation deliberately reuses the interactive
     * methods (same destinations, same format rules, same history), and threading a flag through
     * {@code borrowAndImport}'s eleven arguments to reach {@code recordAudit} would be far more
     * invasive than the thing it records. Each user's pass runs on its own thread, and the flag is
     * always cleared in a finally.
     */
    private final ThreadLocal<Boolean> automationInProgress = ThreadLocal.withInitial(() -> false);

    /** Whether the current thread is inside an automation pass. */
    private boolean isAutomated() {
        return Boolean.TRUE.equals(automationInProgress.get());
    }

    /** The authenticated Grimmory user, or throws if there is no authenticated user. */
    private BookLoreUser currentUser() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getId() == null) {
            throw ApiError.GENERIC_UNAUTHORIZED.createException("No authenticated user for OverDrive operation");
        }
        return user;
    }

    /** The authenticated Grimmory user's id, or throws if there is no authenticated user. */
    private Long currentUserId() {
        return currentUser().getId();
    }

    /**
     * A sink that streams each line of an external handler's output to the current user's OverDrive
     * tool-log websocket topic (labelled with the title id), so the UI can show live progress. Resolves
     * the username now (on the request thread) since the handler drains on a background thread without a
     * security context. Returns null when there's no authenticated user.
     */
    private java.util.function.Consumer<String> toolLogSink(String titleId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getUsername() == null) {
            return null;
        }
        String username = user.getUsername();
        String label = titleId == null ? "" : titleId;
        return line -> notificationService.sendMessageToUser(username, Topic.OVERDRIVE_TOOL_LOG,
                toolLogPayload(label, line));
    }

    /** Lenient reader for handler progress events; unknown fields are tolerated (forward-compat). */
    private static final com.fasterxml.jackson.databind.ObjectMapper TOOL_EVENT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Build the websocket payload for one handler stdout line. If the line is a structured
     * progress event (go-od progress protocol, docs/progress-protocol.md) it is forwarded as
     * {@code {titleId, event}}; otherwise it is forwarded verbatim as {@code {titleId, line}}.
     */
    private static Map<String, Object> toolLogPayload(String label, String line) {
        Map<String, Object> event = parseToolEvent(line);
        if (event != null) {
            return Map.of("titleId", label, "event", event);
        }
        return Map.of("titleId", label, "line", line == null ? "" : line);
    }

    /**
     * Parse a single handler stdout line as a structured progress event: a one-line JSON object
     * with a recognised {@code type} ({@code progress}, {@code log} or {@code result}). Returns
     * null for anything else — plain text, merged stderr, or JSON with an unknown/absent type —
     * so the caller falls back to a verbatim log line. Never throws.
     */
    static Map<String, Object> parseToolEvent(String line) { // package-private for testing
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        // Cheap guard: only object-shaped lines can be events; skip Jackson otherwise.
        if (trimmed.length() < 2 || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}') {
            return null;
        }
        try {
            Map<String, Object> map = TOOL_EVENT_MAPPER.readValue(trimmed,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            Object type = map.get("type");
            if (type instanceof String t && (t.equals("progress") || t.equals("log") || t.equals("result"))) {
                return map;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the current user is an administrator. */
    private boolean currentUserIsAdmin() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        return user != null && user.getPermissions() != null && user.getPermissions().isAdmin();
    }

    /**
     * Whether the current user may administer cards they do not own — unlink, refresh, relabel, set a
     * default library, and list every user's cards. Admins always can; everyone else needs the explicit
     * permission. Without it a user is confined to their own (and shared-to-them) cards, which is the
     * default for an ordinary OverDrive user.
     */
    private boolean currentUserCanManageAllCards() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getPermissions() == null) {
            return false;
        }
        return user.getPermissions().isAdmin() || user.getPermissions().isCanManageAllOverdriveCards();
    }

    /**
     * Whether the current user may manage sharing on cards they do not own. Full card administration
     * is a superset, so it grants this too.
     */
    private boolean currentUserCanManageAllShares() {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        if (user == null || user.getPermissions() == null) {
            return false;
        }
        return currentUserCanManageAllCards() || user.getPermissions().isCanManageAllOverdriveShares();
    }

    /**
     * Resolve a specific owner's card row for administration. A null {@code ownerUserId} means the
     * caller's own card; targeting another user's row requires the cross-user card permission. Unlike
     * {@link #accessibleTokenRow}, a card merely shared with the caller is not theirs to administer.
     */
    private OverDriveTokenEntity administrableCard(String identity, Long ownerUserId) {
        Long me = currentUserId();
        Long target = ownerUserId != null ? ownerUserId : me;
        if (!target.equals(me) && !currentUserCanManageAllCards()) {
            throw ApiError.FORBIDDEN.createException("You can only manage your own OverDrive cards");
        }
        return tokenRepository.findByUserIdAndIdentity(target, identity)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No such card: " + identity));
    }

    /**
     * The user a card write belongs to: the caller by default, or another user for a manager acting on
     * their behalf. Unlike {@link #administrableCard} this doesn't require the row to exist yet, so it's
     * what the link flows use when creating one.
     */
    private Long resolveCardOwner(Long ownerUserId) {
        Long me = currentUserId();
        if (ownerUserId == null || ownerUserId.equals(me)) {
            return me;
        }
        if (!currentUserCanManageAllCards()) {
            throw ApiError.FORBIDDEN.createException("You can only link OverDrive cards for yourself");
        }
        if (!userRepository.existsById(ownerUserId)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("No such user: " + ownerUserId);
        }
        return ownerUserId;
    }

    /** Audit/log suffix naming the card owner, for actions taken on someone else's card. */
    private String onBehalfOfSuffix(Long ownerUserId) {
        Long me = currentUserId();
        return ownerUserId == null || ownerUserId.equals(me) ? "" : " (card owned by " + ownerName(ownerUserId) + ")";
    }

    // ── Card access resolution (owned OR shared-to-you) ──────────────────

    /**
     * The card rows the given user can borrow/hold/view with: the ones they own, plus any another user
     * has shared with them. Owned rows come first. Sharing grants access to the owner's existing token
     * row (never a copy), so token refresh keeps working for every sharee.
     */
    private List<OverDriveTokenEntity> accessibleTokenRows(Long userId) {
        List<OverDriveTokenEntity> rows = new ArrayList<>(tokenRepository.findByUserId(userId));
        for (OverDriveCardShareEntity share : cardShareRepository.findBySharedWithUserId(userId)) {
            tokenRepository.findById(share.getTokenId()).ifPresent(rows::add);
        }
        return rows;
    }

    /**
     * Resolve one accessible card row by its OverDrive identity: the user's own row if present,
     * otherwise a row shared with them. Empty when neither applies.
     */
    private Optional<OverDriveTokenEntity> accessibleTokenRow(Long userId, String identity) {
        Optional<OverDriveTokenEntity> owned = tokenRepository.findByUserIdAndIdentity(userId, identity);
        if (owned.isPresent()) {
            return owned;
        }
        return cardShareRepository.findBySharedWithUserId(userId).stream()
                .map(s -> tokenRepository.findById(s.getTokenId()).orElse(null))
                .filter(t -> t != null && identity.equals(t.getIdentity()))
                .findFirst();
    }

    // ── Activity history (per-user) ──────────────────────────────────────

    /** Cap on how many recent history entries the History tab loads. */
    /**
     * Largest page of history that may be requested at once. The history is no longer capped at a
     * fixed newest-N — the automation writes entries unattended, so a cap would quietly bury the
     * user's own actions — but a page size still needs bounding so one request cannot ask for
     * everything.
     */
    private static final int HISTORY_MAX_PAGE_SIZE = 200;
    private static final int HISTORY_DEFAULT_PAGE_SIZE = 50;

    /**
     * Record one OverDrive history entry, best-effort — never throws, so it can't break the action it
     * describes. Card name/library key are snapshotted; a missing title is backfilled from the loan
     * cache by loan id when possible.
     */
    private void recordAudit(OverDriveAuditAction action, String identity, String titleId, String loanId,
                             Long bookId, String title, String detail) {
        recordAudit(action, identity, titleId, loanId, bookId, title, detail, true);
    }

    /**
     * Record a history entry against a specific user rather than the caller — for an action a manager
     * performed on someone else's behalf. It belongs in the owner's history (it's their card), with the
     * acting manager named in the detail.
     */
    private void recordAuditForUser(Long userId, OverDriveAuditAction action, String identity, String detail) {
        try {
            OverDriveTokenEntity card = identity == null ? null
                    : tokenRepository.findByUserIdAndIdentity(userId, identity).orElse(null);
            auditRepository.save(OverDriveAuditEntity.builder()
                    .userId(userId)
                    .action(action.name())
                    .identity(identity)
                    .libraryKey(card != null ? card.getLibraryKey() : null)
                    .cardName(card != null ? card.getCardName() : null)
                    .detail(truncate(detail, 1024))
                    .success(true)
                    .automated(isAutomated())
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.debug("OverDrive: could not record history entry for user {}: {}", userId, e.getMessage());
        }
    }

    /** Record a failed action to the history (the detail should carry the reason). */
    private void recordAuditFailure(OverDriveAuditAction action, String identity, String titleId, String loanId,
                                    String reason) {
        recordAuditFailure(action, identity, titleId, loanId, null, reason);
    }

    /**
     * As above, naming the title. A failure is the row a user most wants to read later, and one that
     * says only "3217022 failed" makes them go and look the number up.
     */
    private void recordAuditFailure(OverDriveAuditAction action, String identity, String titleId, String loanId,
                                    String title, String reason) {
        recordAudit(action, identity, titleId, loanId, null, title, reason, false);
    }

    /**
     * A display name for an OverDrive title, for history rows that would otherwise show a bare id.
     *
     * <p>Tries the library first — free, and the name the user already knows the book by — then the
     * public catalog. Best-effort throughout: a title is decoration on an audit row, so a lookup that
     * fails or is slow must never take the action down with it.
     */
    private String titleOf(String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }
        try {
            List<Object[]> known = bookRepository.findOverdriveIdTitlePairs(Set.of(titleId));
            if (!known.isEmpty() && known.getFirst().length == 2 && known.getFirst()[1] instanceof String title) {
                return title;
            }
            BookMetadata metadata = overDriveParser.fetchTitleMetadata(titleId);
            return metadata != null ? metadata.getTitle() : null;
        } catch (Exception e) {
            log.debug("OverDrive: could not resolve a title for {} ({}); the history row keeps the id",
                    titleId, e.getMessage());
            return null;
        }
    }

    private void recordAudit(OverDriveAuditAction action, String identity, String titleId, String loanId,
                             Long bookId, String title, String detail, boolean success) {
        try {
            Long userId = currentUserId();
            String libraryKey = null;
            String cardName = null;
            if (identity != null) {
                OverDriveTokenEntity card = accessibleTokenRow(userId, identity).orElse(null);
                if (card != null) {
                    libraryKey = card.getLibraryKey();
                    cardName = card.getCardName();
                }
            }
            String resolvedTitle = title;
            if ((resolvedTitle == null || resolvedTitle.isBlank()) && loanId != null) {
                resolvedTitle = loanRepository.findByUserIdAndOverdriveLoanId(userId, loanId)
                        .map(OverDriveLoanEntity::getTitle).orElse(null);
            }
            auditRepository.save(OverDriveAuditEntity.builder()
                    .userId(userId)
                    .action(action.name())
                    .identity(identity)
                    .libraryKey(libraryKey)
                    .cardName(cardName)
                    .titleId(titleId)
                    .loanId(loanId)
                    .bookId(bookId)
                    .title(truncate(resolvedTitle, 1024))
                    .detail(truncate(detail, 1024))
                    .success(success)
                    .automated(isAutomated())
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.debug("OverDrive: could not record history for {}: {}", action, e.getMessage());
        }
    }

    /** Write a trivial history entry, so tests can observe how the automation flag is applied. */
    void recordAuditForTest() { // package-private for testing
        recordAudit(OverDriveAuditAction.RETURN, null, null, null, null, null, null);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * One page of the current user's OverDrive activity, newest first.
     *
     * <p>Page and size are clamped rather than rejected: a paginator asking for a page past the end
     * (after entries were trimmed, say) should get an empty page, not an error.
     */
    public OverDriveHistoryPage listHistory(Integer page, Integer size) {
        int pageIndex = page == null ? 0 : Math.max(0, page);
        int pageSize = size == null ? HISTORY_DEFAULT_PAGE_SIZE
                : Math.min(HISTORY_MAX_PAGE_SIZE, Math.max(1, size));
        var result = auditRepository.findByUserIdOrderByCreatedAtDesc(
                currentUserId(), PageRequest.of(pageIndex, pageSize));
        Map<String, String> titlesById = titlesForRowsMissingOne(result.getContent());
        List<OverDriveAuditEntry> entries = result.getContent()
                .stream()
                .map(a -> new OverDriveAuditEntry(a.getId(), a.getAction(), a.getIdentity(), a.getLibraryKey(),
                        a.getCardName(), a.getTitleId(), a.getLoanId(), a.getBookId(),
                        a.getTitle() != null && !a.getTitle().isBlank()
                                ? a.getTitle()
                                : titlesById.get(a.getTitleId()),
                        a.getDetail(),
                        a.isSuccess(), a.isAutomated(),
                        a.getCreatedAt() != null ? a.getCreatedAt().toString() : null))
                .toList();
        return new OverDriveHistoryPage(entries, pageIndex, pageSize, result.getTotalElements());
    }

    /**
     * Titles for the rows on this page that recorded only an OverDrive id, looked up from the library.
     *
     * <p>Holds and failed borrows used to be written with no title, leaving the history showing a bare
     * number. Those rows are already on disk and cannot be rewritten, but an id is an id: if the title
     * was ever imported, the library knows what it is called. One query per page, not per row.
     *
     * @return OverDrive id to title, for ids the library can name
     */
    private Map<String, String> titlesForRowsMissingOne(List<OverDriveAuditEntity> rows) {
        Set<String> unnamed = rows.stream()
                .filter(a -> a.getTitle() == null || a.getTitle().isBlank())
                .map(OverDriveAuditEntity::getTitleId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (unnamed.isEmpty()) {
            return Map.of();
        }
        Map<String, String> titles = new HashMap<>();
        for (Object[] pair : bookRepository.findOverdriveIdTitlePairs(unnamed)) {
            if (pair.length == 2 && pair[0] instanceof String id && pair[1] instanceof String title) {
                titles.putIfAbsent(id, title);
            }
        }
        return titles;
    }

    // Mirror the Libby web client exactly (verified against a working browser HAR): a normal desktop
    // browser UA, plus Accept: application/json and Origin on API calls. The fulfill endpoint returns
    // JSON ({"fulfill":{"href":...}}), 403 {"result":"missing_chip"} until the chip is re-minted for
    // this loan (recover by re-minting and retrying), and 403 {"result":"whoa"} when fulfilling with an
    // identity the /chip re-mint stamped prbn=v. prbn=v happens when the re-mint lacked the correct
    // Accept-Language "shibboleth" the web client computes (see refreshIdentity/chipShibboleth); a
    // re-mint carrying the right shibboleth returns a prbn-absent identity that fulfills. So "whoa" is
    // a signal our shibboleth was wrong/missing — not a rate limit — and no retry/cooldown helps.
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

    /** OverDrive audiobook format id preferred by the handoff (Libby's MP3 audiobook). */
    private static final String FORMAT_AUDIOBOOK_MP3 = "audiobook-mp3";

    /**
     * Libby's "read in browser" ebook format. Not a downloadable file — the book is served as
     * web-reader assets and yields no ACSM — so it is fulfilled by the external ebook handler, which
     * reconstructs a book file from them. A last resort: any real download format is preferred.
     */
    private static final String FORMAT_EBOOK_OVERDRIVE = "ebook-overdrive";

    private static boolean isOpenFormat(String formatId) {
        return formatId != null && formatId.endsWith("-open");
    }

    /** The read-in-browser ebook format, fulfilled by the external ebook handler. */
    private static boolean isEbookHandlerFormat(String formatId) {
        return FORMAT_EBOOK_OVERDRIVE.equals(formatId);
    }

    /** Any OverDrive audiobook format (e.g. audiobook-mp3, audiobook-overdrive) — fulfilled by the tool. */
    private static boolean isAudiobookFormat(String formatId) {
        return formatId != null && formatId.startsWith("audiobook-");
    }

    /** True when the title offers any audiobook format (i.e. it is an audiobook, not an ebook). */
    private static boolean isAudiobookItem(OverDriveApiResponse.Item item) {
        return item.getFormats() != null && item.getFormats().stream()
                .map(OverDriveApiResponse.Item.Format::getId)
                .anyMatch(OverDriveService::isAudiobookFormat);
    }

    /** Any OverDrive magazine format (e.g. magazine-overdrive) — fulfilled by the magazine tool. */
    private static boolean isMagazineFormat(String formatId) {
        return formatId != null && formatId.startsWith("magazine");
    }

    /** True when the title offers a magazine format. */
    private static boolean isMagazineItem(OverDriveApiResponse.Item item) {
        return item.getFormats() != null && item.getFormats().stream()
                .map(OverDriveApiResponse.Item.Format::getId)
                .anyMatch(OverDriveService::isMagazineFormat);
    }

    /**
     * A handler-produced book file is a PDF or an EPUB; pick the type from the extension the tool
     * chose. Used for magazines and for read-in-browser ebooks, where the format id doesn't say which.
     */
    private static BookFileType fileTypeFromExtension(String extension) {
        return "pdf".equalsIgnoreCase(extension) ? BookFileType.PDF : BookFileType.EPUB;
    }

    /**
     * Order a magazine's produced files so the primary format is first. A magazine issue is usually two
     * EPUBs the tool names distinctly: a reflowable text version tagged "(Articles)" (e.g. {@code TIME
     * America at 250 (Articles).epub}) and the fixed "as-is" layout ({@code TIME America at 250.epub}).
     * The reflowable "(Articles)" version leads — it reads best on-device — then EPUB over PDF, then
     * anything else. Ties keep the handler's stable name order.
     */
    static List<MagazineHandler.OutputFile> orderMagazineFormats(List<MagazineHandler.OutputFile> files) { // package-private for testing
        Comparator<MagazineHandler.OutputFile> byArticles =
                Comparator.comparingInt(f -> isArticlesVariant(f.fileName()) ? 0 : 1);
        Comparator<MagazineHandler.OutputFile> byExtension = Comparator.comparingInt(f -> {
            String ext = f.extension() == null ? "" : f.extension().toLowerCase(Locale.ROOT);
            return switch (ext) {
                case "epub" -> 0;
                case "pdf" -> 1;
                default -> 2;
            };
        });
        return files.stream().sorted(byArticles.thenComparing(byExtension)).toList();
    }

    /** The reflowable "articles" variant the OverDrive tool tags with an "(Articles)" suffix in its name. */
    private static boolean isArticlesVariant(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).contains("(articles)");
    }

    /** The name without its trailing extension (e.g. "Issue-text.epub" → "Issue-text"). */
    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Ensure a target filename is unique within a set, appending " (2)", " (3)", … before the extension. */
    private static String uniqueImportName(String fileName, Set<String> used) {
        if (used.add(fileName)) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    private static BookFileType bookFileType(String formatId) {
        if (isAudiobookFormat(formatId)) {
            return BookFileType.AUDIOBOOK;
        }
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
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive chip request failed: no identity token returned");
             }

            return new ChipResult(token, token, body.getAccess_token_expires_in());
         } catch (Exception e) {
            log.error("Failed to obtain OverDrive chip: {}", e.getMessage());
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive chip request failed: " + e.getMessage());
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
            // The web client's obf/shib.js smuggles a "shibboleth" into Accept-Language on the /chip
            // re-mint: 2 chars of the identity token (stripped to [a-z], reversed) at offset = the
            // request path length ("chip" → 4). The server validates it against the bearer identity;
            // without it, the re-mint returns an identity stamped prbn=v and fulfillment is refused
            // ("whoa"). With it, the re-mint returns a prbn-absent identity that fulfills. (Verified
            // end-to-end; see docs/OverDrive-Testing.md.)
            String shibboleth = chipShibboleth(token, "chip".length());
            ResponseEntity<OverDriveChipResponse> resp = restClient.post()
                    .uri(finalUri)
                    .headers(h -> h.addAll(libbyHeaders(token)))
                    .header(HttpHeaders.ACCEPT_LANGUAGE, shibboleth)
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

      /**
       * Compute the {@code Accept-Language} "shibboleth" the Libby web client sends on the {@code /chip}
       * re-mint (reverse-engineered from its obfuscated {@code obf/shib.js}): strip the identity JWT to
       * lowercase letters only, reverse it, and take the 2 characters at {@code pathLen} (the request
       * path length; {@code "chip"} = 4). The sentry server validates this against the bearer identity;
       * a correct value yields a fulfillment-ready ({@code prbn}-absent) identity, a wrong/absent one
       * yields {@code prbn=v} and a {@code "whoa"} on fulfill. Returns "eng" only as a safe fallback
       * when the token is too short to slice.
       */
      private static String chipShibboleth(String token, int pathLen) {
        if (token == null) {
            return "eng";
        }
        String reversed = new StringBuilder(token.replaceAll("[^a-z]", "")).reverse().toString();
        if (reversed.length() < pathLen + 2) {
            return "eng";
        }
        return reversed.substring(pathLen, pathLen + 2);
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
       * The identity JWT's {@code exp} claim (epoch seconds) so a stored token records its <b>real</b>
       * lifetime and gets reused for as long as it's valid, rather than a fixed guess that would make us
       * re-mint (and re-run the setup flow) more often than needed. Falls back to ~1h from now if the
       * token can't be decoded.
       */
      private static long tokenExpiryEpoch(String token) {
        if (token != null && !token.isBlank()) {
            try {
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])),
                            StandardCharsets.UTF_8);
                    String exp = firstMatch(java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*(\\d+)"), payload);
                    if (exp != null) {
                        return Long.parseLong(exp);
                    }
                }
            } catch (Exception ignored) {
                // fall through to the default below
            }
        }
        return Instant.now().getEpochSecond() + 3600;
      }

      /**
       * Link a Libby account to a fresh chip identity using an 8-digit setup code
       * (libbyapp.com → Settings → "Copy to another device"). All cards on that identity are linked and
       * stored for the current user (each with the shared token); a user may redeem several codes to
       * link multiple accounts. Returns the cards linked by this code.
       */
      public List<OverDriveCard> redeemSetupCode(String setupCode) {
        return redeemSetupCode(setupCode, null);
      }

      /**
       * Redeem a setup code on another user's behalf. {@code ownerUserId} requires the cross-user card
       * permission; null links for the caller. The code is a short-lived, single-purpose artifact the
       * user can pass on (it exists to move an account to another device), so delegating it is no
       * different from delegating a card number and PIN.
       */
      public List<OverDriveCard> redeemSetupCode(String setupCode, Long ownerUserId) {
        Long owner = resolveCardOwner(ownerUserId);
        String code = setupCode != null ? setupCode.trim() : "";
        if (!code.matches("\\d{8}")) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Invalid Libby setup code: expected 8 digits");
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
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive setup-code registration failed: " + e.getMessage());
         }

        // 2b. Re-mint the identity now that cards are linked, so the stored token is card-bound
        // (the pre-clone identity is rejected by the fulfill endpoint — see refreshIdentity).
        token = refreshIdentity(token);

        // 3. Enumerate all cards on this identity and persist a token row per card for the user.
        List<OverDriveCard> cards = fetchCards(token);
        if (cards.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Setup code linked no library cards; check the code and try again.");
        }
        for (OverDriveCard card : cards) {
            storeToken(card.cardId(), card.name(), card.libraryKey(), token, owner);
            recordCardLinked(owner, card.cardId(), "Linked via setup code");
        }
        log.info("Libby account linked for user {} (by user {}): {} card(s)", owner, currentUserId(), cards.size());
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
        return linkToken(token, null);
      }

      /**
       * Link a pasted identity token on another user's behalf. {@code ownerUserId} requires the
       * cross-user card permission; null links for the caller.
       */
      public List<OverDriveCard> linkToken(String token, Long ownerUserId) {
        Long owner = resolveCardOwner(ownerUserId);
        String t = token != null ? token.trim() : "";
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("A Libby identity token is required.");
        }
        List<OverDriveCard> cards = fetchCards(t);
        if (cards.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("That token linked no library cards; it may be expired or invalid.");
        }
        for (OverDriveCard card : cards) {
            storeToken(card.cardId(), card.name(), card.libraryKey(), t, owner);
            recordCardLinked(owner, card.cardId(), "Linked via pasted token");
        }
        log.info("Libby identity token linked for user {} (by user {}): {} card(s)", owner, currentUserId(),
                cards.size());
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
        return linkCard(libraryKey, cardNumber, pin, null, null);
      }

      public List<OverDriveCard> linkCard(String libraryKey, String cardNumber, String pin, Long ownerUserId) {
        return linkCard(libraryKey, cardNumber, pin, ownerUserId, null);
      }

      /**
       * Link a card by number + PIN. {@code ownerUserId} links it for another user and requires the
       * cross-user card permission — the card+PIN flow is the only link method that can be delegated, as
       * a setup code or identity token comes from the user's own Libby app or browser session.
       */
      public List<OverDriveCard> linkCard(String libraryKey, String cardNumber, String pin, Long ownerUserId,
                                          String linkToCardId) {
        Long owner = resolveCardOwner(ownerUserId);
        String key = libraryKey != null ? libraryKey.trim() : "";
        String cn = cardNumber != null ? cardNumber.trim() : "";
        if (key.isEmpty() || cn.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Library and card number are required to link a card.");
        }
        String websiteId = overDriveParser.fetchWebsiteId(key);
        if (websiteId == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Could not resolve OverDrive library '" + key + "'. Check the library key.");
        }
        String ilsName = fetchIlsName(websiteId);
        if (ilsName == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Could not read the sign-in form for this library; card+PIN link unsupported.");
        }

        // Reuse an existing card's chip when asked, mint a fresh one otherwise. This mirrors the Libby
        // web client: it only calls POST /chip when the account has no identity yet, and otherwise
        // posts the link with the token it already holds, which adds the card to that same chip. Cards
        // sharing a chip then sync in one upstream call instead of one each.
        String token = linkToCardId == null || linkToCardId.isBlank()
                ? requestChip().token()
                : chipTokenOf(linkToCardId, owner);
        submitLocalAuthentication(websiteId, ilsName, cn, pin, token);
        token = refreshIdentity(token);

        List<OverDriveCard> cards = fetchCards(token);
        if (cards.isEmpty()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Card linked but no library card was returned; check the number and PIN.");
        }
        // On a shared chip, fetchCards returns the chip's other cards too. Their token has just been
        // re-minted along with the new card's, so every row is updated — but only the card actually
        // being linked gets these credentials, or a sibling's stored number + PIN would be silently
        // overwritten with someone else's.
        Set<String> alreadyLinked = tokenRepository.findByUserId(owner).stream()
                .map(OverDriveTokenEntity::getIdentity)
                .collect(Collectors.toSet());
        String encCard = credentialCipher.encrypt(cn);
        String encPin = credentialCipher.encrypt(pin);
        for (OverDriveCard card : cards) {
            storeToken(card.cardId(), card.name(), card.libraryKey(), token, owner);
            if (alreadyLinked.contains(card.cardId())) {
                continue;
            }
            storeCardCredentials(card.cardId(), websiteId, ilsName, encCard, encPin, owner);
            recordCardLinked(owner, card.cardId(), linkToCardId == null || linkToCardId.isBlank()
                    ? "Linked via card + PIN"
                    : "Linked via card + PIN, sharing the Libby account of card " + linkToCardId);
        }
        log.info("Libby card linked by number for user {} (by user {}): {} card(s){}", owner, currentUserId(),
                cards.size(), credentialCipher.isEnabled() ? " (credentials stored for auto-relink)" : "");
        return cards;
      }

      /**
       * The live chip token of a card the owner already has, for linking another card onto the same
       * Libby identity. Renews it first if it has expired, since the link call needs a usable bearer.
       */
      String chipTokenOf(String cardId, Long owner) { // package-private for testing
        OverDriveTokenEntity row = ownedCardForChip(cardId, owner);
        String token = tokenRenewedIfExpired(row.getIdentity());
        if (token == null || token.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Card " + cardId + " has no usable sign-in to share; refresh or re-link it first.");
        }
        return token;
      }

      /**
       * The user's own card row for a chip operation.
       *
       * <p>A card shared with you is borrowable but is not yours to extend: joining a card to its chip
       * would put that card on somebody else's Libby account, where they could see and use it and
       * where their sign-in governs it. Only the owner may decide what sits on their identity, so a
       * shared card is refused outright rather than treated as missing.
       */
      private OverDriveTokenEntity ownedCardForChip(String cardId, Long owner) {
        OverDriveTokenEntity row = tokenRepository.findByUserIdAndIdentity(owner, cardId).orElse(null);
        if (row != null) {
            return row;
        }
        if (accessibleTokenRow(owner, cardId).isPresent()) {
            throw ApiError.FORBIDDEN.createException("Card " + cardId + " was shared with you rather than "
                    + "linked by you, so cards cannot be added to its Libby account. Choose a card you own.");
        }
        throw ApiError.GENERIC_NOT_FOUND.createException("No such card: " + cardId);
      }

      /** The owner's other cards sitting on the same chip token as this row. */
      private List<OverDriveTokenEntity> chipMatesOf(OverDriveTokenEntity row) {
        String token = row.getToken();
        if (token == null || token.isBlank() || row.getUserId() == null) {
            return List.of();
        }
        return tokenRepository.findByUserId(row.getUserId()).stream()
                .filter(other -> !other.getIdentity().equals(row.getIdentity()))
                .filter(other -> token.equals(other.getToken()))
                .toList();
      }

      /**
       * Sign each card into the chip behind {@code token}, from its own stored credentials, so they
       * end up on one identity.
       *
       * <p>Best-effort per card: a card that cannot be moved (no stored credentials, or the library
       * rejects it) is skipped and left where it was rather than failing the whole operation. Returns
       * the cards that did move, for the caller to persist the shared token against.
       */
      private List<OverDriveTokenEntity> addCardsToChip(List<OverDriveTokenEntity> cards, String token) {
        return addCardsToChip(cards, token, false);
      }

      /**
       * @param persistAsMoved commit each card's new token as soon as it joins, instead of leaving that
       *                       to the caller. The join is a remote change that cannot be rolled back, so
       *                       a caller that might fail afterwards should record progress as it happens.
       */
      private List<OverDriveTokenEntity> addCardsToChip(List<OverDriveTokenEntity> cards, String token,
                                                        boolean persistAsMoved) {
        List<OverDriveTokenEntity> moved = new ArrayList<>();
        for (OverDriveTokenEntity card : cards) {
            if (card.getCredCard() == null || card.getWebsiteId() == null || card.getIlsName() == null) {
                log.info("OverDrive: card {} has no stored card + PIN, so it cannot join another chip",
                        card.getIdentity());
                continue;
            }
            String cn = credentialCipher.decrypt(card.getCredCard());
            if (cn == null) {
                continue;
            }
            try {
                submitLocalAuthentication(card.getWebsiteId(), card.getIlsName(), cn,
                        credentialCipher.decrypt(card.getCredPin()), token);
                moved.add(card);
                if (persistAsMoved) {
                    // The pre-refresh token already covers the card now that it is on the chip (the
                    // Libby client keeps using it after a link), so this is a usable state to stop in.
                    persistChipToken(List.of(card), token);
                }
            } catch (Exception e) {
                log.warn("OverDrive: could not move card {} onto the shared chip: {}",
                        card.getIdentity(), e.getMessage());
            }
        }
        return moved;
      }

      /** Persist one chip token against every card now sitting on it. */
      private void persistChipToken(List<OverDriveTokenEntity> cards, String token) {
        for (OverDriveTokenEntity card : cards) {
            card.setToken(token);
            card.setExpiresAt(tokenExpiryEpoch(token));
        }
        if (!cards.isEmpty()) {
            tokenRepository.saveAll(cards);
        }
      }

      /**
       * Move the caller's other cards onto one card's Libby identity, so they sync in a single call
       * instead of one per card.
       *
       * <p>Only cards with a stored number + PIN can move: joining a chip means signing into the
       * library again, and a setup-code or pasted-token link never gave us credentials to do that
       * with. Those are reported back rather than silently ignored, since the fix is to re-link them
       * by number.
       *
       * <p>Cards already on the target chip are left alone. Nothing is unlinked — a card that fails
       * to move keeps working exactly as it did, on its own chip.
       *
       * <p>Deliberately not transactional. Joining a chip is a remote change at Libby that no rollback
       * can undo, so each card's new token is committed the moment it moves. Wrapping the whole thing
       * in one transaction meant a later failure discarded every local record of moves that had really
       * happened, leaving Grimmory and Libby disagreeing about which chip a card sits on — which is
       * exactly what a failed run produced.
       */
      public OverDriveChipUnifyResult unifyChips(String targetCardId) {
        Long owner = currentUserId();
        OverDriveTokenEntity target = ownedCardForChip(targetCardId, owner);
        String token = tokenRenewedIfExpired(target.getIdentity());
        if (token == null || token.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Card " + targetCardId + " has no usable sign-in to share; refresh or re-link it first.");
        }

        List<OverDriveTokenEntity> candidates = new ArrayList<>();
        List<OverDriveChipUnifyResult.Skipped> skipped = new ArrayList<>();
        for (OverDriveTokenEntity card : tokenRepository.findByUserId(owner)) {
            if (card.getIdentity().equals(targetCardId)) {
                continue;
            }
            if (token.equals(card.getToken())) {
                continue; // already on this chip
            }
            if (!credentialCipher.isEnabled()) {
                skipped.add(skipped(card, "Credential storage is off (set OVERDRIVE_CREDENTIAL_KEY)"));
                continue;
            }
            if (card.getCredCard() == null || card.getWebsiteId() == null || card.getIlsName() == null) {
                skipped.add(skipped(card, "No stored card + PIN — re-link this card by number to move it"));
                continue;
            }
            candidates.add(card);
        }

        List<OverDriveTokenEntity> moved = addCardsToChip(candidates, token, true);
        for (OverDriveTokenEntity card : candidates) {
            if (!moved.contains(card)) {
                skipped.add(skipped(card, "The library rejected the stored card + PIN"));
            }
        }

        if (!moved.isEmpty()) {
            // Re-mint once the chip holds everything, then put that token on every card on it —
            // including the target, whose own token is now stale. Best-effort: the cards are already
            // recorded on the shared chip above, so a failed re-mint costs a fresher token, not the
            // consolidation itself.
            try {
                String refreshed = refreshIdentity(token);
                List<OverDriveTokenEntity> onChip = new ArrayList<>(moved);
                onChip.add(target);
                persistChipToken(onChip, refreshed);
            } catch (Exception e) {
                log.warn("OverDrive: cards joined card {}'s chip but the token re-mint failed ({}); "
                        + "they are on the shared chip with its existing token", targetCardId, e.getMessage());
            }
            recordAudit(OverDriveAuditAction.CARD_REFRESHED, targetCardId, null, null, null, null,
                    "Unified " + moved.size() + " card(s) onto this Libby account");
        }

        log.info("OverDrive: unified {} card(s) onto card {} for user {} ({} skipped)",
                moved.size(), targetCardId, owner, skipped.size());
        return new OverDriveChipUnifyResult(targetCardId,
                moved.stream().map(OverDriveTokenEntity::getIdentity).toList(), skipped);
      }

      private OverDriveChipUnifyResult.Skipped skipped(OverDriveTokenEntity card, String reason) {
        return new OverDriveChipUnifyResult.Skipped(card.getIdentity(), card.getCardName(), reason);
      }

      /**
       * Record a CARD_LINKED history entry for the card's owner. When a manager linked it on their
       * behalf, the entry still belongs to the owner (it's their card) and names who did it.
       */
      private void recordCardLinked(Long owner, String cardId, String how) {
        if (owner.equals(currentUserId())) {
            recordAudit(OverDriveAuditAction.CARD_LINKED, cardId, null, null, null, null, how);
        } else {
            recordAuditForUser(owner, OverDriveAuditAction.CARD_LINKED, cardId,
                    how + " by " + ownerName(currentUserId()));
        }
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
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive card link failed (check the card number and PIN): "
                    + e.getMessage());
        }
      }

      /** Persist the library ids + encrypted credentials onto the stored token row for a card. */
      @Transactional
      public void storeCardCredentials(String identity, String websiteId, String ilsName, String encCard,
                                       String encPin) {
        storeCardCredentials(identity, websiteId, ilsName, encCard, encPin, null);
      }

      public void storeCardCredentials(String identity, String websiteId, String ilsName, String encCard,
                                       String encPin, Long ownerUserId) {
        tokenRepository.findByUserIdAndIdentity(resolveCardOwner(ownerUserId), identity).ifPresent(entity -> {
            entity.setWebsiteId(websiteId);
            entity.setIlsName(ilsName);
            entity.setCredCard(encCard);
            entity.setCredPin(encPin);
            tokenRepository.save(entity);
        });
      }

      /**
       * Refresh a card's stored token by re-linking from its stored credentials (card+PIN). Works on any
       * card the user can use, including one shared with them — sharing grants access to the owner's token
       * row, so renewing it is what keeps the share working (see {@link #relinkCard}). Throws a clear
       * error when the card has no usable stored credentials (setup-code / pasted-token links, or no
       * credential key configured) — those should be cleared and re-linked instead.
       */
      public void refreshCard(String identity) {
        refreshCard(identity, null);
      }

      /**
       * Refresh a card's token. {@code ownerUserId} targets another user's card and requires the
       * cross-user card permission; null means "a card I can use", which includes one shared with me.
       */
      public void refreshCard(String identity, Long ownerUserId) {
        if (ownerUserId != null && !ownerUserId.equals(currentUserId())) {
            OverDriveTokenEntity card = administrableCard(identity, ownerUserId);
            if (relinkRow(card) == null) {
                throw ApiError.GENERIC_BAD_REQUEST.createException("Couldn't refresh this card — it has no stored card+PIN "
                        + "credentials (set OVERDRIVE_CREDENTIAL_KEY and link by card + PIN), or re-linking "
                        + "failed. Unlink it and link again.");
            }
            recordAudit(OverDriveAuditAction.CARD_REFRESHED, identity, null, null, null, null,
                    "Token refreshed from stored credentials" + onBehalfOfSuffix(ownerUserId));
            return;
        }
        if (relinkCard(identity) == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Couldn't refresh this card — it has no stored card+PIN "
                    + "credentials (set OVERDRIVE_CREDENTIAL_KEY and link by card + PIN), or re-linking "
                    + "failed. Unlink it and link again.");
        }
        recordAudit(OverDriveAuditAction.CARD_REFRESHED, identity, null, null, null, null, "Token refreshed from stored credentials");
      }

      /**
       * Silently re-link a card from its stored (encrypted) credentials to mint a fresh primary token,
       * updating the stored row. Returns the new token, or null when no usable credentials are stored
       * (no credential key configured, or card linked via setup code). Never throws.
       *
       * <p>Resolves the row through {@link #accessibleTokenRow}, so a <b>sharee</b> renews the <b>owner's</b>
       * row — the very row sharing hands them (never a copy). Without that, a shared card whose token died
       * could only be revived by its owner, and every sharee's borrow/return would 403 until they noticed.
       * The owner's credentials are decrypted server-side for the re-link only and never leave the server,
       * and the renewed token grants the sharee nothing they weren't already sharing — the same reasoning
       * as {@link #handlerCard}, which already hands a shared card's credentials to the download tools.
       */
      private String relinkCard(String cardId) {
        if (!credentialCipher.isEnabled()) {
            return null;
        }
        var row = accessibleTokenRow(currentUserId(), cardId).orElse(null);
        return relinkRow(row);
      }

      /**
       * Re-link a specific card row from its stored credentials, updating the row in place. Returns the
       * new token, or null when the row can't be re-linked (no credential key, or no card+PIN on file).
       * Never throws.
       */
      private String relinkRow(OverDriveTokenEntity row) {
        if (!credentialCipher.isEnabled()) {
            return null;
        }
        if (row == null || row.getCredCard() == null || row.getWebsiteId() == null || row.getIlsName() == null) {
            return null;
        }
        String cardId = row.getIdentity();
        String cn = credentialCipher.decrypt(row.getCredCard());
        String pin = credentialCipher.decrypt(row.getCredPin());
        if (cn == null) {
            return null;
        }
        // Cards that shared this row's chip, captured before it is replaced. A re-link mints a brand
        // new identity holding only this card, so without moving them across they are orphaned — and
        // propagateReMintedToken would then hand them a token for a chip they are not on.
        List<OverDriveTokenEntity> chipMates = chipMatesOf(row);
        try {
            String token = requestChip().token();
            submitLocalAuthentication(row.getWebsiteId(), row.getIlsName(), cn, pin, token);
            List<OverDriveTokenEntity> moved = addCardsToChip(chipMates, token);
            token = refreshIdentity(token);
            row.setToken(token);
            row.setExpiresAt(tokenExpiryEpoch(token));
            tokenRepository.save(row);
            persistChipToken(moved, token);
            log.info("OverDrive: re-linked card {} from stored credentials (owner user {}, renewed by user {}){}",
                    cardId, row.getUserId(), currentUserId(),
                    moved.isEmpty() ? "" : " — kept " + moved.size() + " chip-mate(s) on the same identity");
            return token;
        } catch (Exception e) {
            log.warn("OverDrive: auto-relink failed for card {}: {}", cardId, e.getMessage());
            return null;
        }
      }

      /**
       * Persist a reactively re-minted token onto the stored card row so subsequent fulfillments reuse
       * it rather than re-minting again — fewer round-trips, and we keep the fulfillment-ready
       * (prbn-absent) identity that {@link #refreshIdentity} minted with the correct shibboleth.
       * Best-effort; never throws.
       */
      private void persistReMintedToken(String identity, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            // Update the owner's row (the one access resolves to), so a re-mint triggered by a sharee
            // keeps the shared token fresh for everyone.
            accessibleTokenRow(currentUserId(), identity).ifPresent(row -> {
                row.setToken(token);
                row.setExpiresAt(tokenExpiryEpoch(token));
                tokenRepository.save(row);
            });
        } catch (Exception e) {
            log.debug("OverDrive: could not persist re-minted token for card {}: {}", identity, e.getMessage());
        }
      }

      /**
       * Whether a failure is Sentry's {@code 403 {"result":"missing_chip"}} — the identity we called with
       * is no longer a registered chip, so the call can only succeed with a re-minted or re-linked one.
       */
      /**
       * How long a card rests after OverDrive reports it as churning titles.
       *
       * <p>Thunder's own message asks for "several days" and says to contact support if borrowing is
       * still refused after seven, so seven is the number it names as the outside of normal. Waiting
       * the full period once beats discovering by trial that five was not enough — every probe is
       * another borrow attempt on an account already flagged for too many of them.
       */
      private static final java.time.Duration CHURN_COOLDOWN = java.time.Duration.ofDays(7);

      /**
       * Why this card cannot borrow right now, or null when it can.
       *
       * <p>One answer covering both reasons a card is out of action, so every caller — the poller, the
       * bookbag, a person clicking Borrow — asks the same question and gets the same wording. Resting
       * is checked first: it is OverDrive's own refusal, and it outranks a ceiling we set ourselves.
       */
      String borrowBlockedReason(String identity) { // package-private for testing
        Instant resting = churnCooldownUntil(identity);
        if (resting != null) {
            return "OverDrive reported too many titles borrowed and returned on this card; it resumes at "
                    + resting;
        }
        String ceiling = borrowCeilingReached(identity);
        return ceiling == null ? null : "this card has already used " + ceiling;
      }

      /** Refuse a borrow a person asked for, saying which of the two reasons applies. */
      private void assertCanBorrow(String identity) {
        String blocked = borrowBlockedReason(identity);
        if (blocked != null) {
            throw ApiError.CONFLICT.createException("Can't borrow right now: " + blocked + ".");
        }
      }

      /**
       * Why this card cannot give a copy back right now, or null when it can.
       *
       * <p>Returns are rate-limited like borrows because they are the other half of what
       * PatronExceededChurningLimit counts — handing books back in a burst is the same signal to
       * OverDrive as taking them out in one, and pacing one side while leaving the other unbounded is
       * not pacing. The ceilings are the same numbers, counted separately.
       */
      String returnBlockedReason(String identity) { // package-private for testing
        Instant resting = churnCooldownUntil(identity);
        if (resting != null) {
            return "OverDrive reported too many titles borrowed and returned on this card; it resumes at "
                    + resting;
        }
        String ceiling = returnCeilingReached(identity, borrowLimits(identity));
        return ceiling == null ? null : "this card has already used " + ceiling;
      }

      /** Refuse a return a person asked for, saying which reason applies. */
      private void assertCanReturn(String identity) {
        String blocked = returnBlockedReason(identity);
        if (blocked != null) {
            throw ApiError.CONFLICT.createException("Can't return right now: " + blocked
                    + ". It resumes on its own — nothing needs doing.");
        }
      }

      /**
       * A card's ceilings and what it has actually done lately, for the administrator setting them.
       *
       * @param identity the card
       * @param cardName its label, so the admin screen need not join this back to the card list
       * @param limits   the configured ceilings; nulls where none is set
       * @param rate     borrows counted over each window, right now
       */
      public record CardBorrowBudget(String identity, String cardName, BorrowLimits limits, BorrowRate rate) {}

      /**
       * Every card on the server with its ceilings and current borrow rate. Administrators only: the
       * ceilings are deployment-wide policy on a shared library account, not a per-user preference.
       */
      public List<CardBorrowBudget> listCardBorrowBudgets() {
        requireCardLimitAdmin();
        // One row per identity: a card linked by several users is one account with one budget.
        Map<String, String> namesByIdentity = new LinkedHashMap<>();
        for (OverDriveTokenEntity row : tokenRepository.findAll()) {
            if (row.getIdentity() != null) {
                namesByIdentity.putIfAbsent(row.getIdentity(),
                        row.getCardName() != null ? row.getCardName() : row.getIdentity());
            }
        }
        return namesByIdentity.entrySet().stream()
                .map(e -> new CardBorrowBudget(e.getKey(), e.getValue(),
                        borrowLimits(e.getKey()), borrowRate(e.getKey())))
                .toList();
      }

      /**
       * Set a card's ceilings. A null in any position clears that window's ceiling; a value of zero
       * would stop the card borrowing entirely, so it is rejected as almost certainly a mistake —
       * unlinking or resting the card is how you stop it on purpose.
       */
      @Transactional
      public CardBorrowBudget setCardBorrowLimits(String identity, BorrowLimits limits) {
        requireCardLimitAdmin();
        if (identity == null || identity.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("A card is required.");
        }
        for (BorrowWindow window : BorrowWindow.values()) {
            Integer value = limits.forWindow(window);
            if (value != null && value <= 0) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "A limit of " + value + " per " + window.label + " would stop this card borrowing "
                                + "altogether. Leave it blank for no limit, or unlink the card to stop using it.");
            }
        }
        OverDriveCardLimitEntity row = cardLimitRepository.findById(identity)
                .orElseGet(() -> OverDriveCardLimitEntity.builder().identity(identity).build());
        row.setMaxPerMinute(limits.perMinute());
        row.setMaxPerHour(limits.perHour());
        row.setMaxPerDay(limits.perDay());
        row.setMaxPerWeek(limits.perWeek());
        row.setMaxPerMonth(limits.perMonth());
        row.setUpdatedAt(Instant.now());
        cardLimitRepository.save(row);
        log.info("OverDrive: borrow limits for card {} set to {}/min {}/hr {}/day {}/week {}/30d",
                identity, limits.perMinute(), limits.perHour(), limits.perDay(), limits.perWeek(),
                limits.perMonth());
        recordAudit(OverDriveAuditAction.CARD_RELABELED, identity, null, null, null, null,
                "Borrow limits set to " + describe(limits) + ".");
        return new CardBorrowBudget(identity, cardNameOf(identity), limits, borrowRate(identity));
      }

      private static String describe(BorrowLimits limits) {
        StringJoiner joiner = new StringJoiner(", ");
        for (BorrowWindow window : BorrowWindow.values()) {
            Integer value = limits.forWindow(window);
            joiner.add(value == null ? "no " + window.label + " limit" : value + " per " + window.label);
        }
        return joiner.toString();
      }

      /**
       * A card's label, falling back to its identity. The save response feeds the admin grid directly,
       * so returning the identity here renamed the row to a raw chip id until the page was reloaded.
       */
      private String cardNameOf(String identity) {
        return tokenRepository.findByIdentity(identity).stream()
                .map(OverDriveTokenEntity::getCardName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(identity);
      }

      /** Ceilings are deployment policy on a shared account, so only a card administrator may set them. */
      private void requireCardLimitAdmin() {
        if (!currentUserCanManageAllCards()) {
            throw ApiError.FORBIDDEN.createException("Only an administrator can view or change borrow limits.");
        }
      }

      /**
       * How long to wait between borrowing a title and fetching the file.
       *
       * <p>A person browses, borrows, and then their reader collects the book a little later. Doing
       * both in the same breath, for title after title, is the shape of a script. This is deliberately
       * longer than the gap between titles: the pause that matters is the one inside a single
       * borrow-and-download, which is where the machine-like tell is.
       */
      private static final long FULFIL_GAP_MIN_MILLIS = 75_000;
      private static final long FULFIL_GAP_MAX_MILLIS = 240_000;

      /**
       * Let a fresh borrow settle before fetching the file.
       *
       * <p>A person borrows a title and their reader collects it a little later; borrowing and
       * downloading in the same breath, title after title, is the shape of a script. Only for
       * automated passes — somebody sitting in front of the Borrow button is entitled to their book
       * immediately, and making them wait minutes would be absurd.
       *
       * <p>Skipped when the loan already existed: resuming an earlier attempt is not a fresh checkout,
       * and the pause is about the gap between taking a book out and reading it.
       */
      private void pauseBeforeFulfilling(String titleId) {
        if (!isAutomated()) {
            return;
        }
        long millis = ThreadLocalRandom.current().nextLong(FULFIL_GAP_MIN_MILLIS, FULFIL_GAP_MAX_MILLIS + 1);
        log.debug("OverDrive: waiting {}s before fetching title {}", millis / 1000, titleId);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.INTERNAL_SERVER_ERROR.createException("OverDrive automation interrupted before fulfilment");
        }
      }

      /** What one bookbag pass did. */
      record BookbagOutcome(int borrowed, int held, int failures) {} // package-private for testing

      /**
       * Work the bag: borrow what can be borrowed, place holds on what cannot, in the user's order.
       *
       * <p>Order is a priority, not a barrier. A title whose libraries are all lent out would
       * otherwise stall everything behind it for as long as it stayed unavailable, so a blocked entry
       * is passed over and keeps its place for next time.
       *
       * <p>A title nobody can lend right now gets a hold instead, and stays in the bag until that hold
       * comes in — which is what makes the bag a want-list rather than a list of things tried once.
       * The hold is recorded on the entry so a later pass does not queue for the same book twice.
       *
       * <p>Runs against the cards' live budget: {@link #borrowBlockedReason} covers both a card
       * resting after a churning limit and one that has reached an administrator's ceiling, and the
       * loan-capacity snapshot is shared with the rest of the pass so slots spent here are visible to
       * it. When every card is out of budget the bag simply waits for the next poll.
       */
      BookbagOutcome runBookbag(Long userId, Set<String> heldTitleIds, Map<String, Integer> loanSlotsLeft,
                                Map<String, Integer> holdSlotsLeft, Map<String, Integer> holdsPerCard,
                                LoanActionPacer pacer) { // package-private for testing
        List<OverDriveBookbagEntity> bag = bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        if (bag.isEmpty()) {
            return new BookbagOutcome(0, 0, 0);
        }
        Map<String, String> cardByLibrary = new LinkedHashMap<>();
        for (OverDriveTokenEntity row : accessibleTokenRows(userId)) {
            if (row.getLibraryKey() != null && !row.getLibraryKey().isBlank()) {
                cardByLibrary.putIfAbsent(row.getLibraryKey(), row.getIdentity());
            }
        }
        if (cardByLibrary.isEmpty()) {
            return new BookbagOutcome(0, 0, 0);
        }

        Map<String, List<OverDriveLibraryAvailability>> availability = availabilityForTitles(
                bag.stream().map(OverDriveBookbagEntity::getTitleId).distinct().toList(),
                List.copyOf(cardByLibrary.values()));
        Instant now = Instant.now();
        int borrowed = 0;
        int held = 0;
        int failures = 0;

        for (OverDriveBookbagEntity entry : bag) {
            List<OverDriveLibraryAvailability> options = availability.getOrDefault(entry.getTitleId(), List.of());
            entry.setLastTriedAt(now);

            // Already ours — the user got it another way, or a hold we placed came in and was
            // imported. Either way the bag's work on it is done.
            Long owned = entry.isAllowReborrow() ? null : resolveLinkedBookId(entry.getTitleId(), null, null);
            if (owned != null) {
                log.info("OverDrive bookbag: \"{}\" is already in the library as book id={}; dropping it "
                        + "from user {}'s bag", entry.getTitle(), owned, userId);
                bookbagRepository.delete(entry);
                continue;
            }

            String borrowCard = borrowableCardFor(options, cardByLibrary, loanSlotsLeft);
            if (borrowCard != null) {
                try {
                    pauseBetweenLoanActions(pacer);
                    loanSlotsLeft.computeIfPresent(borrowCard, (id, left) -> left - 1);
                    borrowAndImport(borrowCard, entry.getTitleId(), null, null,
                            entry.getTitle(), entry.getAuthor(), null, null, null, null, null);
                    borrowed++;
                    log.info("OverDrive bookbag: borrowed \"{}\" for user {} on card {}",
                            entry.getTitle(), userId, borrowCard);
                    bookbagRepository.delete(entry);
                    continue;
                } catch (Exception e) {
                    failures++;
                    entry.setLastNote(truncate("Couldn't borrow it: " + e.getMessage(), 512));
                    bookbagRepository.save(entry);
                    log.warn("OverDrive bookbag: borrowing \"{}\" for user {} on card {} failed: {}",
                            entry.getTitle(), userId, borrowCard, e.getMessage());
                    continue;
                }
            }

            // Nobody can lend it now. Queue for it, unless we already have.
            if (entry.getHoldCardId() != null) {
                if (heldTitleIds.contains(entry.getTitleId())) {
                    entry.setLastNote("Waiting on the hold placed at " + entry.getHoldCardId() + ".");
                    bookbagRepository.save(entry);
                    continue;
                }
                // The hold is gone — cancelled by hand, or lapsed unclaimed. Forgetting it lets the
                // entry queue again next pass instead of waiting forever on something that no longer
                // exists, which is what a bare "is a card recorded?" check used to do.
                log.info("OverDrive bookbag: the hold on \"{}\" for user {} is no longer on card {}; "
                        + "the entry will queue again", entry.getTitle(), userId, entry.getHoldCardId());
                entry.setHoldCardId(null);
                entry.setHoldPlacedAt(null);
            }
            SoonerQueue queue = bestQueueFor(options, cardByLibrary, holdSlotsLeft, holdsPerCard);
            if (queue == null) {
                entry.setLastNote(noBorrowNote(options, cardByLibrary, loanSlotsLeft));
                bookbagRepository.save(entry);
                continue;
            }
            try {
                // A hold takes no copy out, so it is paced like the light actions rather than the
                // checkouts — queueing for ten books is not what the churning limit counts.
                pauseBetweenTitles(held);
                placeHold(queue.cardId(), entry.getTitleId());
                holdSlotsLeft.computeIfPresent(queue.cardId(), (id, left) -> left - 1);
                entry.setHoldCardId(queue.cardId());
                entry.setHoldPlacedAt(Instant.now());
                entry.setLastNote("On the shelf nowhere, so a hold was placed at " + queue.cardId()
                        + " (~" + queue.waitDays() + "d).");
                bookbagRepository.save(entry);
                held++;
                log.info("OverDrive bookbag: no copy of \"{}\" for user {}; placed a hold at {} (~{}d)",
                        entry.getTitle(), userId, queue.cardId(), queue.waitDays());
            } catch (Exception e) {
                failures++;
                entry.setLastNote(truncate("Couldn't place a hold: " + e.getMessage(), 512));
                bookbagRepository.save(entry);
                log.warn("OverDrive bookbag: placing a hold on \"{}\" for user {} at {} failed: {}",
                        entry.getTitle(), userId, queue.cardId(), e.getMessage());
            }
        }
        return new BookbagOutcome(borrowed, held, failures);
      }

      /** The card a hold sits on, unless that card is resting or out of borrow budget. */
      private String holdCardStillUsable(String holdCardId) {
        return borrowBlockedReason(holdCardId) == null ? holdCardId : null;
      }

      /**
       * Whether any of the user's libraries has this on the shelf at all, ignoring whether we are
       * currently allowed to take it. Distinguishes "no copy exists" — which a hold answers — from
       * "a copy exists but that card is full, resting, or at a ceiling", which only time answers.
       */
      private boolean onShelfSomewhere(List<OverDriveLibraryAvailability> options,
                                       Map<String, String> cardByLibrary) {
        return options.stream().anyMatch(option ->
                (option.available()
                        || (option.luckyDayAvailableCopies() != null && option.luckyDayAvailableCopies() > 0))
                        && cardByLibrary.containsKey(option.libraryKey()));
      }

      /**
       * The best card to take one of these copies with: on the shelf, with room and budget.
       *
       * <p>Where several libraries can lend it, the one with more copies free wins. Every candidate
       * has a copy, but they are not equally able to spare it — taking the only copy at a small
       * library empties its shelf and starts a queue there, while the same read sitting behind
       * several copies elsewhere costs nobody a wait, and a lone copy is likelier to be gone by the
       * time the borrow lands. Remaining checkouts break the tie, so no one card is drained first.
       *
       * <p>This ordering used to live in the search page, which chose the card before asking the
       * server to borrow. Acquisition now goes through the queue, so the choice is the server's and
       * the preference has to live here.
       */
      String borrowableCardFor(List<OverDriveLibraryAvailability> options, Map<String, String> cardByLibrary,
                               Map<String, Integer> loanSlotsLeft) { // package-private for testing
        String best = null;
        int bestCopies = -1;
        for (OverDriveLibraryAvailability option : options) {
            boolean onShelf = option.available()
                    || (option.luckyDayAvailableCopies() != null && option.luckyDayAvailableCopies() > 0);
            String cardId = onShelf ? cardByLibrary.get(option.libraryKey()) : null;
            if (cardId == null || atLoanCapacity(loanSlotsLeft, cardId) || borrowBlockedReason(cardId) != null) {
                continue;
            }
            int copies = (option.availableCopies() != null ? option.availableCopies() : (option.available() ? 1 : 0))
                    + (option.luckyDayAvailableCopies() != null ? option.luckyDayAvailableCopies() : 0);
            boolean better = best == null
                    || copies > bestCopies
                    || (copies == bestCopies && remainingLoans(loanSlotsLeft, cardId) > remainingLoans(loanSlotsLeft, best));
            if (better) {
                best = cardId;
                bestCopies = copies;
            }
        }
        return best;
      }

      /** Checkouts a card has left; an unreported limit sorts as plenty rather than as none. */
      private static int remainingLoans(Map<String, Integer> loanSlotsLeft, String cardId) {
        return loanSlotsLeft.getOrDefault(cardId, Integer.MAX_VALUE);
      }

      /**
       * The best queue to join for a title nobody can lend: shortest estimated wait, then the library
       * owning more copies. The same preference {@link #soonerElsewhere} applies when moving a hold,
       * minus the "beat the current one" test — there is no current hold to beat.
       */
      private SoonerQueue bestQueueFor(List<OverDriveLibraryAvailability> options,
                                       Map<String, String> cardByLibrary, Map<String, Integer> holdSlotsLeft,
                                       Map<String, Integer> holdsPerCard) {
        SoonerQueue best = null;
        int bestCopies = -1;
        for (OverDriveLibraryAvailability option : options) {
            if (!option.holdable() || option.estimatedWaitDays() == null) {
                continue;
            }
            String cardId = cardByLibrary.get(option.libraryKey());
            if (cardId == null || atHoldCapacity(holdSlotsLeft, cardId) || churnCooldownUntil(cardId) != null) {
                continue;
            }
            int copies = option.ownedCopies() != null ? option.ownedCopies() : 0;
            boolean better = best == null
                    || option.estimatedWaitDays() < best.waitDays()
                    || (option.estimatedWaitDays() == best.waitDays() && copies > bestCopies)
                    || (option.estimatedWaitDays() == best.waitDays() && copies == bestCopies
                        && holdsPerCard.getOrDefault(cardId, 0) < holdsPerCard.getOrDefault(best.cardId(), 0));
            if (better) {
                best = new SoonerQueue(cardId, option.estimatedWaitDays(), option.estimatedWaitDays());
                bestCopies = copies;
            }
        }
        return best;
      }

      /**
       * Why an entry could be neither borrowed nor held, in words the user can act on. A copy sitting
       * on a shelf we cannot reach is a different problem from no copy existing, and the note should
       * not make them look the same.
       */
      private String noBorrowNote(List<OverDriveLibraryAvailability> options, Map<String, String> cardByLibrary,
                                  Map<String, Integer> loanSlotsLeft) {
        for (OverDriveLibraryAvailability option : options) {
            boolean onShelf = option.available()
                    || (option.luckyDayAvailableCopies() != null && option.luckyDayAvailableCopies() > 0);
            String cardId = onShelf ? cardByLibrary.get(option.libraryKey()) : null;
            if (cardId == null) {
                continue;
            }
            String blocked = borrowBlockedReason(cardId);
            if (blocked != null) {
                return truncate("A copy is available, but " + blocked + ".", 512);
            }
            if (atLoanCapacity(loanSlotsLeft, cardId)) {
                return "A copy is available, but that card is at its checkout limit.";
            }
        }
        return options.isEmpty()
                ? "None of your libraries carry this title."
                : "No copy available and no queue to join right now.";
      }

      // ── Bookbag: titles queued for the poller to borrow as cards allow ───

      /**
       * One queued title as the UI sees it.
       *
       * @param holdCardId the card a hold was placed on while waiting, or null if none has been
       * @param lastNote   why the last pass could not borrow it, or null after a clean run
       */
      public record BookbagEntry(Long id, String titleId, String title, String author, int position,
                                 String holdCardId, String holdPlacedAt, String lastNote,
                                 String lastTriedAt, String createdAt, boolean allowReborrow) {}

      /** The current user's bag, in the order it will be worked. */
      public List<BookbagEntry> listBookbag() {
        return bookbagRepository.findByUserIdOrderByPositionAscIdAsc(currentUserId()).stream()
                .map(OverDriveService::toBookbagEntry)
                .toList();
      }

      private static BookbagEntry toBookbagEntry(OverDriveBookbagEntity e) {
        return new BookbagEntry(e.getId(), e.getTitleId(), e.getTitle(), e.getAuthor(), e.getPosition(),
                e.getHoldCardId(),
                e.getHoldPlacedAt() != null ? e.getHoldPlacedAt().toString() : null,
                e.getLastNote(),
                e.getLastTriedAt() != null ? e.getLastTriedAt().toString() : null,
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null,
                e.isAllowReborrow());
      }

      /**
       * Put a title in the bag, at the back. Adding one already there is a no-op that returns the
       * existing entry rather than an error or a reorder — the natural reading of pressing the button
       * twice is "I want this", not "move it".
       */
      @Transactional
      public BookbagEntry addToBookbag(String titleId, String title, String author, boolean front) {
        if (titleId == null || titleId.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("A title is required.");
        }
        Long userId = currentUserId();
        List<OverDriveBookbagEntity> existing = bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        // Adding one already queued moves it if the front was asked for, and otherwise leaves it be:
        // "put this first" is an instruction about order, where a plain add is not.
        Optional<OverDriveBookbagEntity> already = bookbagRepository.findByUserIdAndTitleId(userId, titleId);
        if (already.isPresent()) {
            OverDriveBookbagEntity row = already.get();
            if (front) {
                row.setPosition(frontPosition(existing));
                bookbagRepository.save(row);
            }
            return toBookbagEntry(row);
        }
        int position = front ? frontPosition(existing) : backPosition(existing);
        // Queueing a title the library already has can only mean a second copy is wanted — a damaged
        // file, another edition, a format the first import could not produce. Recorded now, because
        // by the time a pass looks at it the entry is indistinguishable from one that simply became
        // redundant while it waited.
        boolean reborrow = resolveLinkedBookId(titleId.trim(), null, null) != null;
        OverDriveBookbagEntity row = OverDriveBookbagEntity.builder()
                .userId(userId).titleId(titleId.trim())
                .title(truncate(title, 1024)).author(truncate(author, 255))
                .position(position).allowReborrow(reborrow).build();
        if (reborrow) {
            log.info("OverDrive bookbag: user {} queued \"{}\" ({}), which the library already has — "
                    + "treating it as a deliberate re-borrow", userId, title, titleId);
        }
        log.info("OverDrive bookbag: user {} queued \"{}\" ({}) at {} of the queue",
                userId, title, titleId, front ? "the front" : "the back");
        return toBookbagEntry(bookbagRepository.save(row));
      }

      /** Positions are only ever compared, never counted, so going below zero to jump the queue is fine. */
      private static int frontPosition(List<OverDriveBookbagEntity> existing) {
        return existing.stream().mapToInt(OverDriveBookbagEntity::getPosition).min().orElse(1) - 1;
      }

      private static int backPosition(List<OverDriveBookbagEntity> existing) {
        return existing.stream().mapToInt(OverDriveBookbagEntity::getPosition).max().orElse(0) + 1;
      }

      /**
       * Borrow one queued title right now, skipping the queue and the pacing.
       *
       * <p>The deliberate way out of the schedule. Everything else about acquiring a book goes through
       * the bag so it happens at a defensible rate; this is the "I want it now" door, and it is
       * narrow on purpose: one title, asked for explicitly, with the download following straight after
       * rather than minutes later.
       *
       * <p>What it does not skip is the card's own state. A card resting after a churning limit, or
       * one at a ceiling an administrator set, still refuses — those exist to stop the account being
       * hurt, and an impatient click is exactly the moment that protection matters. The refusal says
       * which of the two applies and when it lifts.
       *
       * @return the borrowed book
       */
      @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
      public Book borrowBookbagEntryNow(Long id) {
        Long userId = currentUserId();
        OverDriveBookbagEntity entry = bookbagRepository.findById(id)
                .filter(e -> userId.equals(e.getUserId()))
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("That title is not in your bookbag."));

        Map<String, String> cardByLibrary = new LinkedHashMap<>();
        for (OverDriveTokenEntity row : accessibleTokenRows(userId)) {
            if (row.getLibraryKey() != null && !row.getLibraryKey().isBlank()) {
                cardByLibrary.putIfAbsent(row.getLibraryKey(), row.getIdentity());
            }
        }
        List<OverDriveLibraryAvailability> options = availabilityForTitles(
                List.of(entry.getTitleId()), List.copyOf(cardByLibrary.values()))
                .getOrDefault(entry.getTitleId(), List.of());

        // No live loan counts to hand: this is one borrow outside a pass, and the card's own limit
        // check below is what actually protects it.
        String card = borrowableCardFor(options, cardByLibrary, Map.of());
        if (card == null && entry.getHoldCardId() != null) {
            // A hold that has come in reserves a copy for this user, which the library's public
            // availability does not show — the shelf can read zero while a copy is waiting with their
            // name on it. Borrowing on the hold's own card is how that copy is taken; if the hold is
            // not ready, OverDrive refuses and says so.
            card = holdCardStillUsable(entry.getHoldCardId());
        }
        if (card == null) {
            throw ApiError.CONFLICT.createException(noBorrowNote(options, cardByLibrary, Map.of()));
        }
        // Not automated, so borrowAndImport fulfils immediately rather than pausing first.
        Book book = borrowAndImport(card, entry.getTitleId(), null, null,
                entry.getTitle(), entry.getAuthor(), null, null, null, null, null);
        bookbagRepository.delete(entry);
        log.info("OverDrive bookbag: user {} borrowed \"{}\" on demand from card {}",
                userId, entry.getTitle(), card);
        return book;
      }

      /**
       * When each queued entry is expected to be reached.
       *
       * @param entryId    the bookbag entry
       * @param plannedAt  roughly when the pass will get to it
       * @param passOffset which firing it falls in: 0 the next one, 1 the one after, and so on
       */
      public record BookbagPlanEntry(Long entryId, String plannedAt, int passOffset) {}

      /**
       * Lay the queue out across the coming passes.
       *
       * <p>Computed, never stored, so reordering the bag reshuffles it for free and it cannot go
       * stale. Server-side because the honest answer needs the per-card ceilings, and those are
       * deployment policy that an ordinary user cannot read.
       *
       * <p>The limits are what make this more than "everything at once": a pass keeps taking titles
       * only while some card can still borrow, so with a modest hourly ceiling a long queue is drained
       * a few per firing rather than in one sitting. Entries waiting on a hold are skipped entirely —
       * nothing is planned for them until the hold comes in.
       *
       * <p>An estimate, and it says so by being coarse: the middle of each randomised gap, and the
       * plain cron slots for firings after the next, which are not drawn yet.
       */
      public List<BookbagPlanEntry> bookbagPlan(List<Instant> upcomingRuns) {
        Long userId = currentUserId();
        List<OverDriveBookbagEntity> bag = bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        if (bag.isEmpty() || upcomingRuns.isEmpty()) {
            return List.of();
        }
        int perPass = Math.max(1, borrowsLeftThisPass(userId));
        // Midpoints of the pacing a pass really applies, between one checkout and the next.
        long gapMillis = (LOAN_ACTION_GAP_MIN_MILLIS + LOAN_ACTION_GAP_MAX_MILLIS) / 2
                + (FULFIL_GAP_MIN_MILLIS + FULFIL_GAP_MAX_MILLIS) / 2;

        List<BookbagPlanEntry> plan = new ArrayList<>();
        int placed = 0;
        for (OverDriveBookbagEntity entry : bag) {
            if (entry.getHoldCardId() != null) {
                continue; // queued behind a hold; the pass will look, not act
            }
            int pass = placed / perPass;
            if (pass >= upcomingRuns.size()) {
                break; // beyond what we are willing to guess at
            }
            Instant at = upcomingRuns.get(pass).plusMillis((long) (placed % perPass) * gapMillis);
            plan.add(new BookbagPlanEntry(entry.getId(), at.toString(), pass));
            placed++;
        }
        return plan;
      }

      /**
       * How many more titles the user's cards could take between them before a ceiling stops them.
       *
       * <p>Measured over the hour, which is the window that actually bites during one pass: a pass
       * spaces checkouts minutes apart, so it cannot reach a daily or monthly ceiling on its own, and
       * the per-minute one is already satisfied by the spacing. Cards resting or out of budget
       * contribute nothing.
       */
      private int borrowsLeftThisPass(Long userId) {
        int total = 0;
        for (OverDriveTokenEntity row : accessibleTokenRows(userId)) {
            String identity = row.getIdentity();
            if (identity == null || borrowBlockedReason(identity) != null) {
                continue;
            }
            Integer hourly = borrowLimits(identity).perHour();
            if (hourly == null) {
                return Integer.MAX_VALUE / 2; // no hourly ceiling anywhere: the pacing is the only brake
            }
            total += Math.max(0, hourly - borrowRate(identity).lastHour());
        }
        return total;
      }

      /**
       * Put every hold the user currently has into the bookbag, adopting each one rather than placing
       * a second.
       *
       * <p>The backfill for an account that was using holds before the bag existed. It cannot be a
       * migration: holds are never stored here — they live at OverDrive and arrive with each sync — so
       * there is nothing in the database for SQL to read.
       *
       * <p>Deliberately something you ask for rather than something a pass does on its own. Absorbing
       * every hold into a queue that will borrow them unattended is a large consequence, and a pass
       * that did it automatically would also undo any entry you deliberately removed, re-adopting it
       * on the next tick.
       *
       * <p>Oldest hold first: you have waited longest for it.
       *
       * @return how many entries were added
       */
      @Transactional
      public int adoptHoldsIntoBookbag() {
        Long userId = currentUserId();
        List<String> identities = listCards().stream()
                .map(OverDriveCard::cardId).filter(Objects::nonNull).toList();
        if (identities.isEmpty()) {
            return 0;
        }
        Map<String, OverDriveSyncResponse> syncs = syncAll(identities);
        Set<String> alreadyQueued = bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId).stream()
                .map(OverDriveBookbagEntity::getTitleId)
                .collect(Collectors.toSet());

        // One entry per title, even where the same title is held at two libraries: the bag wants the
        // book, not each queue it is sitting in.
        Map<String, OverDriveHold> byTitle = new LinkedHashMap<>();
        for (OverDriveSyncResponse sync : syncs.values()) {
            if (sync == null || sync.getHolds() == null) {
                continue;
            }
            for (OverDriveHold hold : sync.getHolds()) {
                if (hold.getId() != null && !alreadyQueued.contains(hold.getId())) {
                    byTitle.putIfAbsent(hold.getId(), hold);
                }
            }
        }
        if (byTitle.isEmpty()) {
            return 0;
        }

        int position = backPosition(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId));
        List<OverDriveBookbagEntity> added = new ArrayList<>();
        for (OverDriveHold hold : byTitle.values().stream()
                .sorted(Comparator.comparing(h -> parseTimestamp(h.getPlacedDate(), "placedDate"),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()) {
            added.add(OverDriveBookbagEntity.builder()
                    .userId(userId).titleId(hold.getId())
                    .title(truncate(hold.getTitle(), 1024)).author(truncate(hold.getFirstCreatorName(), 255))
                    .position(position++)
                    .holdCardId(hold.getCardId())
                    .holdPlacedAt(parseTimestamp(hold.getPlacedDate(), "placedDate"))
                    .lastNote("Adopted from a hold already placed at " + hold.getCardId() + ".")
                    .build());
        }
        bookbagRepository.saveAll(added);
        log.info("OverDrive bookbag: adopted {} existing hold(s) for user {}", added.size(), userId);
        return added.size();
      }

      /**
       * Take a title out of the bag. Any hold placed for it is left in place: the user asked to stop
       * queueing it, not to give up a queue position they have already earned, and cancelling a hold
       * they may still want is not recoverable.
       */
      @Transactional
      public void removeFromBookbag(Long id) {
        Long userId = currentUserId();
        OverDriveBookbagEntity row = bookbagRepository.findById(id)
                .filter(e -> userId.equals(e.getUserId()))
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("That title is not in your bookbag."));
        bookbagRepository.delete(row);
        log.info("OverDrive bookbag: user {} removed \"{}\" ({})", userId, row.getTitle(), row.getTitleId());
      }

      /** Reorder the bag to the given entry ids, front first. Ids not belonging to the user are ignored. */
      @Transactional
      public List<BookbagEntry> reorderBookbag(List<Long> idsInOrder) {
        Long userId = currentUserId();
        List<OverDriveBookbagEntity> rows = bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        Map<Long, OverDriveBookbagEntity> byId = rows.stream()
                .collect(Collectors.toMap(OverDriveBookbagEntity::getId, e -> e, (a, b) -> a, LinkedHashMap::new));
        int position = 1;
        for (Long id : idsInOrder == null ? List.<Long>of() : idsInOrder) {
            OverDriveBookbagEntity row = byId.remove(id);
            if (row != null) {
                row.setPosition(position++);
            }
        }
        // Anything the client did not mention keeps its relative order, behind what it did.
        for (OverDriveBookbagEntity leftover : byId.values()) {
            leftover.setPosition(position++);
        }
        bookbagRepository.saveAll(rows);
        return listBookbag();
      }

      /** The windows a borrow rate is measured over, longest label first for readable messages. */
      enum BorrowWindow { // package-private for testing
        MINUTE("minute", java.time.Duration.ofMinutes(1)),
        HOUR("hour", java.time.Duration.ofHours(1)),
        DAY("day", java.time.Duration.ofDays(1)),
        WEEK("week", java.time.Duration.ofDays(7)),
        MONTH("30 days", java.time.Duration.ofDays(30));

        final String label;
        final java.time.Duration length;

        BorrowWindow(String label, java.time.Duration length) {
            this.label = label;
            this.length = length;
        }
      }

      /** How many checkouts a card has taken in each window. */
      public record BorrowRate(long lastMinute, long lastHour, long lastDay, long lastWeek,
                               long last30Days) {}

      /** A card's configured ceilings; null in any position means no ceiling is known for that window. */
      public record BorrowLimits(Integer perMinute, Integer perHour, Integer perDay, Integer perWeek,
                                 Integer perMonth) {

        static final BorrowLimits NONE = new BorrowLimits(null, null, null, null, null);

        /**
         * What a card gets before anybody configures it.
         *
         * <p>Unlimited was the wrong default. OverDrive will not say what it tolerates, so an
         * unconfigured card is not "known to be free" — it is unmeasured, and the cost of guessing
         * high is an account refused for days. The monthly figure is the one that matters: the
         * refusals measured on this deployment came at 144-148 borrows in a rolling thirty days, with
         * a peak of 153, so 100 sits comfortably below that band rather than probing its edge.
         *
         * <p>The shorter windows are burst insurance rather than measured limits. Nothing shorter than
         * a month correlated with any refusal here, but a bug that borrows in a loop — which has
         * happened once already — should hit a wall in minutes, not after a hundred checkouts.
         *
         * <p>They apply only while a card has no configured row. Once an administrator saves one, it
         * is taken literally, nulls included: an explicit blank means "no ceiling for that window",
         * which is how you opt a card out.
         */
        static final BorrowLimits DEFAULTS = new BorrowLimits(2, 5, 10, 30, 100);

        Integer forWindow(BorrowWindow window) {
            return switch (window) {
                case MINUTE -> perMinute;
                case HOUR -> perHour;
                case DAY -> perDay;
                case WEEK -> perWeek;
                case MONTH -> perMonth;
            };
        }

        boolean anySet() {
            return perMinute != null || perHour != null || perDay != null || perWeek != null
                    || perMonth != null;
        }
      }

      /** Actions that take a copy out — what a borrow ceiling counts. */
      private static final List<String> BORROW_ACTIONS = List.of("BORROW", "BORROW_AND_IMPORT");

      /**
       * Actions that give one back. Counted against the same ceilings as borrows, separately rather
       * than pooled: the numbers an administrator sets are read off borrow counts at the moment a
       * refusal happened, so pooling would silently halve the borrowing those numbers were chosen to
       * allow. A card doing N borrows and N returns per window is what they describe.
       */
      private static final List<String> RETURN_ACTIONS = List.of("RETURN", "AUTO_RETURN");

      /** Count this card's checkouts over every window, for reporting or for checking a ceiling. */
      public BorrowRate borrowRate(String identity) {
        return rateFor(identity, BORROW_ACTIONS);
      }

      /** Count this card's returns over every window. */
      public BorrowRate returnRate(String identity) {
        return rateFor(identity, RETURN_ACTIONS);
      }

      private BorrowRate rateFor(String identity, List<String> actions) {
        Instant now = Instant.now();
        // One query for all five, not one each: this runs inside per-title, per-library loops, and
        // every card has ceilings now that unconfigured ones fall back to defaults.
        List<Object[]> rows = auditRepository.countActionsPerWindow(identity, actions,
                now.minus(BorrowWindow.MINUTE.length), now.minus(BorrowWindow.HOUR.length),
                now.minus(BorrowWindow.DAY.length), now.minus(BorrowWindow.WEEK.length),
                now.minus(BorrowWindow.MONTH.length));
        if (rows.isEmpty() || rows.getFirst().length < 5) {
            return new BorrowRate(0, 0, 0, 0, 0);
        }
        Object[] counts = rows.getFirst();
        // SUM over no rows is null, and every column is null when the card has borrowed nothing.
        return new BorrowRate(asCount(counts[0]), asCount(counts[1]), asCount(counts[2]),
                asCount(counts[3]), asCount(counts[4]));
      }

      private static long asCount(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
      }

      long borrowsInWindow(BorrowRate rate, BorrowWindow window) { // package-private for testing
        return switch (window) {
            case MINUTE -> rate.lastMinute();
            case HOUR -> rate.lastHour();
            case DAY -> rate.lastDay();
            case WEEK -> rate.lastWeek();
            case MONTH -> rate.last30Days();
        };
      }

      /** A card's configured ceilings, or none when an administrator has not set any. */
      public BorrowLimits borrowLimits(String identity) {
        if (identity == null) {
            return BorrowLimits.NONE;
        }
        // No row means nobody has measured this card, which is a reason for caution rather than for
        // no ceiling at all. A saved row is taken exactly as saved.
        return cardLimitRepository.findById(identity)
                .map(l -> new BorrowLimits(l.getMaxPerMinute(), l.getMaxPerHour(),
                        l.getMaxPerDay(), l.getMaxPerWeek(), l.getMaxPerMonth()))
                .orElse(BorrowLimits.DEFAULTS);
      }

      /**
       * Ceilings for several cards in one query, defaulting the ones nobody has configured. Saves a
       * lookup per card where a whole card list is being described at once.
       */
      private Map<String, BorrowLimits> borrowLimitsFor(Collection<String> identities) {
        Map<String, BorrowLimits> byIdentity = new HashMap<>();
        for (OverDriveCardLimitEntity row : cardLimitRepository.findByIdentityIn(identities)) {
            byIdentity.put(row.getIdentity(), new BorrowLimits(row.getMaxPerMinute(), row.getMaxPerHour(),
                    row.getMaxPerDay(), row.getMaxPerWeek(), row.getMaxPerMonth()));
        }
        identities.forEach(id -> byIdentity.putIfAbsent(id, BorrowLimits.DEFAULTS));
        return byIdentity;
      }

      /** The ceilings a card falls back on until an administrator sets its own. */
      public BorrowLimits defaultBorrowLimits() {
        return BorrowLimits.DEFAULTS;
      }

      /**
       * Which ceiling this card has reached, or null when it is free to borrow.
       *
       * <p>Checked shortest window first, so the message names the one that will clear soonest: being
       * told "3 this minute" is more useful than "40 this week" when both are full.
       *
       * <p>One counting query, covering every window at once. It used to be one per window, on the
       * reasoning that almost no card would have any configured — which stopped being true the moment
       * unconfigured cards started falling back to defaults.
       */
      private String borrowCeilingReached(String identity) {
        return borrowCeilingReached(identity, borrowLimits(identity));
      }

      /** As above, for a caller that has already looked the ceilings up — see {@link #listCards()}. */
      private String borrowCeilingReached(String identity, BorrowLimits limits) {
        return ceilingReached(limits, () -> borrowRate(identity), "borrows");
      }

      /**
       * Which ceiling this card's returns have reached, or null. Same numbers as borrows, counted
       * separately — see {@link #RETURN_ACTIONS}.
       */
      private String returnCeilingReached(String identity, BorrowLimits limits) {
        return ceilingReached(limits, () -> returnRate(identity), "returns");
      }

      private String ceilingReached(BorrowLimits limits, java.util.function.Supplier<BorrowRate> rate,
                                    String noun) {
        if (!limits.anySet()) {
            return null;
        }
        BorrowRate counted = rate.get();
        for (BorrowWindow window : BorrowWindow.values()) {
            Integer ceiling = limits.forWindow(window);
            if (ceiling == null) {
                continue;
            }
            long taken = borrowsInWindow(counted, window);
            if (taken >= ceiling) {
                return taken + " of " + ceiling + " " + noun + " this " + window.label;
            }
        }
        return null;
      }

      /**
       * Record what the account was actually doing when OverDrive refused it for churning.
       *
       * <p>The refusal carries no numbers — "too many titles within a short period of time" — so the
       * only way to learn the real ceiling is to write down the rate at the moment it was hit. Several
       * of these, across a few incidents, are what a per-card limit can then be set from.
       */
      private void recordBorrowRateAtLimit(String identity) {
        try {
            BorrowRate rate = borrowRate(identity);
            String detail = String.format(
                    "Churning limit hit on card %s. Borrows in the preceding minute/hour/day/week/30 days: "
                            + "%d/%d/%d/%d/%d.",
                    identity, rate.lastMinute(), rate.lastHour(), rate.lastDay(), rate.lastWeek(),
                    rate.last30Days());
            log.warn("OverDrive: {} Set a per-card limit below these numbers to stay under it.", detail);
            recordAudit(OverDriveAuditAction.CARD_REFRESHED, identity, null, null, null, null, detail, false);
        } catch (Exception e) {
            // Diagnostics must never be what turns a refused borrow into a broken pass.
            log.debug("OverDrive: could not measure the borrow rate for {} ({})", identity, e.getMessage());
        }
      }

      /**
       * Whether a failure is OverDrive refusing an account for borrowing and returning too much.
       *
       * <p>Matched on the error code rather than the prose: the {@code userExplanation} is a sentence
       * meant for a person and can be reworded, while {@code PatronExceededChurningLimit} is the
       * contract. Walks the cause chain and the message text for the same reason
       * {@link #isMissingChip} does — the call sites wrap failures in an APIException carrying the
       * original body.
       */
      static boolean isChurningLimit(Throwable e) { // package-private for testing
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException r
                    && r.getResponseBodyAsString().contains("PatronExceededChurningLimit")) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("PatronExceededChurningLimit")) {
                return true;
            }
        }
        return false;
      }

      /**
       * Stand a card down after OverDrive flagged it for churning, if it is not already resting.
       *
       * <p>Deliberately does not extend an existing cooldown. A pass that trips the limit on several
       * titles would otherwise push the end date out once per title, turning one week into a month for
       * an account that has already stopped acting.
       */
      private void beginChurnCooldown(String identity) {
        // Every row for this identity, not just the caller's: the limit belongs to the library patron,
        // so a card three users hold is one flagged account and all of their rows rest together. This
        // is what V927's backfill already assumes.
        List<OverDriveTokenEntity> rows = tokenRepository.findByIdentity(identity);
        if (rows.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        if (rows.stream().anyMatch(r -> r.getChurnCooldownUntil() != null && r.getChurnCooldownUntil().isAfter(now))) {
            return; // already resting; see the note on not extending an existing cooldown
        }
        Instant until = now.plus(CHURN_COOLDOWN);
        rows.forEach(row -> row.setChurnCooldownUntil(until));
        tokenRepository.saveAll(rows);
        log.warn("OverDrive: card {} hit the churning limit; borrows and returns on it are paused until {} "
                + "for all {} user(s) holding it", identity, until, rows.size());
        recordAudit(OverDriveAuditAction.CARD_REFRESHED, identity, null, null, null, null,
                "OverDrive reported too many titles borrowed and returned. Borrows and returns on this "
                        + "card are paused until " + until + ".", false);
      }

      /**
       * When this card may act again, or null if it is free to act now. Read across every row for the
       * identity, and the latest wins: a cooldown recorded against the owner has to stop a sharee too,
       * or the account carries on being churned by whoever did not trip it.
       */
      private Instant churnCooldownUntil(String identity) {
        if (identity == null) {
            return null;
        }
        Instant now = Instant.now();
        return tokenRepository.findByIdentity(identity).stream()
                .map(OverDriveTokenEntity::getChurnCooldownUntil)
                .filter(Objects::nonNull)
                .filter(until -> until.isAfter(now))
                .max(Instant::compareTo)
                .orElse(null);
      }

      /**
       * Refuse an action that would borrow or return on a card OverDrive has flagged for churning.
       *
       * <p>Applies to a person clicking as well as to the poller. The account is the thing being
       * rested, and a manual return during the cooldown is the same churn signal as an automatic one —
       * the message says when it lifts so the refusal is actionable rather than mysterious.
       */
      private void assertNotChurnLimited(String identity, String action) {
        Instant until = churnCooldownUntil(identity);
        if (until != null) {
            throw ApiError.CONFLICT.createException(
                    "OverDrive reported too many titles borrowed and returned on this card, so " + action
                            + " is paused until " + until + ". Syncing still works, and the card resumes on "
                            + "its own — nothing needs doing.");
        }
      }

      static boolean isMissingChip(Throwable e) { // package-private for testing
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof org.springframework.web.client.RestClientResponseException r
                    && "missing_chip".equals(firstMatch(RESULT_PATTERN, r.getResponseBodyAsString()))) {
                return true;
            }
            // The Libby call sites wrap their failures in an APIException whose message carries the
            // original 403 body, so match on that too rather than relying on the cause chain surviving.
            if (t.getMessage() != null && t.getMessage().contains("missing_chip")) {
                return true;
            }
        }
        return false;
      }

      /**
       * Run a Libby call for a card, healing a dead chip the way {@link #fetchFulfillment} does — so every
       * endpoint (sync, borrow, hold, return) recovers on its own instead of surfacing a bare 403:
       * <ol>
       *   <li>Re-link up front from the stored card + PIN when the stored token has already expired.</li>
       *   <li>On a {@code 403 {"result":"missing_chip"}}, re-mint the identity ({@link #refreshIdentity}),
       *       persist it, and retry once.</li>
       *   <li>If that still returns {@code missing_chip} the chip itself is gone: silently re-link from the
       *       stored credentials ({@link #relinkCard}) and retry once more.</li>
       * </ol>
       * Cards that can't re-link (setup-code / pasted-token links, or no credential key configured) surface
       * a message saying so rather than the raw 403. A card shared with the caller renews too — the re-link
       * writes the owner's row, which is the row the share points at.
       */
      private <T> T withChipRecovery(String identity, java.util.function.Function<String, T> call) {
        String token = tokenRenewedIfExpired(identity);
        try {
            return call.apply(token);
        } catch (RuntimeException first) {
            if (!isMissingChip(first)) {
                throw first;
            }
            log.info("OverDrive: card {} returned missing_chip — re-minting its identity and retrying", identity);
            String refreshed = refreshIdentity(token);
            if (refreshed != null && !refreshed.equals(token)) {
                persistReMintedToken(identity, refreshed);
                try {
                    return call.apply(refreshed);
                } catch (RuntimeException second) {
                    if (!isMissingChip(second)) {
                        throw second;
                    }
                }
            }
            String relinked = relinkCard(identity);
            if (relinked == null) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive rejected this card's saved sign-in (missing_chip) and it "
                        + "couldn't be renewed automatically — it has no stored card + PIN credentials (set "
                        + "OVERDRIVE_CREDENTIAL_KEY and link by card + PIN), or the re-link failed. Unlink the "
                        + "card and link it again. Original error: " + first.getMessage(), first);
            }
            return call.apply(relinked);
        }
      }

      /**
       * The token to call Libby with for a card: the stored one, or — when it has already expired and the
       * card has card + PIN on file — a freshly re-linked one. This is the proactive half of
       * {@link #withChipRecovery}; a token that dies before its stated expiry is handled reactively there.
       */
      private String tokenRenewedIfExpired(String identity) {
        OverDriveTokenEntity row = accessibleTokenRow(currentUserId(), identity).orElse(null);
        String token = row != null ? row.getToken() : null;
        if (token == null || token.isBlank()) {
            // Same failure resolveToken raises, so callers see one consistent "connect your account" error.
            return resolveToken(identity);
        }
        Long expiresAt = row.getExpiresAt();
        if (expiresAt != null && expiresAt <= Instant.now().getEpochSecond()) {
            String relinked = relinkCard(identity);
            if (relinked != null) {
                log.info("OverDrive: stored token for card {} had expired — renewed from stored credentials", identity);
                return relinked;
            }
        }
        return token;
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
                        // Freshly read from a live sync during linking — always the current user's own card.
                        // Credential/expiry details aren't known here; reloadCards() (listCards) is authoritative.
                        cards.add(new OverDriveCard(card.get("cardId").toString(), name, libraryKey, false, null, null,
                                true, null, 0, false, null, null, null, null));
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
      public OverDriveSyncResponse sync(String identity) {
        return withChipRecovery(identity, token -> syncWith(identity, token));
      }

      /**
       * Sync many cards with one upstream call per <em>chip</em>, not per card.
       *
       * <p>Libby's {@code /chip/sync} takes no card parameter: it is scoped to the chip the bearer token
       * authenticates, and returns every card on that chip with each loan and hold tagged by its owning
       * {@code cardId}. Cards linked together — via a setup code or a pasted identity token — therefore
       * share one token, and syncing them one at a time re-fetches the identical payload once per card.
       * Grouping by token collapses that to a single call per chip, which is what the Libby web client
       * itself does.
       *
       * @return each requested identity mapped to its chip's sync response; cards whose sync failed (or
       *         that have no stored token) are absent, so one dead card can't fail the whole set.
       */
      public Map<String, OverDriveSyncResponse> syncAll(List<String> identities) {
        if (identities == null || identities.isEmpty()) {
            return Map.of();
        }
        Long userId = currentUserId();

        // Group the requested cards by the token that authenticates them — one group per chip.
        Map<String, List<String>> cardsByToken = new LinkedHashMap<>();
        for (String identity : identities.stream().filter(Objects::nonNull).distinct().toList()) {
            String token = accessibleTokenRow(userId, identity)
                    .map(OverDriveTokenEntity::getToken)
                    .filter(t -> !t.isBlank())
                    .orElse(null);
            if (token == null) {
                log.debug("OverDrive syncAll: no stored token for card {}; skipping", identity);
                continue;
            }
            cardsByToken.computeIfAbsent(token, t -> new ArrayList<>()).add(identity);
        }

        Map<String, OverDriveSyncResponse> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : cardsByToken.entrySet()) {
            List<String> group = entry.getValue();
            // Any card in the group can stand in for the chip; recovery/re-mint is keyed off it.
            String representative = group.getFirst();
            OverDriveSyncResponse resp;
            try {
                resp = sync(representative);
            } catch (RuntimeException e) {
                log.warn("OverDrive syncAll: chip sync via card {} failed ({}); its {} card(s) will be absent",
                        representative, e.getMessage(), group.size());
                continue;
            }
            propagateReMintedToken(userId, entry.getKey(), group);
            for (String identity : group) {
                out.put(identity, resp);
            }
        }
        log.info("OverDrive syncAll: {} card(s) covered by {} chip sync call(s)", out.size(), cardsByToken.size());
        return out;
      }

      /**
       * After a grouped sync, copy a re-minted token to the chip's other cards. {@link #withChipRecovery}
       * persists a refreshed identity only against the card it was called with; the siblings share the
       * same chip, so leaving them on the dead token would make each of them re-mint in turn.
       */
      private void propagateReMintedToken(Long userId, String originalToken, List<String> group) {
        if (group.size() < 2) {
            return;
        }
        String current = accessibleTokenRow(userId, group.getFirst())
                .map(OverDriveTokenEntity::getToken)
                .orElse(null);
        if (current == null || current.isBlank() || current.equals(originalToken)) {
            return;
        }
        for (String identity : group.subList(1, group.size())) {
            persistReMintedToken(identity, current);
        }
        log.info("OverDrive: propagated re-minted chip token to {} sibling card(s)", group.size() - 1);
      }

      private OverDriveSyncResponse syncWith(String identity, String authToken) {
        String url = sentryBaseUrl + "/chip/sync";
        HttpHeaders headers = libbyHeaders(authToken);

        try {
            ResponseEntity<OverDriveSyncResponse> resp = restClient.get()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .retrieve()
                    .toEntity(OverDriveSyncResponse.class);

            OverDriveSyncResponse syncResp = resp.getBody();
            if (syncResp != null) {
                 Long userId = currentUserId();
                 if (syncResp.getLoans() != null) {
                     for (OverDriveLoan loan : syncResp.getLoans()) {
                        // The sync is chip-scoped, so it can carry loans belonging to sibling cards.
                        // Attribute each to the card it actually sits on, falling back to the card we
                        // called with only when the feed omits the tag.
                        String loanCard = loan.getCardId() != null && !loan.getCardId().isBlank()
                                ? loan.getCardId()
                                : identity;
                        persistLoan(userId, loanCard, loan);
                     }
                 }
                 // Outside the null check on purpose: a user who has just returned their last book gets
                 // a feed with no loans at all, and that is precisely when there is a row to retire.
                 reconcileLoans(userId, syncResp, identity);
             }

            log.info("OverDrive sync: {} loans, {} holds",
                    syncResp != null && syncResp.getLoans() != null ? syncResp.getLoans().size() : 0,
                    syncResp != null && syncResp.getHolds() != null ? syncResp.getHolds().size() : 0);
            return syncResp;
         } catch (org.springframework.web.client.ResourceAccessException e) {
            // Transient connectivity error reaching Libby (I/O / connection reset) — a clean gateway
            // error the client can simply retry, not a server bug worth a full stack trace.
            log.warn("OverDrive sync: transient I/O error reaching Libby ({}); retryable.", e.getMessage());
            throw ApiError.OVERDRIVE_UNREACHABLE.createException(e.getMessage());
         } catch (Exception e) {
            log.error("OverDrive sync failed: {}", e.getMessage());
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException(e, "OverDrive sync failed: " + e.getMessage());
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

        // A loan id is a title id, so borrowing the same title again reuses this row. If it was last
        // seen returned or expired, this is a new loan wearing an old row: the import and auto-return
        // state left over from the previous checkout describes a book we no longer hold, and leaving it
        // in place would make a freshly borrowed title look instantly due for return.
        boolean reborrowed = isTerminalLoanState(entity.getState());
        if (reborrowed) {
            entity.setFulfilled(false);
            entity.setAutoImportFailures(0);
            entity.setAutoReturnDueAt(null);
        }

        entity.setTitle(loan.getTitle());
        if (loan.getCreators() != null && !loan.getCreators().isEmpty()) {
            entity.setAuthor(loan.getCreators().stream()
                    .map(c -> c.getName())
                    .collect(Collectors.joining(", ")));
         }
        // Age the loan from when it was actually checked out, not from when this row happened to be
        // written. The two differ for a loan borrowed in the Libby app and first seen here days later,
        // and for the re-borrow above — and auto-return measures the minimum age against this.
        Instant checkedOutAt = parseTimestamp(loan.getCheckoutDate(), "checkoutDate");
        if (checkedOutAt != null) {
            entity.setCreatedAt(checkedOutAt);
        } else if (reborrowed || entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        entity.setExpireDate(parseTimestamp(loan.getExpireDate(), "expireDate"));
        entity.setFormatId(loan.getFormat() != null ? loan.getFormat().getId() : null);
        entity.setState("BORROWED");
        entity.setLastSync(Instant.now());

        loanRepository.save(entity);
      }

      /** States meaning "we no longer hold this loan"; the row is history, not a live checkout. */
      private static boolean isTerminalLoanState(String state) {
        return "RETURNED".equals(state) || "EXPIRED".equals(state);
      }

      /**
       * Bring the loan cache back in line with what the user actually holds.
       *
       * <p>{@link #persistLoan} only ever writes rows, so a loan returned in the Libby app, expired on
       * its own, or handed back by another client leaves a row behind claiming it is still borrowed.
       * Those stale rows are why the automation had to be taught to filter every decision through the
       * live sync, and they are what the loan list and history show the user.
       *
       * <p>Scoped to the cards this response actually covers. A chip sync returns every card on the
       * chip and tags each loan, so absence from the feed is real evidence for those cards — but a card
       * on another chip, or one whose sync failed, is simply unrepresented here, and marking its loans
       * returned on that basis would be inventing history. Loans already in a terminal state are left
       * alone.
       *
       * @return how many rows were reconciled
       */
      private int reconcileLoans(Long userId, OverDriveSyncResponse response, String calledWith) {
        Set<String> covered = response.getCards() == null || response.getCards().isEmpty()
                ? new HashSet<>(Collections.singletonList(calledWith))
                : response.getCards().stream()
                        .map(OverDriveSyncResponse.Card::getCardId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(HashSet::new));
        covered.remove(null);
        if (covered.isEmpty()) {
            return 0;
        }
        Set<String> live = response.getLoans() == null ? Set.of()
                : response.getLoans().stream()
                        .map(OverDriveLoan::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

        Instant now = Instant.now();
        int reconciled = 0;
        for (OverDriveLoanEntity row : loanRepository.findByUserId(userId)) {
            if (!covered.contains(row.getIdentity())
                    || live.contains(row.getOverdriveLoanId())
                    || isTerminalLoanState(row.getState())) {
                continue;
            }
            // Expired vs returned is a guess either way — OverDrive doesn't say which happened, it just
            // stops listing the loan. Its own expiry date is the one piece of evidence available, so a
            // loan that ran past it is recorded as expired and everything else as returned.
            boolean expired = row.getExpireDate() != null && row.getExpireDate().isBefore(now);
            row.setState(expired ? "EXPIRED" : "RETURNED");
            row.setLastSync(now);
            loanRepository.save(row);
            reconciled++;
        }
        if (reconciled > 0) {
            log.info("OverDrive sync: reconciled {} stale loan row(s) no longer held on {} card(s)",
                    reconciled, covered.size());
        }
        return reconciled;
      }

      /**
       * Parse an OverDrive timestamp into an {@link Instant}, tolerating the offset-based formats
       * OverDrive can return. Returns null (rather than aborting the whole sync) when the value is
       * missing or unparseable.
       *
       * @param field the field being parsed, so an unparseable value says which one it was
       */
      private Instant parseTimestamp(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
         }
        try {
            return Instant.parse(value);
         } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
             } catch (DateTimeParseException ex) {
                log.warn("Unparseable OverDrive {} '{}', leaving null", field, value);
                return null;
             }
         }
      }

      /**
       * POST /card/{cardId}/loan/{loanId}/fulfill/ebook-epub-adobe
       * Returns the ACSM fulfillment token content as base64.
       */
      public String fulfill(String identity, String loanId) {
        // Fulfill with the stored identity as-is. Don't pre-mint: the web client fulfills with its
        // stored identity and re-mints only reactively when the endpoint returns missing_chip, which
        // fetchFulfillment already handles. Minting up front adds needless chip churn.
        String authToken = tokenRenewedIfExpired(identity);
        byte[] body;
        try {
            body = fetchFulfillment(identity, authToken, loanId, FORMAT_EPUB_ADOBE);
        } catch (RuntimeException e) {
            recordAuditFailure(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, e.getMessage());
            throw e;
        }
        if (body == null || body.length == 0) {
            log.warn("OverDrive fulfill returned empty body for loan {}", loanId);
            recordAuditFailure(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, "Empty fulfillment body");
            return null;
        }

        // Mark loan as fulfilled on the current user's cached row. Scope by user (not just identity):
        // a shared card caches the same loan under both the owner and each sharee, so an identity-only
        // lookup would match multiple rows.
        loanRepository.findByUserIdAndOverdriveLoanId(currentUserId(), loanId)
                .ifPresent(entity -> {
                    entity.setFulfilled(true);
                    loanRepository.save(entity);
                });

        recordAudit(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, null, null, null);
        log.info("OverDrive loan fulfilled: {} bytes for loan {}", body.length, loanId);
        return Base64.getEncoder().encodeToString(body);
      }

      /**
       * POST /card/{cardId}/loan/{titleId} — borrow a title by its OverDrive title id.
       * Returns the created loan id.
       */
      public String borrow(String identity, String titleId, String titleFormat) {
        assertCanBorrow(identity);
        try {
            Map<String, Object> loan = withChipRecovery(identity,
                    token -> borrowLoan(identity, token, titleId, titleFormat));
            Object id = loan.get("id");
            String loanId = id != null ? id.toString() : null;
            recordAudit(OverDriveAuditAction.BORROW, identity, titleId, loanId, null,
                    loan.get("title") != null ? loan.get("title").toString() : null, null);
            return loanId;
        } catch (RuntimeException e) {
            recordAuditFailure(OverDriveAuditAction.BORROW, identity, titleId, null, titleOf(titleId), e.getMessage());
            throw e;
        }
      }

      /**
       * Map a media-type / format hint to OverDrive's {@code title_format} — the Libby web client sends
       * "audiobook" for audiobooks, "magazine" for magazines, "ebook" otherwise. Accepts either a media
       * type ("audiobook") or a format id ("audiobook-mp3"); defaults to "ebook" when unknown.
       */
      private static String normalizeTitleFormat(String hint) {
        if (hint == null || hint.isBlank()) {
            return "ebook";
        }
        String h = hint.toLowerCase(Locale.ROOT);
        if (h.contains("audiobook")) {
            return "audiobook";
        }
        if (h.contains("magazine")) {
            return "magazine";
        }
        return "ebook";
      }

      /**
       * Borrow a title and return the raw loan object (which includes the available {@code formats}).
       * {@code titleFormatHint} is a media type or format id used to set the borrow's {@code title_format}
       * (audiobooks must borrow as "audiobook", not "ebook").
       */
      private Map<String, Object> borrowLoan(String cardId, String authToken, String titleId, String titleFormatHint) {
        String url = sentryBaseUrl + "/card/" + cardId + "/loan/" + titleId;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", LOAN_PERIOD_DAYS);
        body.put("units", "days");
        body.put("title_format", normalizeTitleFormat(titleFormatHint));

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
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive borrow failed: no loan id returned");
             }
            // Log the discriminators that tell a genuine new checkout from an idempotent re-borrow of an
            // already-existing loan (borrow is idempotent — re-borrowing a held title returns the
            // existing loan). A fresh checkout returns isFormatLockedIn=false and a checkoutDate ≈ now;
            // a re-borrow returns the original (older) checkoutDate. Handy for diagnosing loan-state
            // issues (distinct from a "whoa", which is a re-mint shibboleth problem — see refreshIdentity).
            log.info("OverDrive title borrowed: {} (title {}) — checkoutId={}, checkoutDate={}, "
                    + "isFormatLockedIn={}, formats={}",
                    bodyMap.get("id"), titleId, bodyMap.get("checkoutId"), bodyMap.get("checkoutDate"),
                    bodyMap.get("isFormatLockedIn"), loanFormatIds(bodyMap));
            return bodyMap;
         } catch (Exception e) {
            log.error("OverDrive borrow failed: {}", e.getMessage());
            // Thunder refusing the account for churning is not a transient failure to retry — it is a
            // request to stop. Stand the card down before the error propagates, so the rest of this
            // pass and every pass until the cooldown lifts leaves it alone.
            if (isChurningLimit(e)) {
                // Measure before resting the card: the point is to learn what rate tripped it.
                recordBorrowRateAtLimit(cardId);
                beginChurnCooldown(cardId);
            }
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException(e, "OverDrive borrow failed: " + e.getMessage());
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
      private LoanRef findActiveLoan(String cardId, String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }
        try {
            OverDriveSyncResponse synced = sync(cardId);
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
            HttpResponse<byte[]> resp = httpClient.send(
                    apiRequest(url, authToken).build(), HttpResponse.BodyHandlers.ofByteArray());
            String body = resp.body() != null ? new String(resp.body(), StandardCharsets.UTF_8) : "";
            String result = firstMatch(RESULT_PATTERN, body);
            log.info("OverDrive fulfill {} loan {}: status {}{}", formatId, loanId, resp.statusCode(),
                    result != null ? " result=" + result : "");

            if ("missing_chip".equals(result)) {
                // Chip not yet registered for fulfillment — re-mint and retry once (as the web client does).
                // refreshIdentity sends the Accept-Language shibboleth so the re-minted identity is
                // prbn-absent (fulfillment-ready) rather than prbn=v. Persist it so later fulfillments
                // REUSE it instead of re-minting again — the web client re-mints once and keeps the result.
                String refreshed = refreshIdentity(authToken);
                persistReMintedToken(cardId, refreshed);
                authToken = refreshed;
                resp = httpClient.send(
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
                    resp = httpClient.send(
                            apiRequest(url, relinked).build(), HttpResponse.BodyHandlers.ofByteArray());
                    body = resp.body() != null ? new String(resp.body(), StandardCharsets.UTF_8) : "";
                    result = firstMatch(RESULT_PATTERN, body);
                    log.info("OverDrive fulfill {} loan {} (post-relink): status {}{}", formatId, loanId,
                            resp.statusCode(), result != null ? " result=" + result : "");
                }
            }

            // "whoa" = the identity we're fulfilling with is stamped prbn=v, which means its /chip
            // re-mint was made without the correct Accept-Language shibboleth (see refreshIdentity/
            // chipShibboleth). Since we now send that shibboleth, this should be rare; if it recurs, the
            // shibboleth computation is off (e.g. path length / token) or the token was minted elsewhere.
            // It is NOT a rate limit — no retry/cooldown helps; the re-mint must carry the shibboleth.
            if ("whoa".equals(result)) {
                String retryAfter = resp.headers().firstValue("retry-after").orElse(null);
                String reqId = resp.headers().firstValue("x-request-id").orElse(null);
                String date = resp.headers().firstValue("date").orElse(null);
                log.warn("OverDrive fulfill \"whoa\" (prbn=v — chip re-mint lacked a valid shibboleth) — "
                        + "loan {}, format {}, status {}; retry-after={}, x-request-id={}, date={}; body={}; headers={}",
                        loanId, formatId, resp.statusCode(), retryAfter, reqId, date, body,
                        resp.headers().map());
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive refused this fulfillment (\"whoa\") for loan " + loanId
                        + ": the identity is not fulfillment-capable (prbn=v). Re-link this card so a fresh "
                        + "identity is minted; if it persists, the chip shibboleth may need updating.");
            }

            String href = firstMatch(HREF_PATTERN, body);
            if (href != null && !href.isBlank()) {
                return downloadContent(href, loanId);
            }
            // Some formats may return the bytes directly rather than a JSON href.
            if (resp.statusCode() < 300 && resp.body() != null && resp.body().length > 0 && !body.startsWith("{")) {
                return resp.body();
            }
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive fulfill failed (status " + resp.statusCode()
                    + (result != null ? ", result=" + result : "") + ") for loan " + loanId + bodySnippet(resp.body()));
        } catch (IOException e) {
            log.error("OverDrive fulfill IO error for loan {}: {}", loanId, e.getMessage());
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive fulfill failed for loan " + loanId + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.INTERNAL_SERVER_ERROR.createException("OverDrive fulfill interrupted for loan " + loanId);
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
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        log.info("OverDrive content download ({}): status {}", safeHost(href), resp.statusCode());
        if (resp.statusCode() >= 400 || resp.body() == null || resp.body().length == 0) {
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive content download failed (" + resp.statusCode()
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
        var appSettings = appSettingService.getAppSettings();
        MetadataProviderSettings settings = appSettings != null ? appSettings.getMetadataProviderSettings() : null;
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
        return selectFormat(loanFormats, formatPreference(), acsmHandler.isConfigured(),
                audiobookHandler.isConfigured(), ebookHandler.isConfigured());
      }

      /**
       * The format grimmory would actually fulfill/import from the offered format ids (preferred
       * importable one; null when none are importable). Used to label a loan with its real format.
       */
      public String chooseImportFormat(List<String> offeredFormatIds) {
        return chooseFormat(offeredFormatIds);
      }

      /**
       * A format we can actually import: open ebook formats always, Adobe ebook formats only with an
       * ACSM handler, audiobook formats only with an audiobook handler, and Libby's read-in-browser
       * {@code ebook-overdrive} only with an ebook handler.
       */
      private boolean isImportableFormat(String formatId) {
        if (isAudiobookFormat(formatId)) {
            return audiobookHandler.isConfigured();
        }
        if (isEbookHandlerFormat(formatId)) {
            return ebookHandler.isConfigured();
        }
        return SUPPORTED_FORMATS.contains(formatId) && (isOpenFormat(formatId) || acsmHandler.isConfigured());
      }

      /**
       * Why nothing could be imported, phrased for the formats this loan actually offers. Naming a
       * handler the operator could configure is only useful when a format needing it is on the table —
       * a title offered solely as {@code ebook-overdrive}/{@code ebook-kobo} (Libby's read-in-browser
       * and Kobo hand-off formats) has no Adobe format, so pointing at the ACSM handler would send the
       * operator chasing a setting that cannot help.
       */
      static String noImportableFormatMessage(String loanId, List<String> formats) { // package-private for testing
        List<String> fixes = new ArrayList<>();
        if (formats.stream().anyMatch(f -> SUPPORTED_FORMATS.contains(f) && !isOpenFormat(f))) {
            fixes.add("Adobe formats require a configured external ACSM handler.");
        }
        if (formats.stream().anyMatch(OverDriveService::isAudiobookFormat)) {
            fixes.add("Audiobook formats require a configured audiobook handler.");
        }
        if (formats.stream().anyMatch(OverDriveService::isMagazineFormat)) {
            fixes.add("Magazine formats require a configured magazine handler.");
        }
        if (formats.stream().anyMatch(OverDriveService::isEbookHandlerFormat)) {
            fixes.add("Libby's read-in-browser format requires a configured ebook handler.");
        }
        String detail = fixes.isEmpty()
                ? "None of these can be downloaded — Grimmory imports "
                        + String.join(", ", SUPPORTED_FORMATS) + " and audiobook formats."
                : String.join(" ", fixes);
        return "No importable format for this title (loan " + loanId + "). Offered: " + formats + ". " + detail;
      }

      /** Backwards-compatible overload (ebook-only) — no audiobook or read-in-browser ebook handler. */
      static String selectFormat(List<String> loanFormats, List<String> preference, boolean acsmHandlerReady) {
        return selectFormat(loanFormats, preference, acsmHandlerReady, false, false);
      }

      /** Backwards-compatible overload — no read-in-browser ebook handler. */
      static String selectFormat(List<String> loanFormats, List<String> preference, boolean acsmHandlerReady,
                                 boolean audiobookHandlerReady) {
        return selectFormat(loanFormats, preference, acsmHandlerReady, audiobookHandlerReady, false);
      }

      /**
       * Pure selection: the first preferred ebook format the loan offers that is fulfillable; failing
       * that, an offered audiobook format when an audiobook handler is ready. A loan is one medium
       * (ebook OR audiobook), so the two never compete — audiobook titles carry no ebook formats.
       *
       * <p>{@code ebook-overdrive} is tried only after every real download format: it is Libby's
       * read-in-browser format, which the ebook handler must rebuild into a file from the web-reader
       * assets, so an open or Adobe format is always preferred when the loan offers one.
       */
      static String selectFormat(List<String> loanFormats, List<String> preference, boolean acsmHandlerReady,
                                 boolean audiobookHandlerReady, boolean ebookHandlerReady) {
        for (String preferred : preference) {
            if (!loanFormats.contains(preferred)) {
                continue;
            }
            if (!isOpenFormat(preferred) && !acsmHandlerReady) {
                continue; // Adobe format but no ACSM handler to procure it.
            }
            return preferred;
        }
        if (ebookHandlerReady && loanFormats.contains(FORMAT_EBOOK_OVERDRIVE)) {
            return FORMAT_EBOOK_OVERDRIVE;
        }
        if (audiobookHandlerReady) {
            if (loanFormats.contains(FORMAT_AUDIOBOOK_MP3)) {
                return FORMAT_AUDIOBOOK_MP3;
            }
            for (String f : loanFormats) {
                if (isAudiobookFormat(f)) {
                    return f;
                }
            }
        }
        return null;
      }

      /** The built-in default format preference (exposed for tests). */
      static List<String> defaultFormatPreference() {
        return DEFAULT_FORMAT_PREFERENCE;
      }

      /**
       * Search the OverDrive catalog scoped to a specific set of the current user's cards. At least one
       * card is required — searching without a card returns nothing. Each card id is resolved to its
       * library key (ignoring unknown/foreign ids); the search runs across the distinct union of those
       * keys. Backed by the read-only Thunder catalog API (no auth); results carry the title id needed
       * to {@link #borrowAndImport}.
       *
       * <p>Unlike a plain dedupe, a title surfacing from several libraries is <b>merged</b>: it keeps
       * one {@link OverDriveLibraryAvailability} per library so the UI can tell which cards can borrow it
       * now vs only hold it. The scalar availability fields become the aggregate across those libraries.
       */
      /** Default number of merged catalog results returned before "load more" fetches the next window. */
      private static final int DEFAULT_CATALOG_LIMIT = 60;

      public List<OverDriveCatalogItem> searchCatalog(String query, List<String> cardIds) {
        return searchCatalog(query, cardIds, null, false, null, DEFAULT_CATALOG_LIMIT);
      }

      /**
       * Catalog search with optional server-side facet filters pushed into the Thunder query so a broad
       * query's capped result page is narrowed before it is returned (rather than trimmed before the
       * client can filter it). {@code mediaTypes} restricts the medium ("ebook"/"audiobook"; blank =
       * both), {@code availableOnly} keeps only titles borrowable now, {@code language} restricts to an
       * ISO language code. Abridged has no Thunder facet, so it stays a client-side filter.
       *
       * <p>{@code limit} bounds the number of merged results returned; the UI starts small and re-requests
       * a larger limit on "load more". A returned count equal to {@code limit} means there may be more.
       */
      public List<OverDriveCatalogItem> searchCatalog(String query, List<String> cardIds, String mediaTypes,
                                                      boolean availableOnly, String language, int limit) {
        if (cardIds == null || cardIds.isEmpty()) {
            return List.of();
        }
        int effectiveLimit = limit > 0 ? limit : DEFAULT_CATALOG_LIMIT;
        String effectiveMediaTypes = (mediaTypes == null || mediaTypes.isBlank()) ? "ebook,audiobook,magazine" : mediaTypes.trim();
        // Scope strictly to the selected cards' libraries (cards the user owns or that are shared with them).
        Long userId = currentUserId();
        Set<String> libraryKeys = new LinkedHashSet<>();
        for (String cardId : cardIds) {
            accessibleTokenRow(userId, cardId)
                    .map(OverDriveTokenEntity::getLibraryKey)
                    .filter(k -> k != null && !k.isBlank())
                    .ifPresent(libraryKeys::add);
        }

        // A query that is an OverDrive title id (or a Libby/OverDrive URL containing one) looks the
        // title up directly at each library rather than running a text search.
        String titleId = extractTitleId(query);

        // Merge each title across libraries, accumulating one availability entry per library.
        Map<String, OverDriveCatalogItem> byTitleId = new LinkedHashMap<>();
        for (String libraryKey : libraryKeys) {
            List<OverDriveApiResponse.Item> items;
            if (titleId != null) {
                OverDriveApiResponse.Item item = overDriveParser.fetchTitleAtLibrary(libraryKey, titleId);
                items = item != null ? List.of(item) : List.of();
            } else {
                // Fetch up to the requested window per library. Server-side facet narrowing (availability/
                // language) is applied when requested; media types may still be narrowed by a format filter.
                // Audiobooks surface alongside ebooks regardless — only importing needs the audiobook handler.
                items = overDriveParser.searchLibrary(libraryKey, query, effectiveMediaTypes,
                        availableOnly, language, effectiveLimit);
            }
            for (OverDriveApiResponse.Item item : items) {
                if (item == null) {
                    continue;
                }
                OverDriveCatalogItem mapped = toCatalogItem(libraryKey, item);
                if (mapped.title() == null || mapped.title().isBlank() || mapped.titleId() == null) {
                    continue;
                }
                byTitleId.merge(mapped.titleId(), mapped, OverDriveService::mergeCatalogItems);
            }
        }
        // Cap the merged, deduped set to the requested window so "load more" (a larger limit) grows it
        // predictably: a full window returned means the UI should offer to fetch more.
        List<OverDriveCatalogItem> merged = new ArrayList<>(byTitleId.values());
        return merged.size() > effectiveLimit ? new ArrayList<>(merged.subList(0, effectiveLimit)) : merged;
      }

      /** OverDrive title id inside a Libby/OverDrive URL, e.g. share.libbyapp.com/title/618973. */
      private static final Pattern TITLE_ID_IN_URL = Pattern.compile("/(?:title|media)/(\\d+)");

      /**
       * Extract an OverDrive title id from a search query:
       * <ul>
       *   <li>a bare numeric id (digit strings of ISBN length 10/13 are treated as ISBNs, not ids);</li>
       *   <li>a share/Thunder URL with {@code /title/{id}} or {@code /media/{id}};</li>
       *   <li>any other Libby/OverDrive URL — the title id is the <b>last purely-numeric path segment</b>
       *       (e.g. {@code .../series-531761/.../page-1/786873} → {@code 786873}, not the series id).</li>
       * </ul>
       * Returns null when the query isn't an id (so a normal text search runs instead).
       */
      static String extractTitleId(String query) {
        if (query == null) {
            return null;
        }
        String q = query.trim();
        if (q.matches("\\d+")) {
            return (q.length() != 10 && q.length() != 13) ? q : null;
        }
        Matcher m = TITLE_ID_IN_URL.matcher(q);
        if (m.find()) {
            return m.group(1);
        }
        if (q.contains("libbyapp.com") || q.contains("overdrive.com")) {
            // Title id is the last numeric path segment; skip query/fragment and non-numeric segments
            // like "series-531761", "language-en" or "page-1".
            String[] segments = q.replaceAll("[?#].*$", "").split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                if (segments[i].matches("\\d+")) {
                    return segments[i];
                }
            }
        }
        return null;
      }

      /**
       * Merge two catalog items for the same title from different libraries: concatenate their
       * per-library availability and recompute the aggregate scalars. The display fields
       * (cover/isbn/formats/title) are taken from whichever copy is available to borrow now, matching
       * the previous "prefer an available copy" behaviour.
       */
      private static OverDriveCatalogItem mergeCatalogItems(OverDriveCatalogItem existing, OverDriveCatalogItem incoming) {
        List<OverDriveLibraryAvailability> availability = new ArrayList<>(existing.availability());
        availability.addAll(incoming.availability());
        // Prefer the display metadata of an available copy over a holdable-only one.
        OverDriveCatalogItem display = (!existing.available() && incoming.available()) ? incoming : existing;
        return new OverDriveCatalogItem(
                display.titleId(),
                display.formatId(),
                display.title(),
                display.subtitle(),
                display.author(),
                display.coverUrl(),
                display.isbn(),
                existing.available() || incoming.available(),
                existing.holdable() || incoming.holdable(),
                display.availableCopies(),
                display.ownedCopies(),
                display.holdsCount(),
                display.estimatedWaitDays(),
                sumNullable(existing.luckyDayAvailableCopies(), incoming.luckyDayAvailableCopies()),
                existing.preRelease() && incoming.preRelease(),
                display.formats(),
                availability,
                display.bookId(),
                display.language(),
                display.edition() != null ? display.edition() : (existing.edition() != null ? existing.edition() : incoming.edition()),
                display.audiobook(),
                display.narrator() != null ? display.narrator() : (existing.narrator() != null ? existing.narrator() : incoming.narrator()),
                display.duration() != null ? display.duration() : (existing.duration() != null ? existing.duration() : incoming.duration()),
                display.magazine());
      }

      /** Sum two nullable copy counts, treating null as zero; null when both are null. */
      private static Integer sumNullable(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return (a == null ? 0 : a) + (b == null ? 0 : b);
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
        r.put("libraryKeys", configuredLibraryKeys());
        r.put("formatPreference", formatPreference());
        r.put("acsmHandlerConfigured", acsmHandler.isConfigured());
        r.put("audiobookHandlerConfigured", audiobookHandler.isConfigured());
        r.put("ebookHandlerConfigured", ebookHandler.isConfigured());
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
            // prbn = the outcome of the last /chip re-mint (verified end-to-end; see docs/OverDrive-Testing.md):
            //   absent (null) -> fulfillment-ready — the re-mint carried the correct Accept-Language
            //                    shibboleth (see refreshIdentity/chipShibboleth); this is what a SUCCESSFUL
            //                    fulfill uses.
            //   "i"           -> freshly linked/minted, not yet re-minted; fulfill returns missing_chip
            //                    until a (shibboleth-carrying) re-mint clears it.
            //   "v"           -> the re-mint had a WRONG/absent shibboleth; OverDrive refuses fulfillment
            //                    with "whoa". Not velocity/throttle — purely the shibboleth.
            m.put("prbn", prbn);
            m.put("fulfillmentReady", prbn == null);
            m.put("shibbolethFailed", "v".equals(prbn));
        } catch (Exception e) {
            m.put("error", e.getMessage());
        }
        return m;
      }

      /** Whether card credentials can be stored (auto-relink); false unless a credential key is set. */
      public boolean credentialStorageEnabled() {
        return credentialCipher.isEnabled();
      }

      /** Whether an external audiobook handler is configured (enables borrowing audiobook titles). */
      public boolean audiobookHandlerConfigured() {
        return audiobookHandler.isConfigured();
      }

      public boolean magazineHandlerConfigured() {
        return magazineHandler.isConfigured();
      }

      /** Whether an external ebook handler is configured (enables read-in-browser-only titles). */
      public boolean ebookHandlerConfigured() {
        return ebookHandler.isConfigured();
      }

      // ── Per-document-type import destinations (per user) ─────────────────

      /** The current user's per-document-type import destinations (nulls when unset). */
      public OverDriveImportDestinations getImportDestinations() {
        return importDestinationRepository.findByUserId(currentUserId())
                .map(d -> new OverDriveImportDestinations(d.getEbookLibraryId(), d.getEbookPathId(),
                        d.getAudiobookLibraryId(), d.getAudiobookPathId(),
                        d.getMagazineLibraryId(), d.getMagazinePathId()))
                .orElseGet(() -> new OverDriveImportDestinations(null, null, null, null, null, null));
      }

      /** Store the current user's per-document-type import destinations (an upsert). */
      @Transactional
      public void setImportDestinations(OverDriveImportDestinations d) {
        Long userId = currentUserId();
        OverDriveImportDestinationEntity entity = importDestinationRepository.findByUserId(userId)
                .orElseGet(() -> OverDriveImportDestinationEntity.builder().userId(userId).build());
        entity.setEbookLibraryId(d.ebookLibraryId());
        entity.setEbookPathId(d.ebookPathId());
        entity.setAudiobookLibraryId(d.audiobookLibraryId());
        entity.setAudiobookPathId(d.audiobookPathId());
        entity.setMagazineLibraryId(d.magazineLibraryId());
        entity.setMagazinePathId(d.magazinePathId());
        importDestinationRepository.save(entity);
      }

      // ── Unattended automation (per-user opt-in) ──────────────────────────

      /**
       * How many consecutive automatic import failures a loan may accumulate before the poller stops
       * picking it up. Some titles can never import unattended (an audiobook with no external handler,
       * a format the user's libraries won't keep); retrying those on every tick burns the external
       * handler's time and floods the history for no gain. The user can still import them by hand.
       */
      private static final int MAX_AUTO_IMPORT_FAILURES = 3;

      /**
       * Bounds on the randomised gap between two titles handled in the same automation pass, for work
       * that changes nothing at the library: importing a loan already held, moving a hold, cancelling
       * one. These cost the account nothing, so they only need enough spacing not to arrive as a
       * single burst.
       */
      private static final long TITLE_GAP_MIN_MILLIS = 2_000;
      private static final long TITLE_GAP_MAX_MILLIS = 8_000;

      /**
       * Bounds on the gap between two actions that take a copy out or give one back.
       *
       * <p>Far wider than the gap above, because these are the two halves of the cycle
       * PatronExceededChurningLimit counts. Returns are paced like borrows deliberately: the limit is
       * on titles "borrowed and returned", so handing five books back in twenty seconds is the same
       * signal as taking five out that fast, and pacing one while bursting the other is not pacing.
       */
      private static final long LOAN_ACTION_GAP_MIN_MILLIS = 45_000;
      private static final long LOAN_ACTION_GAP_MAX_MILLIS = 150_000;

      /** Defaults for a user who has never opted in: everything off, with the schema's suggested values. */
      private static final OverDriveAutoSyncSettings AUTO_SYNC_DEFAULTS =
              new OverDriveAutoSyncSettings(false, false, false, 14, 48, true, false);

      /** The current user's automation opt-in — all off when they have never opted in. */
      public OverDriveAutoSyncSettings getAutoSyncSettings() {
        return autoSyncRepository.findByUserId(currentUserId())
                .map(a -> new OverDriveAutoSyncSettings(
                        a.isAutoImportLoans(), a.isAutoBorrowHolds(),
                        a.isAutoReturnEnabled(), a.getAutoReturnMinAgeDays(),
                        a.getAutoReturnMaxDelayHours(), a.isAutoReturnPromptWhenWaitlisted(),
                        a.isHoldShoppingEnabled()))
                .orElse(AUTO_SYNC_DEFAULTS);
      }

      /**
       * Store the current user's automation opt-in (an upsert). Auto-borrow implies auto-import:
       * borrowing a ready hold and then not fetching the book would consume the hold — and a checkout
       * slot — for nothing. Normalised on the way in so every reader sees a coherent pair rather than
       * each having to re-derive it.
       */
      @Transactional
      public OverDriveAutoSyncSettings setAutoSyncSettings(OverDriveAutoSyncSettings settings) {
        Long userId = currentUserId();
        boolean borrowHolds = settings.autoBorrowHolds();
        boolean importLoans = settings.autoImportLoans() || borrowHolds;
        OverDriveAutoSyncEntity entity = autoSyncRepository.findByUserId(userId)
                .orElseGet(() -> OverDriveAutoSyncEntity.builder().userId(userId).build());
        entity.setAutoImportLoans(importLoans);
        entity.setAutoBorrowHolds(borrowHolds);

        int minAgeDays = Math.max(0, settings.autoReturnMinAgeDays());
        int maxDelayHours = Math.max(0, settings.autoReturnMaxDelayHours());
        boolean returnWindowChanged = entity.isAutoReturnEnabled() != settings.autoReturnEnabled()
                || entity.getAutoReturnMinAgeDays() != minAgeDays
                || entity.getAutoReturnMaxDelayHours() != maxDelayHours;
        entity.setAutoReturnEnabled(settings.autoReturnEnabled());
        entity.setAutoReturnMinAgeDays(minAgeDays);
        entity.setAutoReturnMaxDelayHours(maxDelayHours);
        entity.setAutoReturnPromptWhenWaitlisted(settings.autoReturnPromptWhenWaitlisted());
        entity.setHoldShoppingEnabled(settings.holdShoppingEnabled());
        autoSyncRepository.save(entity);

        // Due times were drawn against the old window, so they no longer mean anything. Clearing them
        // makes the next pass redraw against the new configuration rather than acting on a schedule
        // the user has just changed out from under.
        if (returnWindowChanged) {
            clearAutoReturnDueDates(userId);
        }

        log.info("OverDrive auto-sync settings for user {}: importLoans={} borrowHolds={} autoReturn={} "
                        + "(minAge={}d, window={}h, promptWhenWaitlisted={}) holdShopping={}",
                userId, importLoans, borrowHolds, settings.autoReturnEnabled(), minAgeDays, maxDelayHours,
                settings.autoReturnPromptWhenWaitlisted(), settings.holdShoppingEnabled());
        return new OverDriveAutoSyncSettings(importLoans, borrowHolds, settings.autoReturnEnabled(),
                minAgeDays, maxDelayHours, settings.autoReturnPromptWhenWaitlisted(),
                settings.holdShoppingEnabled());
      }

      /** Forget every drawn auto-return due date for a user, so the next pass redraws them. */
      private void clearAutoReturnDueDates(Long userId) {
        List<OverDriveLoanEntity> loans = loanRepository.findByUserId(userId).stream()
                .filter(loan -> loan.getAutoReturnDueAt() != null)
                .toList();
        loans.forEach(loan -> loan.setAutoReturnDueAt(null));
        if (!loans.isEmpty()) {
            loanRepository.saveAll(loans);
        }
      }

      /** The user ids that opted into at least one automated action — the poller's work list. */
      public List<Long> autoSyncOptedInUserIds() {
        // Users who set a switch, plus users whose bookbag has something in it — queueing a title is
        // an instruction to borrow it, and a user who has only done that must still be polled.
        Set<Long> ids = autoSyncRepository.findAllOptedIn().stream()
                .map(OverDriveAutoSyncEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        ids.addAll(bookbagRepository.findDistinctUserIds());
        return List.copyOf(ids);
      }

      /**
       * What one user's automation pass did, for the task's summary log.
       *
       * @param loansLinked loans found to be already in the library and linked to the existing book
       *                    rather than downloaded again
       * @param holdsMoved  waiting holds re-placed at another of the user's libraries with a shorter queue
       * @param bookbagBorrowed titles taken from the bookbag queue
       * @param bookbagHeld     bookbag titles nobody could lend, queued for with a hold instead
       */
      public record AutoSyncOutcome(int cardsSynced, int holdsBorrowed, int loansImported, int loansLinked,
                                    int holdsMoved, int bookbagBorrowed, int bookbagHeld,
                                    int loansReturned, int failures) {

        static AutoSyncOutcome none() {
            return new AutoSyncOutcome(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
      }

      /**
       * Run one automation pass for the currently authenticated user: sync their cards, borrow any
       * holds that came in, and import loans that aren't in the library yet.
       *
       * <p>The caller must have installed the target user's security context — everything below resolves
       * cards, loans and import destinations through {@link #currentUserId()}, exactly as a request from
       * that user would. This deliberately reuses the interactive paths rather than a parallel
       * "automatic" implementation, so an automated import lands in the same destination, records the
       * same history entry, and honours the same format rules as one the user clicked.
       *
       * <p>Per-title failures are contained: one title that won't import must not cost the user the rest
       * of the pass, so each is caught, counted against the loan, and logged.
       */
      public AutoSyncOutcome runAutoSync() {
        automationInProgress.set(true);
        try {
            return doAutoSync();
        } finally {
            // Always cleared: the pool thread is reused, and a leaked flag would mark a later
            // interactive action as automated.
            automationInProgress.remove();
        }
      }

      private AutoSyncOutcome doAutoSync() {
        OverDriveAutoSyncSettings settings = getAutoSyncSettings();
        Long userId = currentUserId();
        // One read of the bag for everything the surrounding phases need to know about it.
        List<OverDriveBookbagEntity> bag = bookbagRepository.findByUserIdOrderByPositionAscIdAsc(userId);
        // A bookbag entry is a standing "borrow this for me", so it is an opt-in in its own right and
        // does not need one of the switches on as well.
        boolean hasBookbag = !bag.isEmpty();
        if (!settings.autoImportLoans() && !settings.autoBorrowHolds() && !settings.autoReturnEnabled()
                && !settings.holdShoppingEnabled() && !hasBookbag) {
            return AutoSyncOutcome.none();
        }
        List<String> identities = listCards().stream().map(OverDriveCard::cardId).filter(Objects::nonNull).toList();
        if (identities.isEmpty()) {
            return AutoSyncOutcome.none();
        }

        // One call per chip rather than per card, and it persists the loans this pass then works from.
        Map<String, OverDriveSyncResponse> syncs = syncAll(identities);
        int failures = 0;
        int borrowed = 0;

        // One snapshot of every card's capacity, and one pacer, shared by every step that spends
        // either. Recomputing per phase would let the same slot be spent twice, and counting per phase
        // would let each one start with an unpaced action.
        Map<String, Integer> loanSlotsLeft = loanCapacityByCard(syncs);
        Map<String, Integer> holdSlotsLeft = holdCapacityByCard(syncs);
        Map<String, Integer> holdsPerCard = holdCountByCard(syncs);
        LoanActionPacer pacer = new LoanActionPacer();

        // Titles the user has queued. A ready hold on one of them is claimed whether or not the
        // blanket auto-borrow switch is on: queueing a title is itself the instruction to borrow it,
        // and the bag placing holds it then never collects was the hole in splitting this decision
        // across two switches.
        Set<String> queuedTitleIds = bag.stream()
                .map(OverDriveBookbagEntity::getTitleId)
                .collect(Collectors.toSet());
        // Titles queued in the knowledge that the library already has them. The import sweep needs
        // these to tell a second copy somebody asked for from a duplicate that arrived by accident.
        Set<String> deliberateSecondCopies = bag.stream()
                .filter(OverDriveBookbagEntity::isAllowReborrow)
                .map(OverDriveBookbagEntity::getTitleId)
                .collect(Collectors.toSet());

        if (settings.autoBorrowHolds() || !queuedTitleIds.isEmpty()) {
            for (Map.Entry<String, List<OverDriveHold>> entry : readyHoldsByCard(syncs).entrySet()) {
                String cardId = entry.getKey();
                for (OverDriveHold hold : entry.getValue()) {
                    if (!settings.autoBorrowHolds() && !queuedTitleIds.contains(hold.getId())) {
                        continue; // someone else's hold, and no blanket instruction to claim it
                    }
                    // Don't spend a checkout on a title the library already holds. A hold placed months
                    // ago can come in long after the book arrived by another route, and borrowing it
                    // anyway consumes a loan slot to download a file we already have.
                    Long owned = resolveLinkedBookId(hold.getId(), null, null);
                    if (owned != null) {
                        log.info("OverDrive auto-borrow: skipping ready hold {} (\"{}\") for user {} — "
                                + "book id={} is already in the library", hold.getId(), hold.getTitle(), userId, owned);
                        continue;
                    }
                    // A card at its checkout limit cannot borrow, and asking anyway spends a request to
                    // be told so — once per ready hold, on every poll, for as long as the card stays
                    // full. Counted as a skip rather than a failure: nothing went wrong, there is just
                    // no room until something is returned.
                    String blocked = borrowBlockedReason(cardId);
                    if (blocked != null) {
                        log.info("OverDrive auto-borrow: leaving ready hold {} (\"{}\") for user {} — {}",
                                hold.getId(), hold.getTitle(), userId, blocked);
                        continue;
                    }
                    if (atLoanCapacity(loanSlotsLeft, cardId)) {
                        log.info("OverDrive auto-borrow: card {} is at its checkout limit; leaving ready hold "
                                + "{} (\"{}\") for user {} until a loan is returned",
                                cardId, hold.getId(), hold.getTitle(), userId);
                        continue;
                    }
                    // Spend the slot on the attempt, not on success. The counts are a snapshot from the
                    // start of the pass, so without this a card with one slot left would be offered
                    // every ready hold it has — and a borrow that then fails at import has still taken
                    // the loan, which is why doBorrowAndImport knows how to resume one.
                    loanSlotsLeft.computeIfPresent(cardId, (id, left) -> left - 1);
                    try {
                        pauseBetweenLoanActions(pacer);
                        borrowAndImport(entry.getKey(), hold.getId(), null, null,
                                hold.getTitle(), hold.getFirstCreatorName(), null, null, null, null, null);
                        borrowed++;
                    } catch (Exception e) {
                        failures++;
                        log.warn("OverDrive auto-borrow: hold {} (\"{}\") on card {} failed for user {}: {}",
                                hold.getId(), hold.getTitle(), entry.getKey(), userId, e.getMessage());
                    }
                }
            }
        }

        int moved = 0;
        if (settings.holdShoppingEnabled()) {
            HoldShoppingOutcome shopping = runHoldShopping(userId, settings, syncs, loanSlotsLeft,
                    holdSlotsLeft, holdsPerCard, pacer);
            borrowed += shopping.borrowed();
            moved += shopping.moved();
            failures += shopping.failures();
        }

        int bagBorrowed = 0;
        int bagHeld = 0;
        if (hasBookbag) {
            BookbagOutcome bookbag = runBookbag(userId, heldTitleIds(syncs), loanSlotsLeft, holdSlotsLeft,
                    holdsPerCard, pacer);
            bagBorrowed = bookbag.borrowed();
            bagHeld = bookbag.held();
            failures += bookbag.failures();
        }

        // Loan ids the user actually holds right now. The loan table is a cache that is only ever
        // written to — a loan returned in the Libby app, expired, or returned by this very pass leaves
        // its row behind with a stale state. Filtering on the live sync is what stops the automation
        // acting on loans that no longer exist.
        Set<String> heldLoanIds = syncs.values().stream()
                .filter(Objects::nonNull)
                .map(OverDriveSyncResponse::getLoans)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(OverDriveLoan::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int imported = 0;
        int linked = 0;
        if (settings.autoImportLoans()) {
            // Read the loans back from the database rather than the sync payload: syncAll has just
            // written this pass's loans, and the rows carry the import state (fulfilled, failure count)
            // that decides what still needs doing. Holds borrowed above are already imported by
            // borrowAndImport, and their rows now say so, so they aren't picked up twice.
            for (OverDriveLoanEntity loan : loanRepository.findByUserId(userId)) {
                if (!heldLoanIds.contains(loan.getOverdriveLoanId()) || !pendingAutoImport(loan)) {
                    continue;
                }
                if (linkIfAlreadyInLibrary(loan, userId, deliberateSecondCopies)) {
                    linked++;
                    continue;
                }
                // No check on the card's borrow budget here on purpose: every loan in this list is one
                // the account already holds, so importing it takes no checkout. A card resting or at a
                // ceiling should stop taking new books out, not stop collecting the ones it has.
                try {
                    // The light gap, and counted only within this phase: fetching a loan already held
                    // takes no checkout, so it is not what the minutes-long spacing is for.
                    pauseBetweenTitles(imported);
                    // The stored format decides how the title is fulfilled. Passing null made every
                    // magazine and audiobook fall through to the ebook path and fail as "no importable
                    // format", even where the handler was configured and worked by hand.
                    borrowAndImport(loan.getIdentity(), loan.getOverdriveLoanId(), null, null,
                            loan.getTitle(), loan.getAuthor(), null, loan.getIsbn(),
                            loan.getFormatId(), loan.getFormatId(), null);
                    imported++;
                } catch (Exception e) {
                    failures++;
                    noteAutoImportFailure(loan);
                    // borrowAndImport already records its own failure entry, marked automated; a second
                    // one here only duplicated every row, one of them titled with a raw title id.
                    log.warn("OverDrive auto-import: loan {} (\"{}\") failed for user {} (attempt {}): {}",
                            loan.getOverdriveLoanId(), loan.getTitle(), userId, loan.getAutoImportFailures(),
                            e.getMessage());
                }
            }
        }

        int returned = 0;
        if (settings.autoReturnEnabled()) {
            AutoReturnOutcome autoReturn = runAutoReturn(userId, settings, holdsCountByTitle(syncs),
                    heldLoanIds, pacer);
            returned = autoReturn.returned();
            failures += autoReturn.failures();
        }

        return new AutoSyncOutcome(identities.size(), borrowed, imported, linked, moved,
                bagBorrowed, bagHeld, returned, failures);
      }

      /**
       * Link a loan to the library book it duplicates, instead of downloading it again.
       *
       * <p>Matched on the OverDrive id alone, deliberately — not the ISBN and ASIN fallbacks the
       * display-time match is happy to use. A loan id is a title id, so an id hit means the library
       * holds this very edition and re-importing would spend an external tool run and a download to
       * produce a second copy of a file already on disk. An ISBN hit means far less: OverDrive editions
       * of one work share ISBNs with each other and with print, so it can point at a book that is not
       * this recording at all.
       *
       * <p>That distinction matters here more than anywhere else, because linking marks the loan
       * fulfilled — which stops it being reconsidered every pass, and makes it eligible for automatic
       * return. On an exact match that is the point: a loan whose book the user already has is the copy
       * worth handing back. On a loose match it would return a loan whose file was never downloaded.
       *
       * @return whether the loan was linked (and so needs no import)
       */
      private boolean linkIfAlreadyInLibrary(OverDriveLoanEntity loan, Long userId,
                                             Set<String> deliberateSecondCopies) {
        if (deliberateSecondCopies.contains(loan.getOverdriveLoanId())) {
            // Somebody queued this knowing the library had it. Linking would mark it fulfilled without
            // downloading anything, and being fulfilled is what makes a loan eligible for automatic
            // return — so the copy they asked for would be handed straight back, unread.
            return false;
        }
        Long existing = resolveLinkedBookId(loan.getOverdriveLoanId(), null, null);
        if (existing == null) {
            return false;
        }
        loan.setBookId(existing);
        loan.setFulfilled(true);
        loan.setAutoImportFailures(0);
        loanRepository.save(loan);
        log.info("OverDrive auto-import: loan {} (\"{}\") for user {} is already in the library as book id={}; "
                + "linked instead of re-importing", loan.getOverdriveLoanId(), loan.getTitle(), userId, existing);
        recordAudit(OverDriveAuditAction.AUTO_IMPORT, loan.getIdentity(), loan.getOverdriveLoanId(),
                loan.getOverdriveLoanId(), existing, loan.getTitle(),
                "Already in the library; linked to the existing book instead of importing again");
        return true;
      }

      /**
       * Wait a random few seconds before handling the next title, so a user with several waiting loans
       * does not fire their borrows and imports back-to-back. Each borrow costs an extra chip sync of
       * its own (borrowAndImport re-syncs to resume an existing loan), so an unspaced run turns one
       * poll into a rapid volley. Does nothing before the first title of the pass.
       *
       * <p>Interruption ends the pass: it means the application is shutting down, and the remaining
       * titles will be picked up on the next poll.
       */
      private void pauseBetweenTitles(int handledSoFar) {
        pauseBetween(handledSoFar, TITLE_GAP_MIN_MILLIS, TITLE_GAP_MAX_MILLIS);
      }

      /**
       * Wait before taking another copy out or giving one back. See
       * {@link #LOAN_ACTION_GAP_MIN_MILLIS} — these are the actions the churning limit counts, so they
       * are spaced in minutes rather than seconds. Does nothing before the first of a pass.
       */
      private void pauseBetweenLoanActions(int handledSoFar) {
        pauseBetween(handledSoFar, LOAN_ACTION_GAP_MIN_MILLIS, LOAN_ACTION_GAP_MAX_MILLIS);
      }

      /**
       * Counts the copies moved so far in one pass, across every phase of it.
       *
       * <p>Each phase used to count from zero, and the pause is skipped at zero so that the first
       * action of a pass is not delayed. Three phases meant three "firsts": auto-borrow's last
       * checkout, hold-shopping's first and the bookbag's first could land back to back — the exact
       * burst the spacing exists to prevent. One counter for the pass closes that.
       */
      static final class LoanActionPacer { // package-private for testing
        private int handled;

        int next() {
            return handled++;
        }
      }

      /** Wait before this pass's next checkout or return, spacing it from whatever the last one was. */
      private void pauseBetweenLoanActions(LoanActionPacer pacer) {
        pauseBetweenLoanActions(pacer.next());
      }

      private void pauseBetween(int handledSoFar, long minMillis, long maxMillis) {
        if (handledSoFar == 0) {
            return;
        }
        long millis = ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.INTERNAL_SERVER_ERROR.createException("OverDrive auto-sync interrupted between titles");
        }
      }

      /** What one user's auto-return pass did. */
      record AutoReturnOutcome(int returned, int failures) {} // package-private for testing

      /**
       * Return loans that have been held long enough, per the user's configured window.
       *
       * <p>Only loans Grimmory has already fulfilled are considered. Returning a loan that was never
       * imported would hand back a book the user has no copy of — irreversible, and the opposite of
       * what "I already have this, free the copy" means. A loan borrowed and read in the Libby app is
       * therefore left alone.
       *
       * <p>Each eligible loan gets a due time drawn once and persisted, rather than being returned on
       * the first pass after it ages out. Returning everything the instant it crosses the threshold
       * would stamp a uniform "returned at exactly N days" pattern on the account; spreading returns
       * across a window after the threshold does not.
       *
       * <p>That spreading is dropped when somebody is waiting for the title. A queued hold means a
       * real person's wait is being extended purely so the return looks less regular, which is not a
       * trade worth making — those go back as soon as the minimum age is reached.
       */
      AutoReturnOutcome runAutoReturn(Long userId, OverDriveAutoSyncSettings settings,
                                      Map<String, Integer> holdsByTitle, Set<String> heldLoanIds,
                                      LoanActionPacer pacer) { // package-private for testing
        Instant now = Instant.now();
        int returned = 0;
        int failures = 0;

        for (OverDriveLoanEntity loan : loanRepository.findByUserId(userId)) {
            // Returning a loan the user no longer holds is at best a wasted call and at worst acts on
            // a stale row, so only loans present in this pass's sync are considered.
            if (!heldLoanIds.contains(loan.getOverdriveLoanId())) {
                continue;
            }
            String blocked = returnBlockedReason(loan.getIdentity());
            if (blocked != null) {
                // Returning early is the other half of the cycle OverDrive objected to, so a card that
                // is resting or has spent its return budget stops here too. The loan keeps its drawn
                // due time and goes back once the card is free again.
                continue;
            }
            int holds = holdsByTitle.getOrDefault(loan.getOverdriveLoanId(), 0);
            if (!autoReturnDue(loan, settings, holds, now)) {
                continue;
            }
            boolean waitlisted = settings.autoReturnPromptWhenWaitlisted() && holds > 0;

            try {
                pauseBetweenLoanActions(pacer);
                returnBook(loan.getIdentity(), loan.getOverdriveLoanId());
                returned++;
                log.info("OverDrive auto-return: returned loan {} (\"{}\") for user {}{}",
                        loan.getOverdriveLoanId(), loan.getTitle(), userId,
                        waitlisted ? " — promptly, the title has holds waiting" : "");
            } catch (Exception e) {
                failures++;
                log.warn("OverDrive auto-return: loan {} (\"{}\") failed for user {}: {}",
                        loan.getOverdriveLoanId(), loan.getTitle(), userId, e.getMessage());
                recordAuditFailure(OverDriveAuditAction.AUTO_RETURN, loan.getIdentity(), null,
                        loan.getOverdriveLoanId(), "Automatic return failed: " + e.getMessage());
            }
        }
        return new AutoReturnOutcome(returned, failures);
      }

      /**
       * Whether this loan should be returned now.
       *
       * <p>Eligible loans normally come due at a randomly drawn point inside the window after the
       * minimum age, so returns are not all stamped at exactly N days. When the title has holds
       * queued and the user asked for it, that delay is skipped and the loan comes due the moment it
       * reaches the minimum age — a real person waiting outweighs the pattern.
       *
       * <p>The minimum age itself is never skipped: holds waiting do not shorten the time the user
       * chose to keep the book for.
       */
      boolean autoReturnDue(OverDriveLoanEntity loan, OverDriveAutoSyncSettings settings,
                            int holdsCount, Instant now) { // package-private for testing
        if (!eligibleForAutoReturn(loan)) {
            return false;
        }
        Instant borrowedAt = loan.getCreatedAt();
        if (borrowedAt == null) {
            return false;
        }
        Instant minReturnAt = borrowedAt.plus(settings.autoReturnMinAgeDays(), ChronoUnit.DAYS);
        boolean waitlisted = settings.autoReturnPromptWhenWaitlisted() && holdsCount > 0;
        Instant dueAt = waitlisted ? minReturnAt : autoReturnDueAt(loan, minReturnAt, settings);
        return !now.isBefore(dueAt);
      }

      /**
       * When each of the current user's loans is expected to be returned automatically, for display.
       *
       * <p>Two shapes, because a due time is drawn lazily. A loan the poller has already looked at
       * carries the exact moment it settled on. One it has not reached yet can only be described as
       * "no earlier than": the random offset inside the window has not been chosen. Deliberately does
       * not draw it — this is a read, and fixing the time here would move the decision out of the pass
       * that owns it and make a page load change when a book goes back.
       *
       * <p>Empty when the user has auto-return switched off, so the UI shows nothing rather than a
       * date that will never arrive.
       *
       * @return loan id to its projected return, for loans eligible for one
       */
      public Map<String, AutoReturnSchedule> autoReturnSchedule() {
        OverDriveAutoSyncSettings settings = getAutoSyncSettings();
        if (!settings.autoReturnEnabled()) {
            return Map.of();
        }
        Map<String, AutoReturnSchedule> schedule = new HashMap<>();
        for (OverDriveLoanEntity loan : loanRepository.findByUserId(currentUserId())) {
            if (!eligibleForAutoReturn(loan) || loan.getCreatedAt() == null) {
                continue;
            }
            Instant earliest = loan.getCreatedAt().plus(settings.autoReturnMinAgeDays(), ChronoUnit.DAYS);
            Instant exact = loan.getAutoReturnDueAt();
            schedule.put(loan.getOverdriveLoanId(), new AutoReturnSchedule(
                    exact != null ? exact.toString() : null,
                    earliest.toString(),
                    settings.autoReturnMaxDelayHours()));
        }
        return schedule;
      }

      /**
       * When a loan is due to go back on its own.
       *
       * @param dueAt        the exact moment, once the poller has drawn it; null until then
       * @param earliestAt   the minimum age falling due — the return cannot happen before this
       * @param windowHours  width of the random window after {@code earliestAt}; 0 means exactly then
       */
      public record AutoReturnSchedule(String dueAt, String earliestAt, int windowHours) {}

      /**
       * Whether a loan is a candidate for automatic return at all: still on loan, and already
       * fulfilled, so returning it gives up the library's copy rather than the user's only access.
       */
      private boolean eligibleForAutoReturn(OverDriveLoanEntity loan) {
        return Boolean.TRUE.equals(loan.getFulfilled())
                && loan.getIdentity() != null
                && loan.getOverdriveLoanId() != null
                // Expired counts as gone too, now that reconciliation records it: handing back a loan
                // that already lapsed is a call that can only fail.
                && !isTerminalLoanState(loan.getState());
      }

      /**
       * This loan's persisted return-due time, drawing one on first evaluation.
       *
       * <p>Drawn once and stored rather than recomputed per pass: a fresh random delay on every poll
       * would move the target continuously, and a loan could stay perpetually not-yet-due.
       */
      Instant autoReturnDueAt(OverDriveLoanEntity loan, Instant minReturnAt,
                              OverDriveAutoSyncSettings settings) { // package-private for testing
        if (loan.getAutoReturnDueAt() != null) {
            return loan.getAutoReturnDueAt();
        }
        long windowHours = Math.max(0, settings.autoReturnMaxDelayHours());
        Instant due = windowHours == 0
                ? minReturnAt
                : minReturnAt.plusSeconds(ThreadLocalRandom.current().nextLong(windowHours * 3600 + 1));
        loan.setAutoReturnDueAt(due);
        loanRepository.save(loan);
        log.debug("OverDrive auto-return: loan {} due at {}", loan.getOverdriveLoanId(), due);
        return due;
      }

      /**
       * Remaining checkouts per card, from the counts and limits the sync feed already carries.
       *
       * <p>A card only appears when its library reports both a count and a limit. An absent entry means
       * "unknown", which is treated as unlimited — refusing to borrow because a library declined to say
       * what its cap is would be worse than trying and being turned down.
       */
      private Map<String, Integer> loanCapacityByCard(Map<String, OverDriveSyncResponse> syncs) {
        Map<String, Integer> remaining = new HashMap<>();
        for (OverDriveSyncResponse response : syncs.values()) {
            if (response == null || response.getCards() == null) {
                continue;
            }
            for (OverDriveSyncResponse.Card card : response.getCards()) {
                if (card == null || card.getCardId() == null
                        || card.getCounts() == null || card.getLimits() == null
                        || card.getCounts().getLoan() == null || card.getLimits().getLoan() == null) {
                    continue;
                }
                // A chip response is shared by every card on it, so the same card arrives once per
                // identity in the map — put, not merge.
                remaining.put(card.getCardId(), card.getLimits().getLoan() - card.getCounts().getLoan());
            }
        }
        return remaining;
      }

      /** Whether this card has no checkout slots left; an unknown limit never blocks a borrow. */
      private static boolean atLoanCapacity(Map<String, Integer> loanSlotsLeft, String cardId) {
        Integer left = loanSlotsLeft.get(cardId);
        return left != null && left <= 0;
      }

      /**
       * Remaining holds per card, and which cards refuse holds outright. A library that says it cannot
       * place holds is recorded as having none left, so one check covers both cases.
       */
      private Map<String, Integer> holdCapacityByCard(Map<String, OverDriveSyncResponse> syncs) {
        Map<String, Integer> remaining = new HashMap<>();
        for (OverDriveSyncResponse response : syncs.values()) {
            if (response == null || response.getCards() == null) {
                continue;
            }
            for (OverDriveSyncResponse.Card card : response.getCards()) {
                if (card == null || card.getCardId() == null) {
                    continue;
                }
                if (Boolean.FALSE.equals(card.getCanPlaceHolds())) {
                    remaining.put(card.getCardId(), 0);
                    continue;
                }
                if (card.getCounts() == null || card.getLimits() == null
                        || card.getCounts().getHold() == null || card.getLimits().getHold() == null) {
                    continue;
                }
                remaining.put(card.getCardId(), card.getLimits().getHold() - card.getCounts().getHold());
            }
        }
        return remaining;
      }

      /** Whether this card can take no more holds; an unknown limit never blocks one. */
      private static boolean atHoldCapacity(Map<String, Integer> holdSlotsLeft, String cardId) {
        Integer left = holdSlotsLeft.get(cardId);
        return left != null && left <= 0;
      }

      /**
       * How many holders are queued for each title across a pass's syncs. The sync feed already
       * carries the count for the loan's own library, so knowing whether somebody is waiting costs no
       * extra call.
       */
      private Map<String, Integer> holdsCountByTitle(Map<String, OverDriveSyncResponse> syncs) {
        Map<String, Integer> out = new HashMap<>();
        for (OverDriveSyncResponse sync : syncs.values()) {
            if (sync == null || sync.getLoans() == null) {
                continue;
            }
            for (OverDriveLoan loan : sync.getLoans()) {
                if (loan.getId() == null || loan.getHoldsCount() == null) {
                    continue;
                }
                out.merge(loan.getId(), loan.getHoldsCount(), Math::max);
            }
        }
        return out;
      }

      /**
       * The ready-to-borrow holds in a set of syncs, keyed by the card each sits on. A chip's sync
       * carries every card on that chip, so holds are attributed by their own {@code cardId} (falling
       * back to the card we synced with) — the same rule the interactive sync view uses.
       */
      /** What one user's hold-shopping pass did. */
      record HoldShoppingOutcome(int borrowed, int moved, int failures) {} // package-private for testing

      /**
       * Check every hold the user is still waiting on against their other libraries, and act when one
       * of them is better placed to lend the title.
       *
       * <p>This is the Holds tab's "check all other libraries" run on the schedule instead of by hand.
       * The same title is often stocked by several of a user's libraries with wildly different queues,
       * so a hold placed at one can sit for months while another has it on the shelf.
       *
       * <p>Two outcomes, in order of preference:
       *
       * <ul>
       *   <li><b>Available now elsewhere</b> — borrow it there and cancel the original hold. Only when
       *       the user has opted into automatic borrowing: this consumes a checkout and downloads a
       *       book, which is exactly what that switch governs, and doing it off the back of a
       *       different switch would surprise someone who only wanted their queues tidied.</li>
       *   <li><b>A shorter queue elsewhere</b> — place a hold there, then cancel the original, by the
       *       same rule the Holds tab applies by hand.</li>
       * </ul>
       *
       * <p>The new hold is always placed before the old one is cancelled, so a failed placement leaves
       * the user exactly where they were rather than at the back of a queue they had waited in.
       */
      HoldShoppingOutcome runHoldShopping(Long userId, OverDriveAutoSyncSettings settings,
                                          Map<String, OverDriveSyncResponse> syncs,
                                          Map<String, Integer> loanSlotsLeft,
                                          Map<String, Integer> holdSlotsLeft,
                                          Map<String, Integer> holdsPerCard,
                                          LoanActionPacer pacer) { // package-private for testing
        List<OverDriveHold> waiting = waitingHolds(syncs);
        if (waiting.isEmpty()) {
            return new HoldShoppingOutcome(0, 0, 0);
        }
        // Card per library key, so an availability row can be turned back into a card to act with.
        Map<String, String> cardByLibrary = new LinkedHashMap<>();
        for (OverDriveTokenEntity row : accessibleTokenRows(userId)) {
            if (row.getLibraryKey() != null && !row.getLibraryKey().isBlank()) {
                cardByLibrary.putIfAbsent(row.getLibraryKey(), row.getIdentity());
            }
        }
        if (cardByLibrary.size() < 2) {
            return new HoldShoppingOutcome(0, 0, 0); // nowhere else to look
        }

        // One lightweight call per library covering every waiting title, rather than a media fetch
        // per title per library.
        Map<String, List<OverDriveLibraryAvailability>> availability = availabilityForTitles(
                waiting.stream().map(OverDriveHold::getId).distinct().toList(),
                List.copyOf(cardByLibrary.values()));

        int borrowed = 0;
        int moved = 0;
        int failures = 0;

        for (OverDriveHold hold : waiting) {
            List<OverDriveLibraryAvailability> options = availability.getOrDefault(hold.getId(), List.of());
            if (options.isEmpty() || churnCooldownUntil(hold.getCardId()) != null) {
                // Moving a hold off a resting card means cancelling on it, and borrowing elsewhere
                // still ends in a return here. Leave the whole title alone until it is free.
                continue;
            }

            String borrowCard = settings.autoBorrowHolds()
                    ? availableElsewhere(hold, options, cardByLibrary, loanSlotsLeft)
                    : null;
            // Checked out here, not in the selector: that stays a pure comparison over the availability
            // data and does not reach for the card rows.
            if (borrowCard != null && borrowBlockedReason(borrowCard) != null) {
                borrowCard = null;
            }
            if (borrowCard != null) {
                try {
                    pauseBetweenLoanActions(pacer);
                    loanSlotsLeft.computeIfPresent(borrowCard, (id, left) -> left - 1);
                    borrowAndImport(borrowCard, hold.getId(), null, null,
                            hold.getTitle(), hold.getFirstCreatorName(), null, null, null, null, null);
                    borrowed++;
                    log.info("OverDrive hold shopping: \"{}\" was on the shelf at card {}; borrowed it there "
                            + "for user {} instead of waiting at {}",
                            hold.getTitle(), borrowCard, userId, hold.getCardId());
                    cancelSupersededHold(hold, "borrowed it at another library");
                } catch (Exception e) {
                    failures++;
                    log.warn("OverDrive hold shopping: borrowing \"{}\" at card {} for user {} failed: {}",
                            hold.getTitle(), borrowCard, userId, e.getMessage());
                }
                continue;
            }

            SoonerQueue sooner = soonerElsewhere(hold, options, cardByLibrary, holdSlotsLeft, holdsPerCard);
            // Checked here rather than inside the selector, which stays a pure comparison over the
            // availability data and does not reach for the card rows.
            if (sooner == null || churnCooldownUntil(sooner.cardId()) != null) {
                continue;
            }
            try {
                pauseBetweenTitles(borrowed + moved);
                // Place first, cancel second: the reverse order risks giving up a queue position and
                // then failing to take the new one.
                placeHold(sooner.cardId(), hold.getId());
                holdSlotsLeft.computeIfPresent(sooner.cardId(), (id, left) -> left - 1);
                moved++;
                log.info("OverDrive hold shopping: moved the hold on \"{}\" for user {} to card {} "
                        + "(~{}d instead of ~{}d)",
                        hold.getTitle(), userId, sooner.cardId(), sooner.waitDays(), sooner.currentWaitDays());
                cancelSupersededHold(hold, "placed a shorter hold at another library");
            } catch (Exception e) {
                failures++;
                log.warn("OverDrive hold shopping: could not place a hold on \"{}\" at card {} for user {} "
                        + "({}); the original hold is untouched",
                        hold.getTitle(), sooner.cardId(), userId, e.getMessage());
            }
        }
        return new HoldShoppingOutcome(borrowed, moved, failures);
      }

      /**
       * Cancel a hold that has just been superseded. Deliberately swallows its failure: the useful half
       * of the move has already happened, and undoing it is neither possible nor desirable. The user is
       * left holding two places for one title, which the log says plainly so it can be tidied by hand.
       */
      private void cancelSupersededHold(OverDriveHold hold, String because) {
        if (hold.getCardId() == null) {
            return;
        }
        try {
            cancelHold(hold.getCardId(), hold.getId());
        } catch (Exception e) {
            log.warn("OverDrive hold shopping: {} for \"{}\" but could not cancel the original hold on card "
                    + "{} ({}); it is still in place and needs cancelling by hand",
                    because, hold.getTitle(), hold.getCardId(), e.getMessage());
        }
      }

      /** Every title the user currently has a hold on, ready or waiting, across this pass's cards. */
      private Set<String> heldTitleIds(Map<String, OverDriveSyncResponse> syncs) {
        return syncs.values().stream()
                .filter(Objects::nonNull)
                .map(OverDriveSyncResponse::getHolds)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(OverDriveHold::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
      }

      /** Holds the user is still queued for — the ready ones are auto-borrow's business, not this. */
      private List<OverDriveHold> waitingHolds(Map<String, OverDriveSyncResponse> syncs) {
        List<OverDriveHold> waiting = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OverDriveSyncResponse sync : syncs.values()) {
            if (sync == null || sync.getHolds() == null) {
                continue;
            }
            for (OverDriveHold hold : sync.getHolds()) {
                if (hold.getId() == null || hold.getCardId() == null
                        || Boolean.TRUE.equals(hold.getAvailable())) {
                    continue;
                }
                // Cards sharing a chip see the same hold list; consider each hold once.
                if (seen.add(hold.getCardId() + ":" + hold.getId())) {
                    waiting.add(hold);
                }
            }
        }
        return waiting;
      }

      /**
       * A card at another of the user's libraries where this held title is on the shelf right now, or
       * null. A Lucky Day copy counts — it skips the queue — but it is still a checkout, so the card
       * has to have room for one.
       */
      String availableElsewhere(OverDriveHold hold, List<OverDriveLibraryAvailability> options,
                                Map<String, String> cardByLibrary,
                                Map<String, Integer> loanSlotsLeft) { // package-private for testing
        for (OverDriveLibraryAvailability option : options) {
            boolean onShelf = option.available()
                    || (option.luckyDayAvailableCopies() != null && option.luckyDayAvailableCopies() > 0);
            if (!onShelf) {
                continue;
            }
            String cardId = cardByLibrary.get(option.libraryKey());
            if (cardId != null && !cardId.equals(hold.getCardId()) && !atLoanCapacity(loanSlotsLeft, cardId)) {
                return cardId;
            }
        }
        return null;
      }

      /** A better queue for a held title: which card, its estimated wait, and the one being left. */
      record SoonerQueue(String cardId, int waitDays, int currentWaitDays) {} // package-private for testing

      /**
       * The best other library with a shorter estimated wait than this hold's, or null.
       *
       * <p>Deliberately the same rule the Holds tab applies by hand, down to the tie-breaks: any
       * shorter estimate is worth moving to, ties on wait go to the library owning more copies (a
       * bigger pool churns faster and gains more from holds ahead lapsing), and a remaining tie goes to
       * the card carrying fewest of the user's own holds, so no one card fills its hold slots. A
       * threshold here would mean the button and the schedule disagreeing about what counts as better.
       */
      SoonerQueue soonerElsewhere(OverDriveHold hold, List<OverDriveLibraryAvailability> options,
                                  Map<String, String> cardByLibrary, Map<String, Integer> holdSlotsLeft,
                                  Map<String, Integer> holdsPerCard) { // package-private for testing
        Integer currentWait = parseWaitDays(hold.getEstimatedWaitDays());
        if (currentWait == null) {
            return null; // no estimate to beat; moving would be a guess
        }
        SoonerQueue best = null;
        int bestCopies = -1;
        for (OverDriveLibraryAvailability option : options) {
            if (!option.holdable() || option.estimatedWaitDays() == null
                    || option.estimatedWaitDays() >= currentWait) {
                continue;
            }
            String cardId = cardByLibrary.get(option.libraryKey());
            if (cardId == null || cardId.equals(hold.getCardId()) || atHoldCapacity(holdSlotsLeft, cardId)) {
                continue;
            }
            int copies = option.ownedCopies() != null ? option.ownedCopies() : 0;
            boolean better = best == null
                    || option.estimatedWaitDays() < best.waitDays()
                    || (option.estimatedWaitDays() == best.waitDays() && copies > bestCopies)
                    || (option.estimatedWaitDays() == best.waitDays() && copies == bestCopies
                        && holdsPerCard.getOrDefault(cardId, 0) < holdsPerCard.getOrDefault(best.cardId(), 0));
            if (better) {
                best = new SoonerQueue(cardId, option.estimatedWaitDays(), currentWait);
                bestCopies = copies;
            }
        }
        return best;
      }

      /** How many holds each card is currently carrying, from the sync feed's own counts. */
      private Map<String, Integer> holdCountByCard(Map<String, OverDriveSyncResponse> syncs) {
        Map<String, Integer> counts = new HashMap<>();
        for (OverDriveSyncResponse response : syncs.values()) {
            if (response == null || response.getCards() == null) {
                continue;
            }
            for (OverDriveSyncResponse.Card card : response.getCards()) {
                if (card != null && card.getCardId() != null
                        && card.getCounts() != null && card.getCounts().getHold() != null) {
                    counts.put(card.getCardId(), card.getCounts().getHold());
                }
            }
        }
        return counts;
      }

      /** The sync feed reports a hold's wait as free text; anything non-numeric means "no estimate". */
      private static Integer parseWaitDays(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
      }

      private Map<String, List<OverDriveHold>> readyHoldsByCard(Map<String, OverDriveSyncResponse> syncs) {
        Map<String, List<OverDriveHold>> byCard = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, OverDriveSyncResponse> entry : syncs.entrySet()) {
            OverDriveSyncResponse sync = entry.getValue();
            if (sync == null || sync.getHolds() == null) {
                continue;
            }
            for (OverDriveHold hold : sync.getHolds()) {
                if (!Boolean.TRUE.equals(hold.getAvailable()) || hold.getId() == null) {
                    continue;
                }
                String card = hold.getCardId() != null && !hold.getCardId().isBlank()
                        ? hold.getCardId()
                        : entry.getKey();
                // Cards sharing a chip see the same hold list; borrow each ready hold once.
                if (seen.add(card + ":" + hold.getId())) {
                    byCard.computeIfAbsent(card, k -> new ArrayList<>()).add(hold);
                }
            }
        }
        return byCard;
      }

      /** Whether a loan row is still waiting to be imported automatically. */
      boolean pendingAutoImportForTest(OverDriveLoanEntity loan) { // package-private for testing
        return pendingAutoImport(loan);
      }

      private boolean pendingAutoImport(OverDriveLoanEntity loan) {
        return !Boolean.TRUE.equals(loan.getFulfilled())
                // A row marked returned or expired is not a loan any more. borrowAndImport borrows
                // when it finds no active loan, so importing one of these would silently take the
                // title out again — the caller must also check the loan is in the live sync.
                && !"RETURNED".equals(loan.getState())
                && !"EXPIRED".equals(loan.getState())
                && loan.getIdentity() != null
                && loan.getOverdriveLoanId() != null
                && loan.getAutoImportFailures() < MAX_AUTO_IMPORT_FAILURES;
      }

      /**
       * Count a failed automatic import against the loan so a title that can never import stops being
       * retried. Best-effort: the pass has already failed once here, and losing the counter must not
       * also abort the remaining loans.
       */
      private void noteAutoImportFailure(OverDriveLoanEntity loan) {
        try {
            loanRepository.findByUserIdAndOverdriveLoanId(loan.getUserId(), loan.getOverdriveLoanId())
                    .ifPresent(row -> {
                        row.setAutoImportFailures(row.getAutoImportFailures() + 1);
                        loanRepository.save(row);
                    });
        } catch (Exception e) {
            log.debug("OverDrive auto-import: could not record failure for loan {}: {}",
                    loan.getOverdriveLoanId(), e.getMessage());
        }
      }

      /** A resolved import destination (either may be null when nothing routes the type). */
      private record ImportDestination(Long libraryId, Long pathId) {}

      /** The document kind an import routes by (each has its own per-user default destination). */
      private enum MediaKind { EBOOK, AUDIOBOOK, MAGAZINE }

      /**
       * Resolve where a borrowed document of the given kind should import: an explicit per-borrow
       * destination (both ids present) wins; otherwise the user's per-type default (ebook/audiobook/
       * magazine); otherwise nothing (→ Bookdrop).
       */
      private ImportDestination resolveImportDestination(MediaKind kind, Long requestLibraryId, Long requestPathId) {
        if (requestLibraryId != null && requestPathId != null) {
            return new ImportDestination(requestLibraryId, requestPathId);
        }
        OverDriveImportDestinationEntity d = importDestinationRepository.findByUserId(currentUserId()).orElse(null);
        if (d == null) {
            return new ImportDestination(null, null);
        }
        return switch (kind) {
            case AUDIOBOOK -> new ImportDestination(d.getAudiobookLibraryId(), d.getAudiobookPathId());
            case MAGAZINE -> new ImportDestination(d.getMagazineLibraryId(), d.getMagazinePathId());
            case EBOOK -> new ImportDestination(d.getEbookLibraryId(), d.getEbookPathId());
        };
      }

      /**
       * Build the audiobook handoff request for a card: decrypt its stored card + PIN so the external
       * tool can authenticate itself (with its own chip + UA). Throws a clear error when the card has no
       * stored credentials — the audiobook tool needs card + PIN, so the card must have been linked by
       * number + PIN with a credential key configured (we deliberately don't hand out the web chip token).
       */
      private AudiobookHandler.Request audiobookRequest(String identity, String titleId, String formatId) {
        OverDriveTokenEntity card = accessibleTokenRow(currentUserId(), identity)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No such card for audiobook fulfillment: " + identity));
        String label = (card.getCardName() != null && !card.getCardName().isBlank()
                ? "\"" + card.getCardName() + "\" " : "") + "(" + identity + ")";
        if (!credentialCipher.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Audiobook download needs stored card credentials, but credential "
                    + "storage is off: set OVERDRIVE_CREDENTIAL_KEY (base64 16/24/32 bytes) and re-link the card "
                    + "by number + PIN. (Settings → OverDrive → diagnostics shows credentialStorageEnabled.)");
        }
        if (card.getCredCard() == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Card " + label + " has no stored card + PIN, so it can't download "
                    + "audiobooks. Only cards linked by number + PIN store credentials — setup-code and "
                    + "pasted-token links don't. Re-link THIS card by number + PIN, then retry. (Settings → "
                    + "OverDrive → diagnostics shows each card's credentialsStored.)");
        }
        String cardNumber = credentialCipher.decrypt(card.getCredCard());
        String pin = card.getCredPin() != null ? credentialCipher.decrypt(card.getCredPin()) : null;
        return new AudiobookHandler.Request(sentryBaseUrl, cardNumber, pin, card.getLibraryKey(),
                card.getWebsiteId(), card.getIlsName(), identity, titleId, formatId);
      }

      /**
       * Fulfill an audiobook loan into a file via the external audiobook handler and return it for
       * download — the audiobook analogue of the ACSM download. Unlike borrow-and-import, this neither
       * borrows nor imports: the loan already exists, and the bytes go straight to the browser. In
       * OverDrive a checked-out title's loan id is its title id, so {@code loanId} doubles as the title
       * id the handler needs.
       *
       * @param formatId the loan's audiobook format (e.g. {@code audiobook-mp3}); when null/not an
       *                 audiobook format, it is discovered from the active loan.
       */
      public AudiobookHandler.Result downloadAudiobook(String identity, String loanId, String formatId, Path workDir) {
        resolveToken(identity); // validate the card is accessible even though the tool re-auths itself
        String chosenFormat = isAudiobookFormat(formatId) ? formatId : null;
        if (chosenFormat == null) {
            LoanRef loan = findActiveLoan(identity, loanId);
            chosenFormat = loan == null ? null : loan.formatIds().stream()
                    .filter(OverDriveService::isAudiobookFormat)
                    .findFirst()
                    .orElse(null);
        }
        if (chosenFormat == null) {
            recordAuditFailure(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, "No audiobook format for loan");
            throw ApiError.GENERIC_BAD_REQUEST.createException("No audiobook format found for loan " + loanId);
        }
        try {
            AudiobookHandler.Result result = audiobookHandler.handle(
                    audiobookRequest(identity, loanId, chosenFormat), workDir, toolLogSink(loanId));
            if (result == null || isEmptyFile(result.file())) {
                recordAuditFailure(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, "Audiobook handler produced no file");
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("The audiobook handler did not produce a file for loan " + loanId + ".");
            }
            recordAudit(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, null, null, null);
            log.info("OverDrive audiobook downloaded: {} bytes (.{}) for loan {}", fileSize(result.file()),
                    result.extension(), loanId);
            return result;
        } catch (RuntimeException e) {
            recordAuditFailure(OverDriveAuditAction.DOWNLOAD, identity, null, loanId, e.getMessage());
            throw e;
        }
      }

      /** The configured OverDrive library keys (for metadata search) from metadata provider settings. */
      private List<String> configuredLibraryKeys() {
        var appSettings = appSettingService.getAppSettings();
        MetadataProviderSettings settings = appSettings != null ? appSettings.getMetadataProviderSettings() : null;
        if (settings == null || settings.getOverdrive() == null || settings.getOverdrive().getLibraryKeys() == null) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : settings.getOverdrive().getLibraryKeys()) {
            if (key != null && !key.isBlank()) {
                keys.add(key.trim());
            }
        }
        return new ArrayList<>(keys);
      }

      /**
       * Borrow a title by id, fulfill it, and import the book (EPUB or PDF) into the given library.
       * The fulfillment format is chosen by the configured {@link #formatPreference() preference}
       * among the formats the title offers; DRM-free "open" formats import directly, while Adobe
       * formats are procured via the external ACSM handler (and are skipped if none is configured).
       *
       * <p>Only one import of a given title may run at a time per user. Two concurrent imports of the
       * same title each run the external handler (minutes, and hundreds of MB for an audiobook) and then
       * race on the same computed library path: the winner's post-import file move vacates that path, so
       * the loser's {@code targetFile.exists()} collision check passes, it re-writes the whole file, and
       * it then fails obscurely ("File does not exist or is not a regular file") when the fingerprinter
       * looks for a file the winner has already moved. Reject the duplicate up front instead — the
       * winner still imports normally.
       *
       * @param titleId       the OverDrive title id to borrow
       * @param replaceBookId when set, the already-imported book this import replaces: it is deleted
       *                      once the new file has been fulfilled, so the re-import lands in its place
       *                      instead of adding a second copy
       * @return the persisted {@link Book}
       */
      public Book borrowAndImport(String identity, String titleId, Long libraryId, Long pathId,
                                  String title, String author, String coverUrl, String isbn, String preferredFormat,
                                  String titleFormat, Long replaceBookId) {
        String importKey = currentUserId() + ":" + titleId;
        if (!importsInFlight.add(importKey)) {
            log.info("OverDrive: rejecting duplicate concurrent import of title {} (one is already running)", titleId);
            throw ApiError.CONFLICT.createException("This title is already being imported — wait for that import to "
                    + "finish before starting another.");
        }
        try {
            return doBorrowAndImport(identity, titleId, libraryId, pathId, title, author, coverUrl, isbn,
                    preferredFormat, titleFormat, replaceBookId);
        } finally {
            importsInFlight.remove(importKey);
        }
      }

      private Book doBorrowAndImport(String identity, String titleId, Long libraryId, Long pathId,
                                     String title, String author, String coverUrl, String isbn, String preferredFormat,
                                     String titleFormat, Long replaceBookId) {
        // Track whether this became an import of an existing loan (vs a fresh borrow) so both the
        // success and the failure history entries can report the right action. Hoisted out of the try
        // so the catch can see it.
        boolean alreadyBorrowed = false;
        // Magazines route to their own handler with no Grimmory borrow (the tool borrows + fulfils itself).
        if ("magazine".equals(normalizeTitleFormat(titleFormat)) || isMagazineFormat(preferredFormat)) {
            return importMagazine(identity, titleId, libraryId, pathId, title, author, coverUrl, isbn,
                    replaceBookId);
        }
        // Everything fulfilled for this import is staged on disk here and moved into the library from
        // it; nothing is buffered in heap. Deleted in the finally, by which point a successful import
        // has already moved its file out.
        Path workDir = createWorkDir("overdrive-import-");
        try {
        // Borrow and fulfill with the stored identity as-is, mirroring the web client: it does not
        // pre-mint, and fetchFulfillment re-mints reactively on missing_chip. Pre-minting here only
        // added chip churn without avoiding the missing_chip round-trip.

        // Resume an already-borrowed title rather than borrowing again: a prior attempt may have
        // borrowed the title but failed at fulfill/import, leaving the loan (and a consumed checkout
        // slot) in place. Borrowing is not automatically retried; we only pick up the existing loan.
        LoanRef loan = findActiveLoan(identity, titleId);
        // Was it already on loan? Then this is an import of an existing loan, not a fresh borrow — the
        // history should say so.
        alreadyBorrowed = loan != null;
        if (alreadyBorrowed) {
            log.info("OverDrive: resuming existing loan {} for title {} (skipping re-borrow)", loan.loanId(), titleId);
        } else {
            // Checked here rather than on the way in, because only this branch takes a copy out.
            // Fetching a loan the account already holds spends no checkout, and refusing it would
            // strand books already paid for until they expired — the opposite of protecting the card.
            assertCanBorrow(identity);
            // Prefer an explicit media-type hint; fall back to deriving it from the requested format id.
            String hint = (titleFormat != null && !titleFormat.isBlank()) ? titleFormat : preferredFormat;
            Map<String, Object> borrowed = withChipRecovery(identity,
                    token -> borrowLoan(identity, token, titleId, hint));
            loan = new LoanRef(borrowed.get("id").toString(), loanFormatIds(borrowed));
            pauseBeforeFulfilling(titleId);
        }
        // Resolved after the borrow so a token renewed by the borrow's chip recovery is the one we
        // fulfill with (fetchFulfillment can still re-mint reactively on top of it).
        String authToken = resolveToken(identity);
        String loanId = loan.loanId();
        List<String> formats = loan.formatIds();

        // Honor a user-selected format when the loan actually offers it and we can import it; otherwise
        // fall back to the operator's preference order.
        String chosenFormat;
        if (preferredFormat != null && !preferredFormat.isBlank()
                && formats.contains(preferredFormat) && isImportableFormat(preferredFormat)) {
            chosenFormat = preferredFormat;
        } else {
            chosenFormat = chooseFormat(formats);
        }
        if (chosenFormat == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(noImportableFormatMessage(loanId, formats));
        }

        Path content;
        String extension;
        if (isAudiobookFormat(chosenFormat)) {
            // Audiobook: hand the raw card + PIN to the external tool, which authenticates itself
            // (its own chip + app-emulating UA, kept separate from Grimmory's web chip), then fulfils,
            // downloads and assembles the file. The tool picks the output extension (m4b/mp3/…).
            // The result stays on disk in workDir — an assembled audiobook is far too large to buffer.
            AudiobookHandler.Result audiobook = audiobookHandler.handle(
                    audiobookRequest(identity, titleId, chosenFormat), workDir, toolLogSink(titleId));
            content = audiobook.file();
            extension = audiobook.extension();
            if (isEmptyFile(content)) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("The audiobook handler did not produce a file for loan " + loanId + ".");
            }
        } else if (isEbookHandlerFormat(chosenFormat)) {
            // Read-in-browser only: there is no downloadable file and no ACSM, so the external ebook
            // tool authenticates itself and rebuilds a book file from the web-reader assets. It picks
            // the output extension (epub/pdf), same as the audiobook and magazine handlers.
            EbookHandler.Result ebook = ebookHandler.handle(
                    ebookRequest(identity, titleId, chosenFormat), workDir, toolLogSink(titleId));
            content = ebook.file();
            extension = ebook.extension();
            if (isEmptyFile(content)) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("The ebook handler did not produce a file for loan " + loanId + ".");
            }
        } else if (isOpenFormat(chosenFormat)) {
            // DRM-free: fulfill directly — no external tool required.
            byte[] bytes = fulfillOpen(identity, authToken, loanId, chosenFormat);
            if (bytes == null || bytes.length == 0) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("Open fulfillment returned no data for loan " + loanId);
            }
            extension = fileExtension(chosenFormat);
            content = stageBytes(workDir, bytes, extension);
        } else {
            // Adobe format: hand the ACSM to the configured external tool to procure the book.
            byte[] acsm = getAcsm(identity, authToken, loanId, chosenFormat);
            if (acsm == null || acsm.length == 0) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("Could not fetch the ACSM for loan " + loanId);
            }
            byte[] bytes = acsmHandler.handle(acsm, fileExtension(chosenFormat), toolLogSink(titleId));
            if (bytes == null || bytes.length == 0) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("The external ACSM handler did not produce a book file for loan "
                        + loanId + ".");
            }
            extension = fileExtension(chosenFormat);
            content = stageBytes(workDir, bytes, extension);
        }

        // The read-in-browser handler picks its own container, so take the type from what it wrote
        // rather than from the format id (which says only "ebook-overdrive").
        BookFileType fileType = isEbookHandlerFormat(chosenFormat)
                ? fileTypeFromExtension(extension)
                : bookFileType(chosenFormat);
        // Pull the full OverDrive catalog metadata for this title so the import can overlay every field
        // (description, publisher, series, subjects, language, …), not just the handful the borrow request
        // carried. Fall back to the request-supplied fields if the lookup fails.
        BookMetadata metadata = overDriveParser.fetchTitleMetadata(titleId);
        if (metadata == null) {
            metadata = buildImportMetadata(title, author, coverUrl, isbn, titleId);
        }
        String fileName = buildFileName(title, loanId, extension);
        MediaKind kind = fileType == BookFileType.AUDIOBOOK ? MediaKind.AUDIOBOOK : MediaKind.EBOOK;
        // Replace: drop the copy being superseded only now. Everything that can realistically fail —
        // borrow, external tool, download — has already succeeded and the new file is staged on disk,
        // so this is the latest possible moment to delete, and it frees the target path the re-import
        // is about to write to.
        deleteReplacedBook(replaceBookId, loanId);
        Book book = importOrBookdrop(content, fileName, fileType, kind, metadata, libraryId, pathId, title);

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
        entity.setBookId(book != null ? book.getId() : null);
        entity.setAutoImportFailures(0);
        entity.setLastSync(Instant.now());
        loanRepository.save(entity);

        recordAudit(alreadyBorrowed ? OverDriveAuditAction.IMPORT : OverDriveAuditAction.BORROW_AND_IMPORT,
                identity, titleId, loanId, book != null ? book.getId() : null, title,
                book != null ? "Imported to library" : "Dropped into Bookdrop");
        log.info("OverDrive borrow-and-import complete: loan {} ({}) -> {}", loanId, chosenFormat,
                book != null ? "book " + book.getId() : "Bookdrop");
        return book;
        } catch (RuntimeException e) {
            recordAuditFailure(alreadyBorrowed ? OverDriveAuditAction.IMPORT : OverDriveAuditAction.BORROW_AND_IMPORT,
                    identity, titleId, null, title, e.getMessage());
            throw e;
        } finally {
            FileUtils.deleteDirectoryQuietly(workDir);
        }
      }

      /**
       * Delete the book a "replace" import supersedes, so the re-import takes its place rather than
       * adding a second copy. No-op when {@code replaceBookId} is null (a plain import).
       *
       * <p>Callers must invoke this only <b>after</b> the replacement file is fulfilled and staged:
       * deleting is irreversible, so it must not happen while a borrow or an external tool could still
       * fail. Deleting also frees the library path the naming pattern resolves to, which is what lets
       * the new file land exactly where the old one was instead of tripping the collision check.
       *
       * <p>The loan's book link is cleared first: if the import that follows fails anyway, the row must
       * not be left pointing at a book that no longer exists.
       */
      void deleteReplacedBook(Long replaceBookId, String loanId) { // package-private for testing
        if (replaceBookId == null) {
            return;
        }
        if (!bookRepository.existsById(replaceBookId)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("The copy to replace (book " + replaceBookId + ") no longer exists. "
                    + "Reload your loans and try importing again.");
        }
        loanRepository.findByUserIdAndOverdriveLoanId(currentUserId(), loanId)
                .filter(row -> replaceBookId.equals(row.getBookId()))
                .ifPresent(row -> {
                    row.setBookId(null);
                    loanRepository.save(row);
                });
        // deleteBooks echoes the requested ids back as "deleted" regardless of what it actually removed
        // (it silently skips books outside the user's libraries), so confirm the deletion ourselves —
        // otherwise the import would fail later with an opaque "a file already exists" error.
        bookService.deleteBooks(Set.of(replaceBookId));
        if (bookRepository.existsById(replaceBookId)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Could not delete the existing copy (book " + replaceBookId
                    + "), so it was not replaced. Check that you have permission to delete it.");
        }
        log.info("OverDrive replace: deleted book {} before re-importing loan {}", replaceBookId, loanId);
      }

      /** A scratch directory for one import's fulfilled files; the caller must delete it when done. */
      private static Path createWorkDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw ApiError.INTERNAL_SERVER_ERROR.createException(e, "Could not create a temporary directory for the import: " + e.getMessage());
        }
      }

      /**
       * Stage already-in-memory fulfilled bytes as a file in the work dir, so every import path feeds
       * the same file-based pipeline. Only used for ebooks (open + ACSM), which are small; audiobooks
       * and magazines are written straight to disk by their handlers and never buffered.
       */
      private static Path stageBytes(Path workDir, byte[] bytes, String extension) {
        try {
            Path staged = workDir.resolve("fulfilled." + extension);
            Files.write(staged, bytes);
            return staged;
        } catch (IOException e) {
            throw ApiError.INTERNAL_SERVER_ERROR.createException(e, "Could not stage the fulfilled file: " + e.getMessage());
        }
      }

      /** Whether a produced file is missing or zero-length (i.e. the handler effectively produced nothing). */
      private static boolean isEmptyFile(Path file) {
        return fileSize(file) <= 0;
      }

      /** Size of a produced file in bytes, or -1 when it is missing or unreadable. */
      private static long fileSize(Path file) {
        try {
            return file != null && Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (IOException e) {
            return -1;
        }
      }

      /**
       * Import the fulfilled file into the resolved destination library, or drop it into Bookdrop when
       * there's no destination or the destination wouldn't keep this file type (it purges disallowed
       * formats on scan). Shared by the borrow-and-import and magazine paths.
       */
      private Book importOrBookdrop(Path content, String fileName, BookFileType fileType, MediaKind kind,
                                    BookMetadata metadata, Long libraryId, Long pathId, String title) {
        return importOrBookdrop(
                List.of(new ProducedFile(content, fileName, fileType)),
                kind, metadata, libraryId, pathId, title);
      }

      /**
       * One file to import, part of a possibly-multi-file item (e.g. a magazine's two EPUB formats).
       * {@code file} points into the import's work directory and is moved into place by the import.
       */
      private record ProducedFile(Path file, String fileName, BookFileType fileType) {}

      /**
       * Multi-file variant: import all files as a single book when the destination accepts every one of
       * their formats, otherwise drop them all into Bookdrop (where the watcher groups them by folder).
       * The files are all formats of the same item (e.g. a magazine's two EPUBs), so they must stay
       * together — all-or-nothing — rather than importing some and dropping others.
       *
       * <p>The first file becomes the book's primary format; each remaining file is imported as its own
       * book and then merged into the primary as an additional format via the shared
       * {@link org.booklore.service.book.BookFileAttachmentService}. Each {@code importBook} and the
       * merge are cross-bean calls so their {@code @Transactional} boundaries apply (the metadata overlay
       * touches lazy collections, and the merge needs the source books committed and findable).
       */
      private Book importOrBookdrop(List<ProducedFile> files, MediaKind kind,
                                    BookMetadata metadata, Long libraryId, Long pathId, String title) {
        ImportDestination dest = resolveImportDestination(kind, libraryId, pathId);
        Long destLibraryId = dest.libraryId();
        Long destPathId = dest.pathId();
        boolean allAccepted = destLibraryId != null && destPathId != null
                && files.stream().allMatch(f -> overDriveImportService.acceptsFormat(destLibraryId, f.fileType()));
        if (allAccepted) {
            return importAsOneBook(files, destLibraryId, destPathId, metadata);
        }
        if (destLibraryId != null && destPathId != null) {
            log.warn("Destination library {} does not accept every format of '{}' — dropping {} file(s) into "
                    + "Bookdrop instead of importing (they would be purged on the next scan).",
                    destLibraryId, title, files.size());
        }
        for (ProducedFile f : files) {
            overDriveImportService.dropToBookdrop(f.file(), f.fileName());
        }
        return null;
      }

      /**
       * Import every file as one book: the first is the primary format and the rest are attached to it,
       * so a multi-format item (e.g. a magazine's reflowable + layout EPUBs) appears once with selectable
       * formats.
       *
       * <p>The library naming pattern is title-based, so importing several files under the same metadata
       * would resolve to the same path and collide. Each extra file is therefore imported under a
       * distinct transient title (a unique path), then merged into the primary with {@code moveFiles=true}
       * — which relocates the file next to the primary (auto-renaming e.g. {@code …_1.epub}) and discards
       * the transient book. Cross-bean calls so {@code importBook}/{@code attachBookFiles} keep their
       * own transactions.
       */
      private Book importAsOneBook(List<ProducedFile> files, Long libraryId, Long pathId, BookMetadata metadata) {
        ProducedFile first = files.getFirst();
        Book primary = overDriveImportService.importBook(
                first.file(), first.fileName(), libraryId, pathId, metadata, first.fileType());
        if (files.size() == 1 || primary == null || primary.getId() == null) {
            return primary;
        }
        String baseTitle = metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()
                ? metadata.getTitle() : "overdrive";
        List<Long> attachIds = new ArrayList<>();
        int index = 1;
        for (ProducedFile extra : files.subList(1, files.size())) {
            // Distinct transient title → distinct import path (the merge below relocates + renames it,
            // and the transient book/title is thrown away).
            index++;
            BookMetadata extraMeta = (metadata != null ? metadata.toBuilder() : BookMetadata.builder())
                    .title(baseTitle + " (" + index + ")").build();
            Book extraBook = overDriveImportService.importBook(
                    extra.file(), extra.fileName(), libraryId, pathId, extraMeta, extra.fileType());
            if (extraBook != null && extraBook.getId() != null) {
                attachIds.add(extraBook.getId());
            }
        }
        if (attachIds.isEmpty()) {
            return primary;
        }
        var response = bookFileAttachmentService.attachBookFiles(primary.getId(), attachIds, true);
        log.info("OverDrive import: attached {} additional format(s) to book id={}", attachIds.size(), primary.getId());
        return response.updatedBook() != null ? response.updatedBook() : primary;
      }

      /**
       * Import a magazine issue. Unlike borrow-and-import, Grimmory does <b>no</b> borrow: the external
       * magazine tool authenticates from the card+PIN and borrows + fulfils the issue itself (mediaType
       * "magazine", no formatId). The produced file is a PDF or EPUB — its type is taken from the extension.
       * A loan row is tracked keyed by the title id (a magazine loan's id equals its title id) so the
       * Loans tab links the imported book.
       */
      public Book importMagazine(String identity, String titleId, Long libraryId, Long pathId,
                                 String title, String author, String coverUrl, String isbn, Long replaceBookId) {
        // As with borrow-and-import: the tool's output is staged here and moved into the library, never
        // buffered in heap. Deleted in the finally, after any successful import has moved its files out.
        Path workDir = createWorkDir("overdrive-magazine-import-");
        try {
            resolveToken(identity); // validate the card is accessible even though the tool re-auths

            MagazineHandler.Result magazine = magazineHandler.handle(
                    magazineRequest(identity, titleId), workDir, toolLogSink(titleId));
            List<MagazineHandler.OutputFile> produced = magazine != null ? magazine.files() : null;
            if (produced == null || produced.isEmpty()) {
                throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("The magazine handler did not produce a file for title " + titleId + ".");
            }

            BookMetadata metadata = overDriveParser.fetchTitleMetadata(titleId);
            if (metadata == null) {
                metadata = buildImportMetadata(title, author, coverUrl, isbn, titleId);
            }

            // A magazine issue arrives as several files — typically two EPUBs (a fixed "as-is" layout and
            // a reflowable text version) the tool names distinctly. Import them as ONE book with the
            // extra formats attached; the first (stable name order) is the primary. Each file keeps its
            // own (sanitized) name so the two co-located formats stay distinct, with a uniqueness guard
            // as a backstop against accidental collisions.
            List<MagazineHandler.OutputFile> ordered = orderMagazineFormats(produced);
            Set<String> usedNames = new HashSet<>();
            List<ProducedFile> files = new ArrayList<>(ordered.size());
            for (MagazineHandler.OutputFile f : ordered) {
                String base = stripExtension(f.fileName());
                if (base.isBlank()) {
                    base = title;
                }
                files.add(new ProducedFile(
                        f.file(),
                        uniqueImportName(buildFileName(base, titleId, f.extension()), usedNames),
                        fileTypeFromExtension(f.extension())));
            }
            String extension = ordered.getFirst().extension();
            // See doBorrowAndImport: replace as late as possible, once the files are safely on disk.
            deleteReplacedBook(replaceBookId, titleId);
            Book book = importOrBookdrop(files, MediaKind.MAGAZINE, metadata, libraryId, pathId, title);

            Long userId = currentUserId();
            OverDriveLoanEntity entity = loanRepository.findByUserIdAndOverdriveLoanId(userId, titleId)
                    .orElseGet(() -> {
                        OverDriveLoanEntity e = new OverDriveLoanEntity();
                        e.setOverdriveLoanId(titleId);
                        e.setIdentity(identity);
                        e.setUserId(userId);
                        return e;
                    });
            entity.setTitle(title);
            entity.setAuthor(author);
            entity.setFormatId(FORMAT_MAGAZINE);
            entity.setState("ACTIVE");
            entity.setFulfilled(true);
            entity.setBookId(book != null ? book.getId() : null);
            entity.setAutoImportFailures(0);
            entity.setLastSync(Instant.now());
            loanRepository.save(entity);

            recordAudit(OverDriveAuditAction.BORROW_AND_IMPORT, identity, titleId, titleId,
                    book != null ? book.getId() : null, title,
                    book != null ? "Imported to library" : "Dropped into Bookdrop");
            log.info("OverDrive magazine import complete: title {} (.{}) -> {}", titleId, extension,
                    book != null ? "book " + book.getId() : "Bookdrop");
            return book;
        } catch (RuntimeException e) {
            recordAuditFailure(OverDriveAuditAction.BORROW_AND_IMPORT, identity, titleId, null, title, e.getMessage());
            throw e;
        } finally {
            FileUtils.deleteDirectoryQuietly(workDir);
        }
      }

      /**
       * Build the read-in-browser ebook handoff request — the ebook analogue of
       * {@link #audiobookRequest}, carrying the offered format id so the tool knows which format to
       * rebuild from.
       */
      private EbookHandler.Request ebookRequest(String identity, String titleId, String formatId) {
        HandlerCard card = handlerCard(identity, "ebooks");
        return new EbookHandler.Request(sentryBaseUrl, card.cardNumber(), card.pin(), card.libraryKey(),
                card.websiteId(), card.ilsName(), identity, titleId, formatId);
      }

      /** A card's decrypted credentials + library identifiers, as the manifest handlers need them. */
      private record HandlerCard(String cardNumber, String pin, String libraryKey, String websiteId,
                                 String ilsName) {}

      /**
       * Resolve and decrypt a card's stored number + PIN for a manifest-based handler. The tools
       * authenticate themselves from card credentials (their own chip + UA), so we deliberately never
       * hand out Grimmory's web chip token — which means the card must have been linked by number + PIN
       * with a credential key configured.
       *
       * @param plural what the handler downloads ("audiobooks"/"magazines"/"ebooks"), for the error text
       */
      private HandlerCard handlerCard(String identity, String plural) {
        OverDriveTokenEntity card = accessibleTokenRow(currentUserId(), identity)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No such card for fulfillment: " + identity));
        String label = (card.getCardName() != null && !card.getCardName().isBlank()
                ? "\"" + card.getCardName() + "\" " : "") + "(" + identity + ")";
        if (!credentialCipher.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Downloading " + plural + " needs stored card credentials, but "
                    + "credential storage is off: set OVERDRIVE_CREDENTIAL_KEY (base64 16/24/32 bytes) and "
                    + "re-link the card by number + PIN. (Settings → OverDrive → diagnostics shows "
                    + "credentialStorageEnabled.)");
        }
        if (card.getCredCard() == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Card " + label + " has no stored card + PIN, so it can't download "
                    + plural + ". Only cards linked by number + PIN store credentials — setup-code and "
                    + "pasted-token links don't. Re-link THIS card by number + PIN, then retry. (Settings → "
                    + "OverDrive → diagnostics shows each card's credentialsStored.)");
        }
        return new HandlerCard(
                credentialCipher.decrypt(card.getCredCard()),
                card.getCredPin() != null ? credentialCipher.decrypt(card.getCredPin()) : null,
                card.getLibraryKey(), card.getWebsiteId(), card.getIlsName());
      }

      /** Build the magazine handoff request (card+PIN, no formatId) — mirrors {@link #audiobookRequest}. */
      private MagazineHandler.Request magazineRequest(String identity, String titleId) {
        OverDriveTokenEntity card = accessibleTokenRow(currentUserId(), identity)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No such card for magazine fulfillment: " + identity));
        String label = (card.getCardName() != null && !card.getCardName().isBlank()
                ? "\"" + card.getCardName() + "\" " : "") + "(" + identity + ")";
        if (!credentialCipher.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Magazine download needs stored card credentials, but credential "
                    + "storage is off: set OVERDRIVE_CREDENTIAL_KEY and re-link the card by number + PIN.");
        }
        if (card.getCredCard() == null) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Card " + label + " has no stored card + PIN, so it can't download "
                    + "magazines. Re-link THIS card by number + PIN, then retry.");
        }
        String cardNumber = credentialCipher.decrypt(card.getCredCard());
        String pin = card.getCredPin() != null ? credentialCipher.decrypt(card.getCredPin()) : null;
        return new MagazineHandler.Request(sentryBaseUrl, cardNumber, pin, card.getLibraryKey(),
                card.getWebsiteId(), card.getIlsName(), identity, titleId);
      }

      /** OverDrive magazine format id (the tool fulfils the issue as PDF or EPUB). */
      private static final String FORMAT_MAGAZINE = "magazine-overdrive";

      private String buildFileName(String title, String loanId, String extension) {
        String base = (title != null && !title.isBlank()) ? title : ("overdrive-" + loanId);
        return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + "." + extension;
      }

      /** Build the catalog-sourced metadata to layer onto the imported EPUB. */
      private BookMetadata buildImportMetadata(String title, String author, String coverUrl, String isbn) {
        return buildImportMetadata(title, author, coverUrl, isbn, null);
      }

      /** As above, recording the OverDrive title id so {@code {overdriveId}} can be used in path patterns. */
      private BookMetadata buildImportMetadata(String title, String author, String coverUrl, String isbn,
                                               String titleId) {
        String cleanedIsbn = isbn != null ? isbn.replaceAll("[^0-9Xx]", "") : null;
        String isbn13 = cleanedIsbn != null && cleanedIsbn.length() == 13 ? cleanedIsbn : null;
        String isbn10 = cleanedIsbn != null && cleanedIsbn.length() == 10 ? cleanedIsbn : null;
        return BookMetadata.builder()
                .overdriveId(titleId)
                .title(title)
                .authors(author != null && !author.isBlank() ? List.of(author) : null)
                .thumbnailUrl(coverUrl != null && !coverUrl.isBlank() ? coverUrl : null)
                .isbn13(isbn13)
                .isbn10(isbn10)
                .build();
      }

      private OverDriveCatalogItem toCatalogItem(String libraryKey, OverDriveApiResponse.Item item) {
        List<String> formats = importableFormats(item);
        String isbn = OverDriveItemExtractor.primaryIsbn(item);
        boolean available = Boolean.TRUE.equals(item.getAvailable());
        boolean holdable = Boolean.TRUE.equals(item.getHoldable());
        OverDriveLibraryAvailability availability = new OverDriveLibraryAvailability(
                libraryKey,
                available,
                holdable,
                item.getAvailableCopies(),
                item.getOwnedCopies(),
                item.getHoldsCount(),
                item.getEstimatedWaitDays(),
                item.getLuckyDayAvailableCopies());
        return new OverDriveCatalogItem(
                item.getId(),
                formats.isEmpty() ? pickBorrowFormatId(item) : formats.getFirst(),
                item.getTitle(),
                item.getSubtitle(),
                OverDriveItemExtractor.primaryAuthor(item),
                OverDriveItemExtractor.coverHref(item.getCovers()),
                isbn,
                available,
                holdable,
                item.getAvailableCopies(),
                item.getOwnedCopies(),
                item.getHoldsCount(),
                item.getEstimatedWaitDays(),
                item.getLuckyDayAvailableCopies(),
                Boolean.TRUE.equals(item.getPreRelease()),
                formats,
                new ArrayList<>(List.of(availability)),
                resolveLinkedBookId(isbn, OverDriveItemExtractor.asin(item)),
                OverDriveItemExtractor.languageCode(item),
                item.getEdition(),
                isAudiobookItem(item),
                OverDriveItemExtractor.narrator(item),
                OverDriveItemExtractor.audiobookDuration(item),
                isMagazineItem(item));
      }

      /**
       * Find an existing library book that matches an OverDrive title by ISBN, so the UI can link to it
       * (and discourage re-borrowing a title already in the library). Matches ISBN-13 first, then ISBN-10.
       *
       * @return the matching book id, or null if none / no usable ISBN
       */
      public Long resolveLinkedBookId(String isbn) {
        return resolveLinkedBookId(isbn, null);
      }

      /**
       * Match an OverDrive title to an existing library book by ISBN, then by ASIN. The ASIN fallback
       * links titles that carry no usable ISBN (e.g. audiobooks) to a library book with that ASIN.
       */
      public Long resolveLinkedBookId(String isbn, String asin) {
        return resolveLinkedBookId(null, isbn, asin);
      }

      /**
       * Match an OverDrive title to an existing library book, preferring the OverDrive id.
       *
       * <p>The id is the only exact key of the three. It identifies an <em>edition</em>, so a hit means
       * the library already holds the very file this title would produce. ISBN and ASIN are weaker:
       * OverDrive editions of one work can share an ISBN, audiobooks frequently carry neither, and a
       * print ISBN can match a book that is not this recording at all. They stay as fallbacks for the
       * books imported before ids were recorded, and for loans borrowed outside Grimmory.
       *
       * @return the matching book id, or null if the library has nothing for this title
       */
      public Long resolveLinkedBookId(String overdriveId, String isbn, String asin) {
        // Scope the match to libraries the current user can access, so linking an OverDrive loan to an
        // existing book can't leak a book id from a library the user isn't assigned to. Admins match
        // globally (they can access every library anyway).
        boolean admin = currentUserIsAdmin();
        List<Long> libraryIds = admin ? null : accessibleLibraryIds();
        if (!admin && libraryIds.isEmpty()) {
            return null; // no accessible libraries → nothing to link
        }
        if (overdriveId != null && !overdriveId.isBlank()) {
            String id = overdriveId.trim();
            Long match = (admin ? bookRepository.findIdsByOverdriveId(id)
                    : bookRepository.findIdsByOverdriveIdAndLibraryIdIn(id, libraryIds))
                    .stream().findFirst().orElse(null);
            if (match != null) {
                return match;
            }
        }
        if (isbn != null && !isbn.isBlank()) {
            String cleaned = isbn.replaceAll("[^0-9Xx]", "");
            if (cleaned.length() == 13) {
                Long id = (admin ? bookRepository.findIdsByIsbn13(cleaned)
                        : bookRepository.findIdsByIsbn13AndLibraryIdIn(cleaned, libraryIds))
                        .stream().findFirst().orElse(null);
                if (id != null) {
                    return id;
                }
            } else if (cleaned.length() == 10) {
                Long id = (admin ? bookRepository.findIdsByIsbn10(cleaned)
                        : bookRepository.findIdsByIsbn10AndLibraryIdIn(cleaned, libraryIds))
                        .stream().findFirst().orElse(null);
                if (id != null) {
                    return id;
                }
            }
        }
        if (asin != null && !asin.isBlank()) {
            return (admin ? bookRepository.findIdsByAsin(asin.trim())
                    : bookRepository.findIdsByAsinAndLibraryIdIn(asin.trim(), libraryIds))
                    .stream().findFirst().orElse(null);
        }
        return null;
      }

      /** The ids of libraries the current user is assigned to (empty when none). */
      private List<Long> accessibleLibraryIds() {
        BookLoreUser user = currentUser();
        if (user.getAssignedLibraries() == null) {
            return List.of();
        }
        return user.getAssignedLibraries().stream()
                .map(l -> l.getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
      }

      /**
       * Resolve the library book id for a loan: prefer the exact id recorded when this loan was imported
       * (stored on the loan), falling back to an ISBN match so loans borrowed elsewhere still link.
       *
       * @return the matching book id, or null if none
       */
      public Long resolveLoanBookId(String loanId, String isbn) {
        return resolveLoanBookId(loanId, isbn, null);
      }

      public Long resolveLoanBookId(String loanId, String isbn, String asin) {
        if (loanId != null && !loanId.isBlank()) {
            Long userId = currentUserId();
            Long stored = loanRepository.findByUserIdAndOverdriveLoanId(userId, loanId)
                    .map(OverDriveLoanEntity::getBookId)
                    .orElse(null);
            if (stored != null) {
                return stored;
            }
        }
        // A loan id is the title id, so it doubles as the OverDrive id to match on — that catches a
        // title imported under a different user, or one whose loan row lost its link.
        return resolveLinkedBookId(loanId, isbn, asin);
      }

      /**
       * The supported/importable format ids this title offers, in the operator's preference order (see
       * {@link #formatPreference()}) — the list the UI presents so the user may pick a non-default
       * version. Adobe formats are only included when an ACSM handler is configured (else they can't be
       * imported). Empty when the title offers nothing we can import.
       */
      private List<String> importableFormats(OverDriveApiResponse.Item item) {
        if (item.getFormats() == null || item.getFormats().isEmpty()) {
            return List.of();
        }
        Set<String> offered = item.getFormats().stream()
                .map(OverDriveApiResponse.Item.Format::getId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        boolean acsm = acsmHandler.isConfigured();
        List<String> ordered = new ArrayList<>();
        for (String f : formatPreference()) {
            if (offered.contains(f) && !ordered.contains(f) && (isOpenFormat(f) || acsm)) {
                ordered.add(f);
            }
        }
        // Audiobook formats, when a handler is configured. A title is one medium, so these only appear
        // for audiobook titles (which carry no ebook formats). Prefer audiobook-mp3.
        if (audiobookHandler.isConfigured()) {
            if (offered.contains(FORMAT_AUDIOBOOK_MP3)) {
                ordered.add(FORMAT_AUDIOBOOK_MP3);
            }
            for (String f : offered) {
                if (isAudiobookFormat(f) && !ordered.contains(f)) {
                    ordered.add(f);
                }
            }
        }
        // Read-in-browser ebooks, when the ebook handler is configured. Last of the ebook options:
        // any real download format above is preferred.
        if (ebookHandler.isConfigured() && offered.contains(FORMAT_EBOOK_OVERDRIVE)
                && !ordered.contains(FORMAT_EBOOK_OVERDRIVE)) {
            ordered.add(FORMAT_EBOOK_OVERDRIVE);
        }
        // Magazine formats, when the magazine handler is configured (a title is one medium).
        if (magazineHandler.isConfigured()) {
            for (String f : offered) {
                if (isMagazineFormat(f) && !ordered.contains(f)) {
                    ordered.add(f);
                }
            }
        }
        return ordered;
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

      /**
       * DELETE /card/{cardId}/loan/{loanId} — return a book.
       */
      public void returnBook(String identity, String loanId) {
        assertCanReturn(identity);
        String url = sentryBaseUrl + "/card/" + identity + "/loan/" + loanId;

        try {
            withChipRecovery(identity, authToken -> restClient.delete()
                    .uri(url)
                    .headers(h -> h.addAll(libbyHeaders(authToken)))
                    .retrieve()
                    .toBodilessEntity());

             // Mark loan as returned on the current user's cached row. Scope by user (not just identity):
             // a shared card caches the same loan under both the owner and each sharee, so an
             // identity-only lookup would match multiple rows.
            loanRepository.findByUserIdAndOverdriveLoanId(currentUserId(), loanId)
                    .ifPresent(entity -> {
                        entity.setState("RETURNED");
                        loanRepository.save(entity);
                     });

            recordAudit(OverDriveAuditAction.RETURN, identity, null, loanId, null, null, null);
            log.info("OverDrive book returned: {}", loanId);
             } catch (Exception e) {
               log.error("OverDrive return failed for loan {}: {}", loanId, e.getMessage());
               recordAuditFailure(OverDriveAuditAction.RETURN, identity, null, loanId, e.getMessage());
               throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive return failed: " + e.getMessage());
             }
           }

            private byte[] getAcsm(String identity, String authToken, String loanId, String formatId) {
               return fetchFulfillment(identity, authToken, loanId, formatId);
             }

            // ── Persistence Helpers ──────────────────────────────────────────────
      /**
       * GET /card/{cardId}/hold/{formatId} — place a hold.
       */
      public void placeHold(String identity, String titleId) {
        assertNotChurnLimited(identity, "placing holds");
        String url = sentryBaseUrl + "/card/" + identity + "/hold/" + titleId;

        try {
            withChipRecovery(identity, authToken -> restClient.post()
                    .uri(url)
                    .headers(h -> h.addAll(libbyHeaders(authToken)))
                    .retrieve()
                    .toBodilessEntity());

            recordAudit(OverDriveAuditAction.HOLD_PLACED, identity, titleId, null, null,
                    titleOf(titleId), null);
            log.info("OverDrive hold placed for title {}", titleId);
         } catch (Exception e) {
            log.error("OverDrive hold failed: {}", e.getMessage());
            recordAuditFailure(OverDriveAuditAction.HOLD_PLACED, identity, titleId, null,
                    titleOf(titleId), e.getMessage());
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive hold failed: " + e.getMessage());
         }
      }

      /**
       * Cancel a hold on a title.
       */
      public void cancelHold(String identity, String titleId) {
        assertNotChurnLimited(identity, "cancelling holds");
        String url = sentryBaseUrl + "/card/" + identity + "/hold/" + titleId;

        try {
            withChipRecovery(identity, authToken -> restClient.delete()
                    .uri(url)
                    .headers(h -> h.addAll(libbyHeaders(authToken)))
                    .retrieve()
                    .toBodilessEntity());

            recordAudit(OverDriveAuditAction.HOLD_CANCELLED, identity, titleId, null, null,
                    titleOf(titleId), null);
            log.info("OverDrive hold cancelled for title {}", titleId);
         } catch (Exception e) {
            log.error("OverDrive cancel hold failed: {}", e.getMessage());
            recordAuditFailure(OverDriveAuditAction.HOLD_CANCELLED, identity, titleId, null,
                    titleOf(titleId), e.getMessage());
            throw ApiError.OVERDRIVE_UPSTREAM_FAILED.createException("OverDrive cancel hold failed: " + e.getMessage());
         }
      }

     // ── Token Management ─────────────────────────────────────────────────

      /** Store (or replace) the current user's token for a specific card. Persisted across restarts. */
      @Transactional
      public void storeToken(String identity, String cardName, String libraryKey, String token) {
        storeToken(identity, cardName, libraryKey, token, null);
      }

      /**
       * Store a token for a specific user. {@code ownerUserId} writes the row for someone else and
       * requires the cross-user card permission; null means the caller.
       */
      public void storeToken(String identity, String cardName, String libraryKey, String token, Long ownerUserId) {
        Long userId = resolveCardOwner(ownerUserId);
        OverDriveTokenEntity entity = tokenRepository.findByUserIdAndIdentity(userId, identity)
                .orElseGet(OverDriveTokenEntity::new);
        entity.setUserId(userId);
        entity.setIdentity(identity);
        entity.setCardName(cardName);
        entity.setLibraryKey(libraryKey);
        entity.setToken(token);
        entity.setExpiresAt(tokenExpiryEpoch(token));
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        tokenRepository.save(entity);
        log.info("OverDrive token stored for user {} (card {})", userId, identity);
      }

      /** Remove the current user's stored token for a specific card. */
      @Transactional
      public void removeToken(String identity) {
        removeToken(identity, null);
      }

      /**
       * Remove a stored token. {@code ownerUserId} targets another user's card and requires the
       * cross-user card permission; null means the caller's own.
       */
      @Transactional
      public void removeToken(String identity, Long ownerUserId) {
        OverDriveTokenEntity card = administrableCard(identity, ownerUserId);
        // Capture the card name before deletion so the history entry can still name the unlinked card.
        String cardName = card.getCardName();
        Long ownerId = card.getUserId();
        // Shares point at the token row, so drop them with it rather than leaving them dangling.
        cardShareRepository.findByTokenId(card.getId()).forEach(cardShareRepository::delete);
        tokenRepository.deleteByUserIdAndIdentity(ownerId, identity);
        recordAudit(OverDriveAuditAction.CARD_UNLINKED, identity, null, null, null, null,
                (cardName != null ? "Unlinked " + cardName : "Unlinked card") + onBehalfOfSuffix(ownerId));
        log.info("OverDrive card {} removed for user {} by user {}", identity, ownerId, currentUserId());
      }

      /** Whether the current user can use the given card (owns it or it's shared with them). */
      public boolean hasToken(String identity) {
        return accessibleTokenRow(currentUserId(), identity).isPresent();
      }

     // ── Card Sharing ─────────────────────────────────────────────────────

      /**
       * Candidate users to share a card with: everyone except the current user (minimal fields).
       * Only users who actually own a linked card (or those who may manage any user's cards) may
       * enumerate the roster — a user with nothing to share has no need for the picker, so this keeps
       * the full user list from being readable by every authenticated user.
       */
      public List<OverDriveShareUser> shareableUsers() {
        return shareableUsers(null);
      }

      /**
       * Candidate share targets for a specific card owner. {@code ownerUserId} names whose card is being
       * shared — that user is excluded (they already have it) rather than the caller, so a cross-user
       * manager can share a card with themselves. Cross-user use requires the share permission.
       */
      public List<OverDriveShareUser> shareableUsers(Long ownerUserId) {
        Long me = currentUserId();
        Long owner = ownerUserId != null ? ownerUserId : me;
        if (!owner.equals(me) && !currentUserCanManageAllShares()) {
            throw ApiError.FORBIDDEN.createException("You can only manage sharing for cards you own");
        }
        if (owner.equals(me) && tokenRepository.findByUserId(me).isEmpty() && !currentUserCanManageAllShares()) {
            return List.of();
        }
        // Permissions are fetch-joined: the filter below reads them, and this runs outside a session.
        return userRepository.findAllWithPermissions().stream()
                .filter(u -> u.getId() != null && !u.getId().equals(owner))
                .filter(OverDriveService::canUseOverdrive)
                .map(u -> new OverDriveShareUser(u.getId(), u.getUsername(), u.getName()))
                .sorted(Comparator.comparing(u -> shareUserSortKey(u), String.CASE_INSENSITIVE_ORDER))
                .toList();
      }

      /** A share is only useful to someone who can reach the OverDrive feature at all. */
      private static boolean canUseOverdrive(BookLoreUserEntity user) {
        var perms = user.getPermissions();
        return perms != null && (perms.isPermissionAdmin() || perms.isPermissionAccessOverdrive());
      }

      private static String shareUserSortKey(OverDriveShareUser u) {
        return u.name() != null && !u.name().isBlank() ? u.name() : (u.username() != null ? u.username() : "");
      }

      /**
       * Resolve the owner's card row for share management, enforcing that the caller may manage it: the
       * card's owner, or a user permitted to manage any user's cards. Throws 403 otherwise, 404 if no
       * such card exists.
       */
      private OverDriveTokenEntity manageableCard(String identity) {
        return manageableCard(identity, null);
      }

      private OverDriveTokenEntity manageableCard(String identity, Long ownerUserId) {
        Long me = currentUserId();
        if (ownerUserId != null && !ownerUserId.equals(me)) {
            // An explicit owner removes the ambiguity below, but is still a cross-user action.
            if (!currentUserCanManageAllShares()) {
                throw ApiError.FORBIDDEN.createException("You can only manage sharing for cards you own");
            }
            return tokenRepository.findByUserIdAndIdentity(ownerUserId, identity)
                    .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No such card: " + identity));
        }
        Optional<OverDriveTokenEntity> owned = tokenRepository.findByUserIdAndIdentity(me, identity);
        if (owned.isPresent()) {
            return owned.get();
        }
        if (currentUserCanManageAllShares()) {
            // (user_id, identity) is unique, so two users can each own a row for the same identity.
            // Rather than silently editing an arbitrary owner's shares, act only when it's unambiguous.
            List<OverDriveTokenEntity> matches = tokenRepository.findByIdentity(identity);
            if (matches.isEmpty()) {
                throw ApiError.GENERIC_NOT_FOUND.createException("No such card: " + identity);
            }
            if (matches.size() > 1) {
                throw ApiError.GENERIC_BAD_REQUEST.createException(
                        "Card " + identity + " is linked by multiple users; pass userId to pick one");
            }
            return matches.getFirst();
        }
        throw ApiError.FORBIDDEN.createException("You can only manage sharing for cards you own");
      }

      /** The users a card is currently shared with (the card's owner, or a cross-user card manager). */
      public List<OverDriveShareUser> listShares(String identity) {
        return listShares(identity, null);
      }

      public List<OverDriveShareUser> listShares(String identity, Long ownerUserId) {
        OverDriveTokenEntity card = manageableCard(identity, ownerUserId);
        return cardShareRepository.findByTokenId(card.getId()).stream()
                .map(s -> userRepository.findById(s.getSharedWithUserId()).orElse(null))
                .filter(Objects::nonNull)
                .map(u -> new OverDriveShareUser(u.getId(), u.getUsername(), u.getName()))
                .sorted(Comparator.comparing(u -> shareUserSortKey(u), String.CASE_INSENSITIVE_ORDER))
                .toList();
      }

      /**
       * Replace the set of users a card is shared with (owner or cross-user card manager). The owner is never a
       * valid target; unknown user ids are ignored. Existing grants not in the new set are revoked.
       */
      @Transactional
      public void setShares(String identity, List<Long> userIds) {
        setShares(identity, userIds, null);
      }

      @Transactional
      public void setShares(String identity, List<Long> userIds, Long ownerUserId) {
        OverDriveTokenEntity card = manageableCard(identity, ownerUserId);
        Set<Long> desired = new LinkedHashSet<>();
        if (userIds != null) {
            for (Long uid : userIds) {
                if (uid != null && !uid.equals(card.getUserId()) && userRepository.existsById(uid)) {
                    desired.add(uid);
                }
            }
        }
        List<OverDriveCardShareEntity> existing = cardShareRepository.findByTokenId(card.getId());
        Set<Long> current = existing.stream()
                .map(OverDriveCardShareEntity::getSharedWithUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Revoke grants no longer wanted.
        for (OverDriveCardShareEntity share : existing) {
            if (!desired.contains(share.getSharedWithUserId())) {
                cardShareRepository.delete(share);
            }
        }
        // Add newly-wanted grants.
        for (Long uid : desired) {
            if (!current.contains(uid)) {
                cardShareRepository.save(OverDriveCardShareEntity.builder()
                        .tokenId(card.getId())
                        .sharedWithUserId(uid)
                        .createdAt(Instant.now())
                        .build());
            }
        }
        recordAudit(OverDriveAuditAction.SHARE_UPDATED, identity, null, null, null, null,
                desired.isEmpty() ? "Sharing cleared" : "Shared with " + desired.size() + " user(s)");
        log.info("OverDrive card {} shares set to {} user(s) by user {}", identity, desired.size(), currentUserId());
      }

      /**
       * The cards the current user can use: the ones they linked, plus any shared with them. Shared
       * cards carry {@code owned=false} and the owner's name so the UI can hide management actions;
       * owned cards carry how many other users they've been shared with.
       */
      public List<OverDriveCard> listCards() {
        Long userId = currentUserId();
        List<OverDriveTokenEntity> rows = accessibleTokenRows(userId);
        Instant now = Instant.now();
        // One ceilings query for the whole list. Both meters can show room on a card that has stopped
        // borrowing, so the card list is where the reason has to be legible.
        Map<String, BorrowLimits> limits = borrowLimitsFor(rows.stream()
                .map(OverDriveTokenEntity::getIdentity).filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream()
                .map(t -> {
                    boolean owned = userId.equals(t.getUserId());
                    // Only while it is still in force; a lapsed one is not worth telling the user about.
                    boolean resting = t.getChurnCooldownUntil() != null && t.getChurnCooldownUntil().isAfter(now);
                    return new OverDriveCard(t.getIdentity(), t.getCardName(), t.getLibraryKey(),
                            owned && t.getCredCard() != null, t.getDefaultLibraryId(), t.getDefaultPathId(),
                            owned, owned ? null : ownerName(t.getUserId()),
                            owned ? cardShareRepository.countByTokenId(t.getId()) : 0,
                            canAutoRenew(t), t.getExpiresAt(),
                            resting ? t.getChurnCooldownUntil().toString() : null,
                            // Not computed while resting: OverDrive's own refusal outranks a ceiling we
                            // set ourselves, and reporting both would say the same thing twice.
                            resting ? null
                                    : borrowCeilingReached(t.getIdentity(),
                                            limits.getOrDefault(t.getIdentity(), BorrowLimits.DEFAULTS)),
                            resting ? null
                                    : returnCeilingReached(t.getIdentity(),
                                            limits.getOrDefault(t.getIdentity(), BorrowLimits.DEFAULTS)));
                })
                .toList();
      }

      /**
       * Every user's linked cards, for a cross-user card manager. Returns the owner alongside each card
       * so management calls can name the target unambiguously (an identity can be linked by more than
       * one user). Throws 403 without the cross-user card permission.
       */
      public List<OverDriveManagedCard> listAllCards() {
        if (!currentUserCanManageAllCards()) {
            throw ApiError.FORBIDDEN.createException("You cannot manage other users' OverDrive cards");
        }
        return tokenRepository.findAll().stream()
                .map(t -> new OverDriveManagedCard(
                        t.getIdentity(), t.getCardName(), t.getLibraryKey(), t.getCredCard() != null,
                        t.getDefaultLibraryId(), t.getDefaultPathId(),
                        cardShareRepository.countByTokenId(t.getId()),
                        canAutoRenew(t), t.getExpiresAt(),
                        t.getUserId(), ownerName(t.getUserId())))
                .sorted(Comparator.comparing(OverDriveManagedCard::ownerName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(c -> c.name() != null ? c.name() : c.cardId(), String.CASE_INSENSITIVE_ORDER))
                .toList();
      }

      /**
       * Whether a card can silently re-link its token when it expires: it must have card+PIN on file,
       * retain the library sign-in details, and credential storage must currently be enabled (a
       * disabled/changed key can't decrypt the stored credentials). Same requirement as an audiobook
       * download. Ownership is not part of it — a sharee renews the owner's row (see {@link #relinkCard}).
       */
      private boolean canAutoRenew(OverDriveTokenEntity t) {
        return credentialCipher.isEnabled() && t.getCredCard() != null
                && t.getWebsiteId() != null && t.getIlsName() != null;
      }

      /** Display name (falling back to username) for a card owner, for the "shared by" label. */
      private String ownerName(Long userId) {
        return userRepository.findById(userId)
                .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getUsername())
                .orElse("another user");
      }

      /** Set a friendly display label for a card; a blank label clears it back to the default name. */
      public void setCardLabel(String identity, String label) {
        setCardLabel(identity, label, null);
      }

      public void setCardLabel(String identity, String label, Long ownerUserId) {
        OverDriveTokenEntity entity = administrableCard(identity, ownerUserId);
        entity.setCardName(label != null && !label.isBlank() ? label.trim() : null);
        tokenRepository.save(entity);
        recordAudit(OverDriveAuditAction.CARD_RELABELED, identity, null, null, null, null,
                (label != null && !label.isBlank() ? "Renamed to \"" + label.trim() + "\"" : "Label cleared")
                        + onBehalfOfSuffix(entity.getUserId()));
      }

      /**
       * Set (or clear) the default destination library + path for a card, remembered for next time.
       * Passing nulls clears the default so imports fall back to the Bookdrop folder.
       */
      public void setDefaultLibrary(String identity, Long libraryId, Long pathId) {
        setDefaultLibrary(identity, libraryId, pathId, null);
      }

      public void setDefaultLibrary(String identity, Long libraryId, Long pathId, Long ownerUserId) {
        OverDriveTokenEntity entity = administrableCard(identity, ownerUserId);
        entity.setDefaultLibraryId(libraryId);
        entity.setDefaultPathId(pathId);
        tokenRepository.save(entity);
      }

      /**
       * Check a specific title's availability across the given cards' libraries (for the current user).
       * Used by the Holds tab to surface whether a held title can be borrowed now at another of the
       * user's libraries. Returns one entry per distinct library that responded.
       */
      public List<OverDriveLibraryAvailability> titleAvailability(String titleId, List<String> cardIds) {
        if (titleId == null || titleId.isBlank()) {
            return List.of();
        }
        return availabilityForTitles(List.of(titleId), cardIds).getOrDefault(titleId, List.of());
      }

      /**
       * Per-library availability for many titles at once, keyed by title id. Issues a single batched
       * {@code /media/availability} call per library — all titles in one body, all libraries in
       * parallel — rather than a full media fetch per title × library. Checking a whole tab of holds
       * against the user's other libraries therefore costs one lightweight round-trip, not dozens.
       */
      public Map<String, List<OverDriveLibraryAvailability>> availabilityForTitles(List<String> titleIds, List<String> cardIds) {
        if (titleIds == null || titleIds.isEmpty() || cardIds == null || cardIds.isEmpty()) {
            return Map.of();
        }
        Long userId = currentUserId();
        Set<String> libraryKeys = new LinkedHashSet<>();
        for (String cardId : cardIds) {
            accessibleTokenRow(userId, cardId)
                    .map(OverDriveTokenEntity::getLibraryKey)
                    .filter(k -> k != null && !k.isBlank())
                    .ifPresent(libraryKeys::add);
        }
        // One concurrent fan-out across the libraries rather than a call per library in series.
        Map<String, Map<String, OverDriveApiResponse.Item>> byLibrary =
                overDriveParser.fetchAvailabilityBulk(libraryKeys, titleIds);
        Map<String, List<OverDriveLibraryAvailability>> result = new LinkedHashMap<>();
        for (String libraryKey : libraryKeys) {
            for (Map.Entry<String, OverDriveApiResponse.Item> entry
                    : byLibrary.getOrDefault(libraryKey, Map.of()).entrySet()) {
                result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(toLibraryAvailability(libraryKey, entry.getValue()));
            }
        }
        return result;
      }

      /** Extra metadata for a title, enriched from the catalog (narrator/edition/duration + media type). */
      public record MediaExtras(String narrator, String edition, String duration, boolean audiobook, boolean magazine) {}

      /**
       * Fetch narrator/edition/duration for many titles at once (one {@code /media/bulk} call), keyed by
       * title id. Used to enrich a card's loans/holds, which the sync feed carries only sparsely.
       */
      public Map<String, MediaExtras> mediaExtras(List<String> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = titleIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, OverDriveApiResponse.Item> bulk = overDriveParser.fetchMediaBulk(ids);
        Map<String, MediaExtras> out = new LinkedHashMap<>();
        for (Map.Entry<String, OverDriveApiResponse.Item> entry : bulk.entrySet()) {
            OverDriveApiResponse.Item item = entry.getValue();
            out.put(entry.getKey(), new MediaExtras(
                    OverDriveItemExtractor.narrator(item),
                    item.getEdition(),
                    OverDriveItemExtractor.audiobookDuration(item),
                    isAudiobookItem(item),
                    isMagazineItem(item)));
        }
        return out;
      }

      /** Map a Thunder availability/media item to our per-library availability DTO. */
      private static OverDriveLibraryAvailability toLibraryAvailability(String libraryKey, OverDriveApiResponse.Item item) {
        return new OverDriveLibraryAvailability(
                libraryKey,
                Boolean.TRUE.equals(item.getAvailable()),
                Boolean.TRUE.equals(item.getHoldable()),
                item.getAvailableCopies(),
                item.getOwnedCopies(),
                item.getHoldsCount(),
                item.getEstimatedWaitDays(),
                item.getLuckyDayAvailableCopies());
      }

      /** The card ids the current user can use (owned + shared with them). */
      public List<String> listIdentities() {
        return accessibleTokenRows(currentUserId()).stream()
                .map(OverDriveTokenEntity::getIdentity)
                .toList();
      }

      /** The stored token for a card the current user can use (owned or shared), or null. */
      public String getStoredToken(String identity) {
        return accessibleTokenRow(currentUserId(), identity)
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
       * Resolve the token to use for a card: the current user's stored token for that card (or a card
       * shared with them, via {@link #accessibleTokenRow}). Throws if the card is not accessible or has
       * no stored token. Callers cannot supply their own bearer token — doing so would bypass the
       * per-user ownership check and, when passed as a query param, leak the token into access logs.
       */
      private String resolveToken(String identity) {
        String stored = getStoredToken(identity);
        if (stored == null || stored.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
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