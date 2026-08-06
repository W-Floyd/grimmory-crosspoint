package org.booklore.service.metadata.parser;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.response.OverDriveApiResponse;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.BookUtils;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Metadata provider backed by the OverDrive "Thunder" catalog API — the unofficial, no-auth API the
 * Libby web client uses to search a library's collection. Read-only catalog metadata only; this does
 * not touch lending, checkout, or DRM.
 *
 * <p>Thunder is library-scoped, so a library key (the OverDrive "preferredKey" / website id, e.g.
 * {@code lapl}) must be configured under the provider settings. The API is undocumented; the endpoint
 * and {@link OverDriveApiResponse} field names reflect the observed contract and may need adjusting
 * if OverDrive changes it.
 */
@Slf4j
@Service
public class OverDriveParser implements BookParser {

    private static final String THUNDER_BASE_URL = "https://thunder.api.overdrive.com/v2/libraries";
    /** Library-agnostic single-title endpoint: .../v2/media/{titleId} returns one media object. */
    private static final String THUNDER_MEDIA_URL = "https://thunder.api.overdrive.com/v2/media";
    /** Libby's public, library-agnostic share link for a title id (e.g. .../title/618973). */
    private static final String LIBBY_TITLE_URL = "https://share.libbyapp.com/title/";
    /** Client id the Libby web client tags its Thunder calls with. */
    private static final String THUNDER_CLIENT_ID = "dewey";
    /** Thunder caps a single page at 100 items; larger perPage silently returns none. */
    private static final int RESULTS_PER_PAGE = 100;
    /** Hard cap on total search results collected across pages (bounds how far a broad query paginates). */
    private static final int MAX_TOTAL_RESULTS = 200;
    private static final long MIN_REQUEST_INTERVAL_MS = 1000;
    private static final Pattern SPECIAL_CHARACTERS_PATTERN = Pattern.compile("[.,\\-\\[\\]{}()!@#$%^&*_=+|~`<>?/\";:]");

    private final ObjectMapper objectMapper;
    private final AppSettingService appSettingService;
    private final HttpClient httpClient;
    private final AtomicLong lastRequestTime = new AtomicLong(0);

    public OverDriveParser(ObjectMapper objectMapper, AppSettingService appSettingService, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.appSettingService = appSettingService;
        this.httpClient = httpClient;
    }

    @Override
    public BookMetadata fetchTopMetadata(Book book, FetchMetadataRequest request) {
        List<BookMetadata> results = fetchMetadata(book, request);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public List<BookMetadata> fetchMetadata(Book book, FetchMetadataRequest request) {
        List<String> libraryKeys = getLibraryKeys();
        if (libraryKeys.isEmpty()) {
            log.warn("OverDrive: no library key configured; skipping. Set the library's OverDrive key in metadata provider settings.");
            return List.of();
        }

        // 1. ISBN search
        if (request.getIsbn() != null && !request.getIsbn().isBlank()) {
            List<BookMetadata> byIsbn = search(libraryKeys, ParserUtils.cleanIsbn(request.getIsbn()));
            if (!byIsbn.isEmpty()) {
                return byIsbn;
            }
            log.info("OverDrive: ISBN search returned nothing, falling back to title/author.");
        }

        String title = request.getTitle();
        String author = request.getAuthor();

        // 2. Title + author
        if (title != null && !title.isBlank()) {
            String term = buildTerm(title, author);
            List<BookMetadata> results = search(libraryKeys, term);
            if (!results.isEmpty()) {
                return results;
            }
        }

        // 3. Filename fallback
        String fileName = book.getPrimaryFile() != null ? book.getPrimaryFile().getFileName() : null;
        if ((title == null || title.isBlank()) && fileName != null && !fileName.isBlank()) {
            return search(libraryKeys, buildTerm(BookUtils.cleanFileName(fileName), null));
        }

        return List.of();
    }

    private String buildTerm(String title, String author) {
        String term = SPECIAL_CHARACTERS_PATTERN.matcher(title).replaceAll(" ").trim();
        if (author != null && !author.isBlank()) {
            term = term + " " + author.trim();
        }
        return term;
    }

    /**
     * Search the given library keys and merge the results, deduplicated by OverDrive title id (the same
     * title surfacing from several libraries is returned once, keeping the first library's copy).
     */
    private List<BookMetadata> search(Collection<String> libraryKeys, String query) {
        Map<String, OverDriveApiResponse.Item> byTitleId = new LinkedHashMap<>();
        int noIdCounter = 0;
        for (String libraryKey : libraryKeys) {
            // Include audiobooks so audiobook titles in the library can be matched, not just ebooks.
            for (OverDriveApiResponse.Item item : fetchItems(libraryKey, query, "ebook,audiobook", false, null, MAX_TOTAL_RESULTS)) {
                if (item == null) {
                    continue;
                }
                // Dedupe by title id; items without one can't be deduped, so keep each under a unique key.
                String key = (item.getId() != null && !item.getId().isBlank())
                        ? item.getId()
                        : "noid-" + (noIdCounter++);
                byTitleId.putIfAbsent(key, item);
            }
        }
        return byTitleId.values().stream()
                .map(this::toMetadata)
                .filter(m -> m.getTitle() != null && !m.getTitle().isBlank())
                .toList();
    }

    /**
     * Raw catalog search against a specific library key. Returns an empty list for a blank key or on
     * failure. Used to search across the libraries a user has cards for.
     */
    public List<OverDriveApiResponse.Item> searchLibrary(String libraryKey, String query) {
        return searchLibrary(libraryKey, query, "ebook");
    }

    /**
     * Raw catalog search restricted to the given OverDrive media types (comma-separated, e.g.
     * {@code "ebook"} or {@code "ebook,audiobook"}). The borrow flow widens to audiobooks when an
     * audiobook handler is configured; metadata matching stays ebook-only.
     */
    public List<OverDriveApiResponse.Item> searchLibrary(String libraryKey, String query, String mediaTypes) {
        if (libraryKey == null || libraryKey.isBlank()) {
            return List.of();
        }
        return fetchItems(libraryKey, query, mediaTypes, false, null, MAX_TOTAL_RESULTS);
    }

    /**
     * Raw catalog search that additionally pushes facet filters into the Thunder query so a capped
     * result page is already narrowed server-side (useful when a broad query returns too many hits):
     * {@code availableOnly} maps to {@code showOnlyAvailable=true}, and {@code language} (an ISO code
     * like "en") restricts to that language. Both are optional; pass {@code false}/{@code null} to skip.
     * {@code maxResults} bounds how many items are collected across pages (callers use it to fetch a
     * small first window and grow it on "load more").
     */
    public List<OverDriveApiResponse.Item> searchLibrary(String libraryKey, String query, String mediaTypes,
                                                         boolean availableOnly, String language, int maxResults) {
        if (libraryKey == null || libraryKey.isBlank()) {
            return List.of();
        }
        return fetchItems(libraryKey, query, mediaTypes, availableOnly, language, maxResults);
    }

    /**
     * Resolve a library's display name from its preferred/advantage key via the Thunder library
     * directory ({@code /v2/libraries/{key}}). No auth required. Returns null on any failure.
     */
    public String fetchLibraryName(String libraryKey) {
        if (libraryKey == null || libraryKey.isBlank()) {
            return null;
        }
        try {
            waitForRateLimit();
            URI uri = UriComponentsBuilder.fromUriString(THUNDER_BASE_URL)
                    .pathSegment(libraryKey)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            var node = objectMapper.readTree(response.body());
            var name = node.get("name");
            return name != null && !name.asString().isBlank() ? name.asString() : null;
        } catch (IOException e) {
            log.warn("OverDrive: failed to resolve library name for {}: {}", libraryKey, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Resolve a library's OverDrive {@code websiteId} from its preferred/advantage key via the Thunder
     * library directory. Needed for the authenticated card-link endpoints. Returns null on any failure.
     */
    public String fetchWebsiteId(String libraryKey) {
        if (libraryKey == null || libraryKey.isBlank()) {
            return null;
        }
        try {
            waitForRateLimit();
            URI uri = UriComponentsBuilder.fromUriString(THUNDER_BASE_URL)
                    .pathSegment(libraryKey)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            var node = objectMapper.readTree(response.body());
            var websiteId = node.get("websiteId");
            return websiteId != null && !websiteId.asString().isBlank() ? websiteId.asString() : null;
        } catch (IOException e) {
            log.warn("OverDrive: failed to resolve websiteId for {}: {}", libraryKey, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Fetch the full OverDrive catalog metadata for a single title by its id, via the library-agnostic
     * {@code /v2/media/{titleId}} endpoint (no library key or auth required). Returns the complete
     * {@link BookMetadata} (title, subtitle, authors, description, publisher, series, subjects, …) so an
     * import can overlay every field, or null on any failure.
     */
    public BookMetadata fetchTitleMetadata(String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }
        try {
            waitForRateLimit();
            URI uri = UriComponentsBuilder.fromUriString(THUNDER_MEDIA_URL)
                    .pathSegment(titleId)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OverDrive: media-by-id fetch for {} returned status {}", titleId, response.statusCode());
                return null;
            }
            OverDriveApiResponse.Item item = objectMapper.readValue(response.body(), OverDriveApiResponse.Item.class);
            return item != null ? toMetadata(item) : null;
        } catch (IOException e) {
            log.warn("OverDrive: failed to fetch media metadata for {}: {}", titleId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Fetch full catalog metadata for many titles at once via the library-agnostic
     * {@code /v2/media/bulk?titleIds=…} endpoint (no auth), keyed by title id. Used to enrich a card's
     * loans/holds (which the sync feed carries only sparsely) with narrator, edition and duration in a
     * single call. Returns an empty map on failure/blank input.
     */
    public Map<String, OverDriveApiResponse.Item> fetchMediaBulk(List<String> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) {
            return Map.of();
        }
        try {
            waitForRateLimit();
            URI uri = UriComponentsBuilder.fromUriString(THUNDER_MEDIA_URL)
                    .pathSegment("bulk")
                    .queryParam("titleIds", String.join(",", titleIds))
                    .build()
                    .encode()
                    .toUri();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OverDrive: media/bulk fetch returned status {}", response.statusCode());
                return Map.of();
            }
            OverDriveApiResponse.Item[] items = objectMapper.readValue(response.body(), OverDriveApiResponse.Item[].class);
            Map<String, OverDriveApiResponse.Item> byId = new LinkedHashMap<>();
            if (items != null) {
                for (OverDriveApiResponse.Item item : items) {
                    if (item != null && item.getId() != null) {
                        byId.put(item.getId(), item);
                    }
                }
            }
            return byId;
        } catch (IOException e) {
            log.warn("OverDrive: failed to fetch media/bulk: {}", e.getMessage());
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    /**
     * Fetch a single title's catalog item (including per-library availability) at a specific library via
     * {@code /v2/libraries/{key}/media/{titleId}}. No auth required. Returns null on any failure. Used to
     * check whether a held title is borrowable at another of the user's libraries.
     */
    public OverDriveApiResponse.Item fetchTitleAtLibrary(String libraryKey, String titleId) {
        if (libraryKey == null || libraryKey.isBlank() || titleId == null || titleId.isBlank()) {
            return null;
        }
        try {
            waitForRateLimit();
            URI uri = UriComponentsBuilder.fromUriString(THUNDER_BASE_URL)
                    .pathSegment(libraryKey, "media", titleId)
                    .queryParam("includedFacets", "availability")
                    .queryParam("includeFacets", "false")
                    .build()
                    .encode()
                    .toUri();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OverDrive: title {} at library {} returned status {}", titleId, libraryKey, response.statusCode());
                return null;
            }
            return objectMapper.readValue(response.body(), OverDriveApiResponse.Item.class);
        } catch (IOException e) {
            log.warn("OverDrive: failed to fetch title {} at library {}: {}", titleId, libraryKey, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private List<OverDriveApiResponse.Item> fetchItems(String libraryKey, String query, String mediaTypes,
                                                       boolean availableOnly, String language, int maxResults) {
        int cap = Math.max(1, Math.min(maxResults, MAX_TOTAL_RESULTS));
        // Request only as large a page as we need (Thunder caps a page at 100), so a small first window
        // is a single lightweight call rather than always pulling 100.
        int perPage = Math.min(RESULTS_PER_PAGE, cap);
        List<OverDriveApiResponse.Item> collected = new ArrayList<>();
        int page = 1;
        Integer totalItems = null;
        while (collected.size() < cap) {
            OverDriveApiResponse pageResponse = fetchItemsPage(libraryKey, query, mediaTypes, availableOnly, language, page, perPage);
            if (pageResponse == null || pageResponse.getItems() == null || pageResponse.getItems().isEmpty()) {
                break;
            }
            // Drop null placeholders Thunder can include, so the window count stays accurate and callers
            // never dereference a null item.
            pageResponse.getItems().stream().filter(java.util.Objects::nonNull).forEach(collected::add);
            if (pageResponse.getTotalItems() != null) {
                totalItems = pageResponse.getTotalItems();
            }
            // Stop once we've pulled everything the query has, or when a short page signals the last one.
            if (pageResponse.getItems().size() < perPage
                    || (totalItems != null && collected.size() >= totalItems)) {
                break;
            }
            page++;
        }
        if (collected.size() > cap) {
            collected = collected.subList(0, cap);
        }
        if (totalItems != null && totalItems > collected.size()) {
            log.info("OverDrive search for '{}' at {} returned {} of {} matches (window {}).",
                    query, libraryKey, collected.size(), totalItems, cap);
        }
        return collected;
    }

    private OverDriveApiResponse fetchItemsPage(String libraryKey, String query, String mediaTypes,
                                                boolean availableOnly, String language, int page, int perPage) {
        try {
            waitForRateLimit();

            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(THUNDER_BASE_URL)
                    .pathSegment(libraryKey, "media")
                    .queryParam("query", query)
                    // Media types to return (e.g. "ebook" or "ebook,audiobook"). Ebook-only for metadata
                    // matching; the borrow flow widens to audiobooks when an audiobook handler is configured.
                    .queryParam("mediaTypes", mediaTypes)
                    // Ask Thunder to include per-item availability (isAvailable/isHoldable/holdsCount/
                    // estimatedWaitDays/…) so the borrow UI can offer Borrow vs Place Hold accurately.
                    .queryParam("includedFacets", "availability")
                    // We never read the result-set facet aggregation block, so drop it (~30% smaller payload).
                    .queryParam("includeFacets", "false")
                    .queryParam("perPage", perPage)
                    .queryParam("page", page);
            // Optional server-side facet narrowing (Libby's own params) so a broad query's capped page is
            // already filtered rather than trimmed before the client can filter it.
            if (availableOnly) {
                builder.queryParam("showOnlyAvailable", "true");
            }
            if (language != null && !language.isBlank()) {
                builder.queryParam("language", language.trim());
            }

            URI uri = builder.build().encode().toUri();

            log.info("OverDrive Thunder API URL: {}", uri);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    // Thunder rejects requests without a browser-like UA.
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OverDrive Thunder API request failed. Status: {}", response.statusCode());
                return null;
            }
            return objectMapper.readValue(response.body(), OverDriveApiResponse.class);
        } catch (IOException e) {
            log.error("OverDrive: IO error fetching metadata: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("OverDrive: request interrupted");
            return null;
        }
    }

    /**
     * Batch per-library availability for many titles in one call. Convenience wrapper over
     * {@link #fetchAvailabilityBulk} for a single library.
     */
    public Map<String, OverDriveApiResponse.Item> fetchAvailability(String libraryKey, List<String> titleIds) {
        if (libraryKey == null || libraryKey.isBlank()) {
            return Map.of();
        }
        return fetchAvailabilityBulk(List.of(libraryKey), titleIds).getOrDefault(libraryKey, Map.of());
    }

    /**
     * Per-library availability for many titles, fetched for all libraries <em>concurrently</em> —
     * {@code POST /v2/libraries/{key}/media/availability} with the title ids in the body, mirroring the
     * Libby web client (which fires one such request per library in parallel). No auth required.
     *
     * <p>POST rather than a {@code ?titleIds=…} query string: the id list grows with the user's holds and
     * would otherwise run into URL length limits. Concurrent rather than sequential: the per-request
     * throttle would otherwise add a full second of latency for every extra library, and a handful of
     * parallel requests is exactly the shape of traffic the real client produces. The throttle is applied
     * once for the batch, so successive batches stay spaced apart.
     *
     * @return library key → (title id → availability item). Libraries whose call failed map to an empty
     *         map rather than failing the batch.
     */
    public Map<String, Map<String, OverDriveApiResponse.Item>> fetchAvailabilityBulk(
            Collection<String> libraryKeys, List<String> titleIds) {
        if (libraryKeys == null || libraryKeys.isEmpty() || titleIds == null || titleIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = libraryKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        List<String> ids = titleIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (keys.isEmpty() || ids.isEmpty()) {
            return Map.of();
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("ids", ids));
        } catch (Exception e) {
            log.warn("OverDrive: could not encode availability request body: {}", e.getMessage());
            return Map.of();
        }

        // One throttle wait for the whole fan-out, not one per library.
        waitForRateLimit();

        Map<String, CompletableFuture<HttpResponse<String>>> inFlight = new LinkedHashMap<>();
        for (String libraryKey : keys) {
            URI uri = UriComponentsBuilder.fromUriString(THUNDER_BASE_URL)
                    .pathSegment(libraryKey, "media", "availability")
                    .queryParam("x-client-id", THUNDER_CLIENT_ID)
                    .build()
                    .encode()
                    .toUri();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            inFlight.put(libraryKey, httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()));
        }

        Map<String, Map<String, OverDriveApiResponse.Item>> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<HttpResponse<String>>> entry : inFlight.entrySet()) {
            String libraryKey = entry.getKey();
            try {
                Map<String, OverDriveApiResponse.Item> byId = new LinkedHashMap<>();
                for (OverDriveApiResponse.Item item : parseItems(entry.getValue().join())) {
                    // Thunder can return null placeholders in the items array (e.g. titles not carried at
                    // this library), so guard before dereferencing.
                    if (item != null && item.getId() != null) {
                        byId.put(item.getId(), item);
                    }
                }
                out.put(libraryKey, byId);
            } catch (CompletionException | CancellationException e) {
                log.warn("OverDrive: failed to fetch availability at {}: {}", libraryKey, e.getMessage());
                out.put(libraryKey, Map.of());
            }
        }
        return out;
    }

    private List<OverDriveApiResponse.Item> parseItems(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status != 200) {
            log.warn("OverDrive Thunder API request failed. Status: {}", status);
            return List.of();
        }
        try {
            OverDriveApiResponse parsed = objectMapper.readValue(response.body(), OverDriveApiResponse.class);
            if (parsed == null || parsed.getItems() == null) {
                return List.of();
            }
            // Thunder may include null placeholders in the items array — drop them so callers never NPE.
            return parsed.getItems().stream().filter(java.util.Objects::nonNull).toList();
        } catch (Exception e) {
            log.error("OverDrive: failed to parse response: {}", e.getMessage());
            return List.of();
        }
    }

    private BookMetadata toMetadata(OverDriveApiResponse.Item item) {
        String[] isbns = OverDriveItemExtractor.isbns(item);
        SeriesData series = extractSeries(item);

        return BookMetadata.builder()
                .provider(MetadataProvider.Overdrive)
                .externalUrl(item.getId() != null ? LIBBY_TITLE_URL + item.getId() : null)
                .title(item.getTitle())
                .subtitle(item.getSubtitle())
                .authors(OverDriveItemExtractor.authors(item))
                .description(cleanDescription(item.getFullDescription() != null ? item.getFullDescription() : item.getDescription()))
                .publisher(item.getPublisher() != null ? item.getPublisher().getName() : null)
                .publishedDate(parseDate(item.getPublishDate()))
                .categories(extractNames(item.getSubjects()))
                .tags(extractKeywords(item.getKeywords()))
                .language(OverDriveItemExtractor.languageCode(item))
                .isbn13(isbns[0])
                .isbn10(isbns[1])
                .asin(OverDriveItemExtractor.asin(item))
                .thumbnailUrl(OverDriveItemExtractor.coverHref(item.getCovers()))
                .seriesName(series.name())
                .seriesNumber(series.number())
                .rating(item.getStarRating())
                .build();
    }

    private Set<String> extractNames(List<OverDriveApiResponse.Item.NamedValue> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> names = values.stream()
                .map(OverDriveApiResponse.Item.NamedValue::getName)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return names.isEmpty() ? null : names;
    }

    private Set<String> extractKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        Set<String> result = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return result.isEmpty() ? null : result;
    }

    private record SeriesData(String name, Float number) {}

    private SeriesData extractSeries(OverDriveApiResponse.Item item) {
        if (item.getDetailedSeries() == null || item.getDetailedSeries().getSeriesName() == null) {
            return new SeriesData(null, null);
        }
        Float number = null;
        String order = item.getDetailedSeries().getReadingOrder();
        if (order != null && !order.isBlank()) {
            try {
                number = Float.parseFloat(order.trim());
            } catch (NumberFormatException ignored) {
                // non-numeric reading order (e.g. a range); leave number null
            }
        }
        return new SeriesData(item.getDetailedSeries().getSeriesName(), number);
    }

    private String cleanDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        return Jsoup.parse(description).text().trim();
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception e) {
            log.debug("OverDrive: could not parse date '{}'", value);
            return null;
        }
    }

    /** The distinct, ordered, non-blank OverDrive library keys to search for metadata. */
    private List<String> getLibraryKeys() {
        MetadataProviderSettings settings = appSettingService.getAppSettings().getMetadataProviderSettings();
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

    private void waitForRateLimit() {
        long now = System.currentTimeMillis();
        long sinceLast = now - lastRequestTime.get();
        if (sinceLast < MIN_REQUEST_INTERVAL_MS) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - sinceLast);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestTime.set(System.currentTimeMillis());
    }
}
