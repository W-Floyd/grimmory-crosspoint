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
import org.booklore.util.LanguageNormalizer;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private static final int MAX_RESULTS = 20;
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
        String libraryKey = getLibraryKey();
        if (libraryKey == null || libraryKey.isBlank()) {
            log.warn("OverDrive: no library key configured; skipping. Set the library's OverDrive key in metadata provider settings.");
            return List.of();
        }

        // 1. ISBN search
        if (request.getIsbn() != null && !request.getIsbn().isBlank()) {
            List<BookMetadata> byIsbn = search(libraryKey, ParserUtils.cleanIsbn(request.getIsbn()));
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
            List<BookMetadata> results = search(libraryKey, term);
            if (!results.isEmpty()) {
                return results;
            }
        }

        // 3. Filename fallback
        String fileName = book.getPrimaryFile() != null ? book.getPrimaryFile().getFileName() : null;
        if ((title == null || title.isBlank()) && fileName != null && !fileName.isBlank()) {
            return search(libraryKey, buildTerm(BookUtils.cleanFileName(fileName), null));
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

    private List<BookMetadata> search(String libraryKey, String query) {
        return fetchItems(libraryKey, query).stream()
                .map(this::toMetadata)
                .filter(m -> m.getTitle() != null && !m.getTitle().isBlank())
                .toList();
    }

    /**
     * Raw catalog search returning the underlying Thunder items (which carry the OverDrive title id and
     * per-format ids needed to borrow), for callers that need more than {@link BookMetadata} exposes.
     * Returns an empty list when no library key is configured or the request fails.
     */
    public List<OverDriveApiResponse.Item> searchCatalog(String query) {
        String libraryKey = getLibraryKey();
        if (libraryKey == null || libraryKey.isBlank()) {
            log.warn("OverDrive: no library key configured; skipping catalog search.");
            return List.of();
        }
        return fetchItems(libraryKey, query);
    }

    /**
     * Raw catalog search against a specific library key. Returns an empty list for a blank key or on
     * failure. Used to search across the libraries a user has cards for.
     */
    public List<OverDriveApiResponse.Item> searchLibrary(String libraryKey, String query) {
        if (libraryKey == null || libraryKey.isBlank()) {
            return List.of();
        }
        return fetchItems(libraryKey, query);
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

    private List<OverDriveApiResponse.Item> fetchItems(String libraryKey, String query) {
        try {
            waitForRateLimit();

            URI uri = UriComponentsBuilder.fromUriString(THUNDER_BASE_URL)
                    .pathSegment(libraryKey, "media")
                    .queryParam("query", query)
                    // Restrict to ebooks so we return book editions, not audiobook/magazine ones.
                    .queryParam("mediaTypes", "ebook")
                    // Ask Thunder to include per-item availability (isAvailable/isHoldable/holdsCount/
                    // estimatedWaitDays/…) so the borrow UI can offer Borrow vs Place Hold accurately.
                    .queryParam("includedFacets", "availability")
                    .queryParam("perPage", MAX_RESULTS)
                    .queryParam("page", 1)
                    .build()
                    .encode()
                    .toUri();

            log.info("OverDrive Thunder API URL: {}", uri);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(uri)
                    // Thunder rejects requests without a browser-like UA.
                    .header("User-Agent", "Mozilla/5.0 (compatible; Grimmory)")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return parseItems(response);
        } catch (IOException e) {
            log.error("OverDrive: IO error fetching metadata: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("OverDrive: request interrupted");
            return List.of();
        }
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
            return parsed.getItems();
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
                .language(item.getLanguages() != null && !item.getLanguages().isEmpty()
                        ? LanguageNormalizer.normalize(item.getLanguages().getFirst().getName()) : null)
                .isbn13(isbns[0])
                .isbn10(isbns[1])
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

    private String getLibraryKey() {
        MetadataProviderSettings settings = appSettingService.getAppSettings().getMetadataProviderSettings();
        if (settings == null || settings.getOverdrive() == null) {
            return null;
        }
        return settings.getOverdrive().getLibraryKey();
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
