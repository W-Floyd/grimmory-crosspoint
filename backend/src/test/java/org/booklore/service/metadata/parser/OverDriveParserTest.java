package org.booklore.service.metadata.parser;

import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.dto.settings.MetadataProviderSettings;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OverDriveParserTest {

    @Mock
    private AppSettingService appSettingService;
    @Mock
    private HttpClient httpClient;

    private OverDriveParser parser;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        settings("lapl");
        parser = new OverDriveParser(new ObjectMapper(), appSettingService, httpClient);
    }

    private void settings(String libraryKey) {
        settings(libraryKey, null);
    }

    private void settings(String libraryKey, List<String> extraKeys) {
        MetadataProviderSettings.Overdrive overdrive = new MetadataProviderSettings.Overdrive();
        overdrive.setEnabled(true);
        List<String> keys = new ArrayList<>();
        if (libraryKey != null) {
            keys.add(libraryKey);
        }
        if (extraKeys != null) {
            keys.addAll(extraKeys);
        }
        overdrive.setLibraryKeys(keys);
        MetadataProviderSettings provider = new MetadataProviderSettings();
        provider.setOverdrive(overdrive);
        when(appSettingService.getAppSettings())
                .thenReturn(AppSettings.builder().metadataProviderSettings(provider).build());
    }

    private void mockResponse(String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Test
    void fetchMetadata_mapsThunderItemToBookMetadata() throws Exception {
        mockResponse("""
                {
                  "totalItems": 1,
                  "items": [{
                    "id": "123",
                    "title": "The Test Book",
                    "subtitle": "A Subtitle",
                    "fullDescription": "<p>A <b>great</b> read.</p>",
                    "creators": [
                      {"name": "Jane Author", "role": "Author"},
                      {"name": "Nate Narrator", "role": "Narrator"}
                    ],
                    "covers": {"cover510Wide": {"href": "https://img/large.jpg"}, "cover150Wide": {"href": "https://img/small.jpg"}},
                    "publisher": {"name": "Test House"},
                    "publishDate": "2021-06-15T00:00:00Z",
                    "subjects": [{"name": "Fiction"}, {"name": "Fantasy"}],
                    "languages": [{"id": "es", "name": "Spanish; Castilian"}],
                    "keywords": ["gamers", "cyberpunk"],
                    "formats": [{"id": "ebook-epub-adobe", "isbn": "9781234567897",
                      "identifiers": [{"type": "ISBN", "value": "9781234567897"}, {"type": "ASIN", "value": "B00ABCDEF0"}]}],
                    "detailedSeries": {"seriesName": "The Series", "readingOrder": "3"},
                    "starRating": 4.5
                  }]
                }
                """);

        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().title("The Test Book").author("Jane Author").build());

        assertThat(results).hasSize(1);
        BookMetadata m = results.getFirst();
        assertThat(m.getProvider()).isEqualTo(MetadataProvider.Overdrive);
        assertThat(m.getTitle()).isEqualTo("The Test Book");
        assertThat(m.getSubtitle()).isEqualTo("A Subtitle");
        assertThat(m.getAuthors()).containsExactly("Jane Author"); // narrator excluded
        assertThat(m.getDescription()).isEqualTo("A great read."); // HTML stripped
        assertThat(m.getPublisher()).isEqualTo("Test House");
        assertThat(m.getPublishedDate()).isEqualTo(java.time.LocalDate.of(2021, 6, 15));
        assertThat(m.getCategories()).containsExactlyInAnyOrder("Fiction", "Fantasy");
        assertThat(m.getIsbn13()).isEqualTo("9781234567897");
        assertThat(m.getAsin()).isEqualTo("B00ABCDEF0");
        assertThat(m.getLanguage()).isEqualTo("es"); // from the language id, not the "Spanish; Castilian" name
        assertThat(m.getTags()).containsExactlyInAnyOrder("gamers", "cyberpunk");
        assertThat(m.getThumbnailUrl()).isEqualTo("https://img/large.jpg"); // prefers largest
        assertThat(m.getSeriesName()).isEqualTo("The Series");
        assertThat(m.getSeriesNumber()).isEqualTo(3f);
        assertThat(m.getRating()).isEqualTo(4.5);
        // A Libby share link (from the title id) so the results UI can build a provider link.
        assertThat(m.getExternalUrl()).isEqualTo("https://share.libbyapp.com/title/123");
    }

    @Test
    void fetchMetadata_picksLargestCoverByWidthIncludingUnhardcodedSizes() throws Exception {
        // A size the old code never knew about (cover1080Wide) plus an explicit width both win at runtime.
        mockResponse("""
                {"items": [{
                  "id": "1", "title": "Book",
                  "covers": {
                    "cover150Wide": {"href": "https://img/150.jpg", "width": 150},
                    "cover510Wide": {"href": "https://img/510.jpg", "width": 510},
                    "cover1080Wide": {"href": "https://img/1080.jpg", "width": 1080}
                  }
                }]}
                """);

        BookMetadata m = parser.fetchMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().title("Book").build()).getFirst();

        assertThat(m.getThumbnailUrl()).isEqualTo("https://img/1080.jpg");
    }

    @Test
    void fetchMetadata_fallsBackToWidthParsedFromKeyWhenAbsent() throws Exception {
        // No explicit width fields → width parsed from the OverDrive key convention (coverNNNWide).
        mockResponse("""
                {"items": [{
                  "id": "1", "title": "Book",
                  "covers": {
                    "cover300Wide": {"href": "https://img/300.jpg"},
                    "cover720Wide": {"href": "https://img/720.jpg"}
                  }
                }]}
                """);

        BookMetadata m = parser.fetchMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().title("Book").build()).getFirst();

        assertThat(m.getThumbnailUrl()).isEqualTo("https://img/720.jpg");
    }

    @Test
    void fetchMetadata_searchesAllConfiguredLibraryKeysAndDedupesByTitleId() throws Exception {
        settings("lapl", List.of("bpl"));
        parser = new OverDriveParser(new ObjectMapper(), appSettingService, httpClient);
        // Both libraries return the same title id → the merged result is deduplicated to one.
        mockResponse("""
                {"items": [{"id": "123", "title": "Dune", "creators": [{"name": "Frank Herbert", "role": "Author"}]}]}
                """);

        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().title("Dune").build());

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getTitle()).isEqualTo("Dune");
        // One query per configured library key (lapl + bpl).
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void fetchMetadata_returnsEmptyWhenNoLibraryKeyConfigured() throws Exception {
        settings("   ");
        parser = new OverDriveParser(new ObjectMapper(), appSettingService, httpClient);

        List<BookMetadata> results = parser.fetchMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().title("Anything").build());

        assertThat(results).isEmpty();
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void fetchTopMetadata_returnsFirstResult() throws Exception {
        mockResponse("""
                {"items": [
                  {"title": "First", "creators": [{"name": "A", "role": "Author"}]},
                  {"title": "Second"}
                ]}
                """);

        BookMetadata top = parser.fetchTopMetadata(Book.builder().build(),
                FetchMetadataRequest.builder().title("First").build());

        assertThat(top).isNotNull();
        assertThat(top.getTitle()).isEqualTo("First");
    }
}
