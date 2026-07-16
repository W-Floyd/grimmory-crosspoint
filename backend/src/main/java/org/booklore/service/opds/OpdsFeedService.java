package org.booklore.service.opds;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.config.security.userdetails.OpdsUserDetails;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookFile;
import org.booklore.model.dto.Library;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.OpdsSortOrder;
import org.booklore.model.enums.ReadStatus;
import org.booklore.model.dto.opds.DevicePreset;
import org.booklore.service.MagicShelfService;
import org.booklore.service.opds.optimization.DevicePresetService;
import org.booklore.service.opds.optimization.OptimizedDownloadService;
import org.booklore.util.ArchiveUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpdsFeedService {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final List<String> PAGINATION_QUERY_WHITELIST = List.of(
            "q", "libraryId", "shelfId", "shelfIds", "magicShelfId", "author", "series", "preset",
            "format", "readStatus"
    );

    private final AuthenticationService authenticationService;
    private final OpdsBookService opdsBookService;
    private final MagicShelfService magicShelfService;
    private final MagicShelfBookService magicShelfBookService;
    private final DevicePresetService devicePresetService;
    private final OptimizedDownloadService optimizedDownloadService;

    /**
     * Resolve the device preset for this request: an explicit {@code ?preset=} wins, otherwise
     * fall back to the authenticated OPDS user's configured default preset. Returns the canonical
     * (configured) id, or {@code null} when none is set/known. Only known presets are propagated
     * into feed links.
     */
    private String resolvePreset(HttpServletRequest request) {
        String requested = request.getParameter("preset");
        if (requested != null && !requested.isBlank()) {
            return devicePresetService.resolveId(requested).orElse(null);
        }
        return defaultUserPreset();
    }

    /** The authenticated OPDS user's default device preset (canonical id), or {@code null}. */
    private String defaultUserPreset() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        if (details == null || details.getOpdsUserV2() == null) {
            return null;
        }
        String preset = details.getOpdsUserV2().getDefaultPreset();
        return (preset == null || preset.isBlank())
                ? null
                : devicePresetService.resolveId(preset).orElse(null);
    }

    /** Append {@code preset=<id>} to a URL using the correct separator, when a preset is set. */
    private String withPreset(String url, String preset) {
        if (preset == null || preset.isBlank()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "preset=" + URLEncoder.encode(preset, StandardCharsets.UTF_8);
    }

    public String generateRootNavigation(HttpServletRequest request) {
        String preset = resolvePreset(request);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:root</id>
                  <title>Booklore Catalog</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        appendRootEntry(feed, "All Books", "urn:booklore:catalog:all",
                withPreset("/api/v1/opds/catalog?page=1&size=" + DEFAULT_PAGE_SIZE, preset),
                "acquisition", "Browse all available books");
        appendRootEntry(feed, "Recently Added", "urn:booklore:catalog:recent",
                withPreset("/api/v1/opds/recent?page=1&size=" + DEFAULT_PAGE_SIZE, preset),
                "acquisition", "Recently added books");
        appendRootEntry(feed, "Continue Reading", "urn:booklore:catalog:continue-reading",
                withPreset("/api/v1/opds/continue-reading?page=1&size=" + DEFAULT_PAGE_SIZE, preset),
                "acquisition", "Books you have started but not finished");
        appendRootEntry(feed, "Libraries", "urn:booklore:navigation:libraries",
                withPreset("/api/v1/opds/libraries", preset), "navigation", "Browse books by library");
        appendRootEntry(feed, "Shelves", "urn:booklore:navigation:shelves",
                withPreset("/api/v1/opds/shelves", preset), "navigation", "Browse your personal shelves");
        appendRootEntry(feed, "Magic Shelves", "urn:booklore:navigation:magic-shelves",
                withPreset("/api/v1/opds/magic-shelves", preset), "navigation", "Browse your smart, dynamic shelves");
        appendRootEntry(feed, "Authors", "urn:booklore:navigation:authors",
                withPreset("/api/v1/opds/authors", preset), "navigation", "Browse books by author");
        appendRootEntry(feed, "Series", "urn:booklore:navigation:series",
                withPreset("/api/v1/opds/series", preset), "navigation", "Browse books by series");
        appendRootEntry(feed, "Surprise Me", "urn:booklore:catalog:surprise",
                withPreset("/api/v1/opds/surprise", preset), "acquisition", "25 random books from the catalog");

        // Device discovery: when presets are configured, offer a device chooser so any OPDS
        // reader can enter a preset-scoped browse. New config presets appear automatically.
        if (devicePresetService.hasPresets()) {
            appendRootEntry(feed, "Devices", "urn:booklore:navigation:devices",
                    "/api/v1/opds/devices", "navigation",
                    "Browse with device-optimized files (e.g. Xteink X3/X4)");
        }

        feed.append("</feed>");
        return feed.toString();
    }

    private void appendRootEntry(StringBuilder feed, String title, String id, String href, String kind, String content) {
        feed.append("""
                  <entry>
                    <title>%s</title>
                    <id>%s</id>
                    <updated>%s</updated>
                    <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=%s"/>
                    <content type="text">%s</content>
                  </entry>
                """.formatted(escapeXml(title), id, now(), escapeXml(href), kind, escapeXml(content)));
    }

    /** Navigation feed listing configured device presets, each linking to a preset-scoped catalog. */
    public String generateDevicesNavigation(HttpServletRequest request) {
        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:devices</id>
                  <title>Devices</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/devices" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        for (var entry : devicePresetService.all().entrySet()) {
            String id = entry.getKey();
            DevicePreset preset = entry.getValue();
            String label = preset.displayName() != null ? preset.displayName() : id;
            String href = withPreset("/api/v1/opds/catalog?page=1&size=" + DEFAULT_PAGE_SIZE, id);
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:device:%s</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">Files optimized for %s (%dx%d)</content>
                      </entry>
                    """.formatted(
                    escapeXml(label),
                    escapeXml(id),
                    now(),
                    escapeXml(href),
                    escapeXml(label),
                    preset.getMaxWidth(),
                    preset.getMaxHeight()
            ));
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateLibrariesNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        String preset = resolvePreset(request);
        List<Library> libraries = opdsBookService.getAccessibleLibraries(userId);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:libraries</id>
                  <title>Libraries</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/libraries" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        for (Library library : libraries) {
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:library:%d</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">%s</content>
                      </entry>
                    """.formatted(
                    escapeXml(library.getName()),
                    library.getId(),
                    now(),
                    escapeXml(withPreset("/api/v1/opds/catalog?libraryId=" + library.getId(), preset)),
                    escapeXml(library.getName() != null ? library.getName() : "Library collection")
            ));
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateShelvesNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        String preset = resolvePreset(request);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:shelves</id>
                  <title>Shelves</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/shelves" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        if (userId != null) {
            var shelves = opdsBookService.getUserShelves(userId);

            if (shelves != null) {
                for (var shelf : shelves) {
                    feed.append("""
                              <entry>
                                <title>%s</title>
                                <id>urn:booklore:shelf:%d</id>
                                <updated>%s</updated>
                                <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                                <content type="text">Personal shelf collection</content>
                              </entry>
                            """.formatted(
                            escapeXml(shelf.getName()),
                            shelf.getId(),
                            now(),
                            escapeXml(withPreset("/api/v1/opds/catalog?shelfId=" + shelf.getId(), preset))
                    ));
                }
            }
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateMagicShelvesNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        String preset = resolvePreset(request);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:magic-shelves</id>
                  <title>Magic Shelves</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/magic-shelves" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        if (userId != null) {
            var magicShelves = magicShelfService.getUserShelvesForOpds(userId);

            if (magicShelves != null) {
                for (var shelf : magicShelves) {
                    feed.append("""
                              <entry>
                                <title>%s</title>
                                <id>urn:booklore:magic-shelf:%d</id>
                                <updated>%s</updated>
                                <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                                <content type="text">Smart, dynamic shelf collection</content>
                              </entry>
                            """.formatted(
                            escapeXml(shelf.getName()),
                            shelf.getId(),
                            now(),
                            escapeXml(withPreset("/api/v1/opds/catalog?magicShelfId=" + shelf.getId(), preset))
                    ));
                }
            }
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateAuthorsNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        String preset = resolvePreset(request);
        List<String> authors = opdsBookService.getDistinctAuthors(userId);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:authors</id>
                  <title>Authors</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/authors" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        for (String author : authors) {
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:author:%s</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">Books by %s</content>
                      </entry>
                    """.formatted(
                    escapeXml(author),
                    escapeXml(author),
                    now(),
                    escapeXml(withPreset("/api/v1/opds/catalog?author=" + URLEncoder.encode(author, StandardCharsets.UTF_8), preset)),
                    escapeXml(author)
            ));
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateSeriesNavigation(HttpServletRequest request) {
        Long userId = getUserId();
        String preset = resolvePreset(request);
        List<String> seriesList = opdsBookService.getDistinctSeries(userId);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
                  <id>urn:booklore:navigation:series</id>
                  <title>Series</title>
                  <updated>%s</updated>
                  <link rel="self" href="/api/v1/opds/series" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now()));

        for (String series : seriesList) {
            feed.append("""
                      <entry>
                        <title>%s</title>
                        <id>urn:booklore:series:%s</id>
                        <updated>%s</updated>
                        <link rel="subsection" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                        <content type="text">Books in the %s series</content>
                      </entry>
                    """.formatted(
                    escapeXml(series),
                    escapeXml(series),
                    now(),
                    escapeXml(withPreset("/api/v1/opds/catalog?series=" + URLEncoder.encode(series, StandardCharsets.UTF_8), preset)),
                    escapeXml(series)
            ));
        }

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateCatalogFeed(HttpServletRequest request) {
        Long libraryId = parseLongParam(request, "libraryId", null);
        Set<Long> shelfIds = parseShelfIds(request);
        Long magicShelfId = parseLongParam(request, "magicShelfId", null);
        String query = request.getParameter("q");
        String author = request.getParameter("author");
        String series = request.getParameter("series");
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        Long userId = getUserId();
        OpdsSortOrder sortOrder = getSortOrder();
        Page<Book> booksPage;

        // Facets apply only to the plain "all books" browse — combining them with a
        // library/shelf/author/series/search scope is intentionally not supported.
        boolean plainCatalog = libraryId == null && (shelfIds == null || shelfIds.isEmpty())
                && magicShelfId == null && (author == null || author.isBlank())
                && (series == null || series.isBlank()) && (query == null || query.isBlank());
        BookFileType formatFacet = plainCatalog ? parseFormat(request.getParameter("format")) : null;
        String readStatusFacet = plainCatalog ? normalizeReadStatusFacet(request.getParameter("readStatus")) : null;

        String feedTitle;
        String feedId;
        if (formatFacet != null) {
            booksPage = opdsBookService.getBooksByFormatPage(userId, formatFacet, page - 1, size);
            feedTitle = formatFacet.name() + " Books";
            feedId = "urn:booklore:catalog:format:" + formatFacet.name();
        } else if (readStatusFacet != null) {
            booksPage = opdsBookService.getBooksByReadStatusPage(userId, readStatusStatuses(readStatusFacet), page - 1, size);
            feedTitle = readStatusFacet.equals("READING") ? "Currently Reading" : "Finished Books";
            feedId = "urn:booklore:catalog:read-status:" + readStatusFacet;
        } else if (magicShelfId != null) {
            booksPage = magicShelfBookService.getBooksByMagicShelfId(userId, magicShelfId, page - 1, size);
            feedTitle = determineFeedTitle(libraryId, shelfIds, magicShelfId, author, series);
            feedId = determineFeedId(libraryId, shelfIds, magicShelfId, author, series);
        } else if (author != null && !author.isBlank()) {
            booksPage = opdsBookService.getBooksByAuthorName(userId, author, page - 1, size);
            feedTitle = determineFeedTitle(libraryId, shelfIds, magicShelfId, author, series);
            feedId = determineFeedId(libraryId, shelfIds, magicShelfId, author, series);
        } else if (series != null && !series.isBlank()) {
            booksPage = opdsBookService.getBooksBySeriesName(userId, series, page - 1, size);
            feedTitle = determineFeedTitle(libraryId, shelfIds, magicShelfId, author, series);
            feedId = determineFeedId(libraryId, shelfIds, magicShelfId, author, series);
        } else {
            booksPage = opdsBookService.getBooksPage(userId, query, libraryId, shelfIds, page - 1, size);
            feedTitle = determineFeedTitle(libraryId, shelfIds, magicShelfId, author, series);
            feedId = determineFeedId(libraryId, shelfIds, magicShelfId, author, series);
        }

        // Apply user's preferred sort order (read-status facet keeps its recency order)
        if (readStatusFacet == null) {
            booksPage = opdsBookService.applySortOrder(booksPage, sortOrder);
        }

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>%s</id>
                  <title>%s</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(
                escapeXml(feedId),
                escapeXml(feedTitle),
                now(),
                booksPage.getTotalElements(),
                ((page - 1) * size) + 1,
                size,
                escapeXml(buildCurrentUrl(request, page, size))
        ));

        appendPaginationLinks(feed, request, page, booksPage.getTotalPages(), size);

        String preset = resolvePreset(request);
        if (plainCatalog) {
            appendCatalogFacets(feed, userId, preset, formatFacet, readStatusFacet);
        }
        booksPage.getContent().forEach(book -> appendBookEntry(feed, book, preset));

        feed.append("</feed>");
        return feed.toString();
    }

    /**
     * Emit OPDS facet links so readers can filter the catalog by format and reading status. Only
     * formats actually present in the user's libraries are offered; the currently applied facet is
     * marked {@code opds:activeFacet}.
     */
    private void appendCatalogFacets(StringBuilder feed, Long userId, String preset,
                                     BookFileType activeFormat, String activeReadStatus) {
        for (BookFileType format : opdsBookService.getAvailableFormats(userId)) {
            String href = withPreset("/api/v1/opds/catalog?format=" + format.name(), preset);
            appendFacet(feed, href, format.name(), "Format", format.equals(activeFormat));
        }
        appendFacet(feed, withPreset("/api/v1/opds/catalog?readStatus=READING", preset),
                "Currently Reading", "Reading Status", "READING".equals(activeReadStatus));
        appendFacet(feed, withPreset("/api/v1/opds/catalog?readStatus=READ", preset),
                "Finished", "Reading Status", "READ".equals(activeReadStatus));
    }

    private void appendFacet(StringBuilder feed, String href, String title, String group, boolean active) {
        feed.append("  <link rel=\"http://opds-spec.org/facet\" href=\"")
                .append(escapeXml(href))
                .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\" title=\"")
                .append(escapeXml(title))
                .append("\" opds:facetGroup=\"").append(escapeXml(group)).append("\"");
        if (active) {
            feed.append(" opds:activeFacet=\"true\"");
        }
        feed.append("/>\n");
    }

    /** Parse a format facet value into a {@link BookFileType}, or {@code null} if blank/unknown. */
    private BookFileType parseFormat(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BookFileType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Normalize a read-status facet value to {@code READING} or {@code READ}, or {@code null}. */
    private String normalizeReadStatusFacet(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.trim().toUpperCase();
        return (upper.equals("READING") || upper.equals("READ")) ? upper : null;
    }

    private Set<ReadStatus> readStatusStatuses(String facet) {
        return "READING".equals(facet)
                ? Set.of(ReadStatus.READING, ReadStatus.RE_READING)
                : Set.of(ReadStatus.READ);
    }

    public String generateRecentFeed(HttpServletRequest request) {
        Long userId = getUserId();
        OpdsSortOrder sortOrder = getSortOrder();
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        Page<Book> booksPage = opdsBookService.getRecentBooksPage(userId, page - 1, size);

        // Apply user's preferred sort order
        booksPage = opdsBookService.applySortOrder(booksPage, sortOrder);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:catalog:recent</id>
                  <title>Recently Added Books</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), booksPage.getTotalElements(), ((page - 1) * size) + 1, size, escapeXml(buildCurrentUrl(request, page, size))));

        appendPaginationLinks(feed, request, page, booksPage.getTotalPages(), size);

        String preset = resolvePreset(request);
        booksPage.getContent().forEach(book -> appendBookEntry(feed, book, preset));

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateContinueReadingFeed(HttpServletRequest request) {
        Long userId = getUserId();
        int page = Math.max(1, parseLongParam(request, "page", 1L).intValue());
        int size = Math.min(parseLongParam(request, "size", (long) DEFAULT_PAGE_SIZE).intValue(), MAX_PAGE_SIZE);

        // Ordered by lastReadTime DESC in the query; the user's sort order is deliberately not
        // applied so "most recently read" stays the meaningful ordering.
        Page<Book> booksPage = opdsBookService.getContinueReadingPage(userId, page - 1, size);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:catalog:continue-reading</id>
                  <title>Continue Reading</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>%d</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="%s" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), booksPage.getTotalElements(), ((page - 1) * size) + 1, size, escapeXml(buildCurrentUrl(request, page, size))));

        appendPaginationLinks(feed, request, page, booksPage.getTotalPages(), size);

        String preset = resolvePreset(request);
        booksPage.getContent().forEach(book -> appendBookEntry(feed, book, preset));

        feed.append("</feed>");
        return feed.toString();
    }

    public String generateSurpriseFeed(HttpServletRequest request) {
        Long userId = getUserId();
        int count = 25;
        List<Book> books = opdsBookService.getRandomBooks(userId, count);

        var feed = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
                  <id>urn:booklore:catalog:surprise</id>
                  <title>Surprise Me</title>
                  <updated>%s</updated>
                  <opensearch:totalResults>%d</opensearch:totalResults>
                  <opensearch:startIndex>1</opensearch:startIndex>
                  <opensearch:itemsPerPage>%d</opensearch:itemsPerPage>
                  <link rel="self" href="/api/v1/opds/surprise" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
                  <link rel="start" href="/api/v1/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
                  <link rel="search" type="application/opensearchdescription+xml" title="Search" href="/api/v1/opds/search.opds"/>
                """.formatted(now(), books.size(), count));

        String preset = resolvePreset(request);
        books.forEach(book -> appendBookEntry(feed, book, preset));

        feed.append("</feed>");
        return feed.toString();
    }

    public String getOpenSearchDescription() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
                  <ShortName>Booklore</ShortName>
                  <Description>Search Booklore catalog</Description>
                  <Url type="application/atom+xml;profile=opds-catalog;kind=acquisition"
                       template="/api/v1/opds/catalog?q={searchTerms}"/>
                </OpenSearchDescription>
                """;
    }

    private void appendPaginationLinks(StringBuilder feed, HttpServletRequest request, int currentPage, int totalPages, int size) {
        if (totalPages > 0) {
            feed.append("  <link rel=\"first\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, 1, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\"/>\n");
        }
        if (currentPage > 1) {
            feed.append("  <link rel=\"previous\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, currentPage - 1, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\"/>\n");
        }
        if (currentPage < totalPages) {
            feed.append("  <link rel=\"next\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, currentPage + 1, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\"/>\n");
        }
        if (totalPages > 0) {
            feed.append("  <link rel=\"last\" href=\"")
                    .append(escapeXml(buildPaginationUrl(request, totalPages, size)))
                    .append("\" type=\"application/atom+xml;profile=opds-catalog;kind=acquisition\"/>\n");
        }
    }

    private String buildPaginationUrl(HttpServletRequest request, int page, int size) {
        String url = request.getRequestURI();
        StringBuilder result = new StringBuilder(url).append("?");

        for (String key : PAGINATION_QUERY_WHITELIST) {
            String value = request.getParameter(key);
            if (value == null || value.isBlank()) {
                continue;
            }

            result.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                    .append("&");
        }

        result.append("page=").append(page).append("&size=").append(size);

        return result.toString();
    }

    private String buildCurrentUrl(HttpServletRequest request, int page, int size) {
        return buildPaginationUrl(request, page, size);
    }

    private void appendBookEntry(StringBuilder feed, Book book, String preset) {
        feed.append("""
                  <entry>
                    <title>%s</title>
                    <id>urn:booklore:book:%d</id>
                    <updated>%s</updated>
                """.formatted(
                escapeXml(book.getMetadata().getTitle()),
                book.getId(),
                book.getAddedOn() != null ? book.getAddedOn() : now()
        ));

        if (book.getMetadata().getAuthors() != null) {
            book.getMetadata().getAuthors().forEach(author ->
                    feed.append("    <author><name>").append(escapeXml(author)).append("</name></author>\n")
            );
        }

        appendMetadata(feed, book);
        appendLinks(feed, book, preset);

        feed.append("  </entry>\n");
    }

    private void appendMetadata(StringBuilder feed, Book book) {
        var meta = book.getMetadata();
        if (meta == null) return;

        if (meta.getPublisher() != null) {
            feed.append("    <dc:publisher>").append(escapeXml(meta.getPublisher())).append("</dc:publisher>\n");
        }
        if (meta.getLanguage() != null) {
            feed.append("    <dc:language>").append(escapeXml(meta.getLanguage())).append("</dc:language>\n");
        }
        if (meta.getCategories() != null) {
            meta.getCategories().forEach(cat ->
                    feed.append("    <category term=\"").append(escapeXml(cat)).append("\"/>\n")
            );
        }
        if (meta.getDescription() != null) {
            feed.append("    <summary>").append(escapeXml(meta.getDescription())).append("</summary>\n");
        }
        if (meta.getIsbn10() != null) {
            feed.append("    <dc:identifier>urn:isbn:").append(escapeXml(meta.getIsbn10())).append("</dc:identifier>\n");
        }
        // Series metadata
        if (meta.getSeriesName() != null) {
            feed.append("    <meta property=\"belongs-to-collection\" id=\"series\">")
                    .append(escapeXml(meta.getSeriesName())).append("</meta>\n");
            if (meta.getSeriesNumber() != null) {
                feed.append("    <meta property=\"group-position\" refines=\"#series\">")
                        .append(meta.getSeriesNumber()).append("</meta>\n");
            }
        }
    }

    private Instant getCoverUpdatedOn(Book book) {
        if (book.getMetadata() != null) {
            if (book.getMetadata().getCoverUpdatedOn() != null) {
                return book.getMetadata().getCoverUpdatedOn();
            }

            if (book.getMetadata().getAudiobookCoverUpdatedOn() != null) {
                return book.getMetadata().getAudiobookCoverUpdatedOn();
            }
        }

        return null;
    }

    private void appendLinks(StringBuilder feed, Book book, String preset) {
        // Add acquisition link for primary file
        if (book.getPrimaryFile() != null) {
            appendAcquisitionLink(feed, book.getId(), book.getPrimaryFile(), preset);
        }

        // Add acquisition links for alternative formats
        if (book.getAlternativeFormats() != null) {
            for (BookFile altFormat : book.getAlternativeFormats()) {
                appendAcquisitionLink(feed, book.getId(), altFormat, preset);
            }
        }

        Instant coverUpdatedOn = getCoverUpdatedOn(book);
        if (coverUpdatedOn != null) {
            String coverUrl = withPreset("/api/v1/opds/" + book.getId() + "/cover?" + coverUpdatedOn, preset);
            feed.append("    <link rel=\"http://opds-spec.org/image\" href=\"")
                    .append(escapeXml(coverUrl)).append("\" type=\"image/jpeg\"/>\n");
            feed.append("    <link rel=\"http://opds-spec.org/image/thumbnail\" href=\"")
                    .append(escapeXml(coverUrl)).append("\" type=\"image/jpeg\"/>\n");
        }
    }

    private void appendAcquisitionLink(StringBuilder feed, Long bookId, BookFile bookFile, String preset) {
        if (bookFile == null || bookFile.getId() == null) return;

        String mimeType = fileMimeType(bookFile);
        String href = withPreset("/api/v1/opds/" + bookId + "/download?fileId=" + bookFile.getId(), preset);
        feed.append("    <link href=\"")
                .append(escapeXml(href))
                .append("\" rel=\"http://opds-spec.org/acquisition\" type=\"")
                .append(mimeType)
                .append("\"");

        // OPDS spec `length` (octets) so readers can show size and pre-allocate downloads.
        acquisitionLength(bookId, bookFile, preset).ifPresent(length ->
                feed.append(" length=\"").append(length).append("\""));

        // Add title attribute to help readers distinguish formats
        if (bookFile.getBookType() != null) {
            feed.append(" title=\"").append(bookFile.getBookType().name()).append("\"");
        }

        feed.append("/>\n");
    }

    /**
     * Byte size to advertise for an acquisition link. For a device preset that optimizes this
     * file (EPUB), the real size is only known once the optimized variant has been cached: if it
     * has, that size is reported; otherwise the variant is warmed in the background and no length
     * is advertised until a later feed load can report the real bytes. Unoptimized links use the
     * stored file size (KB → bytes).
     */
    private OptionalLong acquisitionLength(Long bookId, BookFile bookFile, String preset) {
        if (preset != null && !preset.isBlank() && bookFile.getBookType() == BookFileType.EPUB) {
            DevicePreset devicePreset = devicePresetService.resolve(preset).orElse(null);
            if (devicePreset != null) {
                OptionalLong cached = optimizedDownloadService.cachedVariantSize(bookId, bookFile.getId(), preset);
                if (cached.isPresent()) {
                    return cached;
                }
                // Warm the cache off the request thread; a subsequent feed load advertises the real size.
                try {
                    optimizedDownloadService.prewarm(bookId, bookFile.getId(), devicePreset, preset);
                } catch (RuntimeException e) {
                    log.debug("Could not schedule OPDS prewarm for book {} file {} preset {}: {}",
                            bookId, bookFile.getId(), preset, e.getMessage());
                }
                return OptionalLong.empty();
            }
        }
        Long kb = bookFile.getFileSizeKb();
        return (kb == null || kb <= 0) ? OptionalLong.empty() : OptionalLong.of(kb * 1024L);
    }

    private String determineFeedTitle(Long libraryId, Set<Long> shelfIds, Long magicShelfId, String author, String series) {
        if (magicShelfId != null) {
            return magicShelfBookService.getMagicShelfName(magicShelfId);
        }
        if (shelfIds != null && !shelfIds.isEmpty()) {
            if (shelfIds.size() == 1) {
                return opdsBookService.getShelfName(shelfIds.iterator().next());
            }
            return "Multiple Shelves";
        }
        if (libraryId != null) {
            return opdsBookService.getLibraryName(libraryId);
        }
        if (author != null && !author.isBlank()) {
            return "Books by " + author;
        }
        if (series != null && !series.isBlank()) {
            return series + " series";
        }
        return "Booklore Catalog";
    }

    private String determineFeedId(Long libraryId, Set<Long> shelfIds, Long magicShelfId, String author, String series) {
        if (magicShelfId != null) {
            return "urn:booklore:magic-shelf:" + magicShelfId;
        }
        if (shelfIds != null && !shelfIds.isEmpty()) {
            if (shelfIds.size() == 1) {
                return "urn:booklore:shelf:" + shelfIds.iterator().next();
            }
            return "urn:booklore:shelves:" + String.join(",", shelfIds.stream().map(String::valueOf).sorted().toList());
        }
        if (libraryId != null) {
            return "urn:booklore:library:" + libraryId;
        }
        if (author != null && !author.isBlank()) {
            return "urn:booklore:author:" + author;
        }
        if (series != null && !series.isBlank()) {
            return "urn:booklore:series:" + series;
        }
        return "urn:booklore:catalog";
    }

    private String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private boolean hasValidFilePath(BookFile bookFile) {
        return bookFile != null
                && bookFile.getFileName() != null
                && bookFile.getFilePath() != null;
    }

    private String fileMimeType(BookFile bookFile) {
        if (bookFile == null || bookFile.getBookType() == null) {
            return "application/octet-stream";
        }
        return switch (bookFile.getBookType()) {
            case PDF -> "application/pdf";
            case EPUB -> "application/epub+zip";
            case FB2 -> {
                if (hasValidFilePath(bookFile)) {
                    ArchiveUtils.ArchiveType type = ArchiveUtils.detectArchiveType(new File(bookFile.getFilePath()));
                    if (type == ArchiveUtils.ArchiveType.ZIP) {
                        yield "application/zip";
                    }
                }
                yield "application/x-fictionbook+xml";
            }
            case MOBI -> "application/x-mobipocket-ebook";
            case AZW3 -> "application/vnd.amazon.ebook";
            case CBX -> {
                if (bookFile.getArchiveType() != null) {
                    if (bookFile.getArchiveType() == ArchiveUtils.ArchiveType.RAR) {
                        yield "application/vnd.comicbook-rar";
                    }
                    if (bookFile.getArchiveType() == ArchiveUtils.ArchiveType.ZIP) {
                        yield "application/vnd.comicbook+zip";
                    }
                    if (bookFile.getArchiveType() == ArchiveUtils.ArchiveType.SEVEN_ZIP) {
                        yield "application/x-7z-compressed";
                    }
                }

                if (hasValidFilePath(bookFile)) {
                    ArchiveUtils.ArchiveType type = ArchiveUtils.detectArchiveType(new File(bookFile.getFilePath()));
                    if (type != ArchiveUtils.ArchiveType.UNKNOWN) {
                        yield switch (type) {
                            case RAR -> "application/vnd.comicbook-rar";
                            case ZIP -> "application/vnd.comicbook+zip";
                            case SEVEN_ZIP -> "application/x-7z-compressed";
                            default -> "application/vnd.comicbook+zip";
                        };
                    }
                }
                yield "application/vnd.comicbook+zip";
            }
            case AUDIOBOOK -> {
                String lower = bookFile.getFileName().toLowerCase();
                if (lower.endsWith(".mp3")) yield "audio/mpeg";
                if (lower.endsWith(".opus")) yield "audio/opus";
                yield "audio/mp4";
            }
        };
    }

    private String escapeXml(String input) {
        return input == null ? "" : StringEscapeUtils.escapeXml10(input);
    }

    private Long parseLongParam(HttpServletRequest request, String name, Long defaultValue) {
        try {
            String v = request.getParameter(name);
            if (v == null || v.isBlank()) return defaultValue;
            return Long.parseLong(v);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private Set<Long> parseShelfIds(HttpServletRequest request) {
        String shelfIdParam = request.getParameter("shelfId");
        String shelfIdsParam = request.getParameter("shelfIds");

        Set<Long> shelfIds = new HashSet<>();

        // Support both single shelfId and comma-separated shelfIds
        if (shelfIdParam != null && !shelfIdParam.isBlank()) {
            try {
                shelfIds.add(Long.parseLong(shelfIdParam));
            } catch (NumberFormatException e) {
                log.warn("Invalid shelfId parameter: {}", shelfIdParam);
            }
        }

        if (shelfIdsParam != null && !shelfIdsParam.isBlank()) {
            for (String id : shelfIdsParam.split(",")) {
                try {
                    shelfIds.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException e) {
                    log.warn("Invalid shelf ID in shelfIds parameter: {}", id);
                }
            }
        }

        return shelfIds.isEmpty() ? null : shelfIds;
    }

    private Long getUserId() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        if (details == null || details.getOpdsUserV2() == null) {
            throw ApiError.FORBIDDEN.createException("OPDS authentication required");
        }
        return details.getOpdsUserV2().getUserId();
    }

    private OpdsSortOrder getSortOrder() {
        OpdsUserDetails details = authenticationService.getOpdsUser();
        return details != null && details.getOpdsUserV2() != null && details.getOpdsUserV2().getSortOrder() != null
                ? details.getOpdsUserV2().getSortOrder()
                : OpdsSortOrder.RECENT;
    }
}
