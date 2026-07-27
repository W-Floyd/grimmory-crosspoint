package org.booklore.service.overdrive;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.entity.OverDriveTokenEntity;
import org.booklore.repository.OverDriveLoanRepository;
import org.booklore.repository.OverDriveTokenRepository;
import org.booklore.service.acsm.AcsmHandler;
import org.booklore.service.metadata.parser.OverDriveParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverDriveServiceTest {

    @Mock private OverDriveLoanRepository loanRepository;
    @Mock private org.booklore.repository.BookRepository bookRepository;
    @Mock private AcsmHandler acsmHandler;
    @Mock private org.booklore.service.audiobook.AudiobookHandler audiobookHandler;
    @Mock private org.booklore.service.magazine.MagazineHandler magazineHandler;
    @Mock private RestClient restClient;
    @Mock private OverDriveImportService overDriveImportService;
    @Mock private OverDriveParser overDriveParser;
    @Mock private OverDriveTokenRepository tokenRepository;
    @Mock private org.booklore.repository.OverDriveCardShareRepository cardShareRepository;
    @Mock private org.booklore.repository.OverDriveAuditRepository auditRepository;
    @Mock private org.booklore.repository.OverDriveImportDestinationRepository importDestinationRepository;
    @Mock private org.booklore.repository.UserRepository userRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private org.booklore.service.appsettings.AppSettingService appSettingService;
    @Mock private org.booklore.service.NotificationService notificationService;
    @Mock private org.booklore.service.book.BookFileAttachmentService bookFileAttachmentService;

    private OverDriveService service;

    @BeforeEach
    void setUp() {
        // Credential cipher with no key configured -> disabled (token-only), matching default deploys.
        OverDriveCredentialCipher cipher = new OverDriveCredentialCipher("");
        service = new OverDriveService(loanRepository, bookRepository, acsmHandler, audiobookHandler, magazineHandler,
                restClient, overDriveImportService, overDriveParser, tokenRepository, cardShareRepository, auditRepository,
                importDestinationRepository, userRepository, authenticationService, appSettingService, cipher,
                notificationService, bookFileAttachmentService);
    }

    private void authAs(long userId) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(userId).build());
    }

    private void authAsAdmin(long userId) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setAdmin(true);
        when(authenticationService.getAuthenticatedUser())
                .thenReturn(BookLoreUser.builder().id(userId).permissions(perms).build());
    }

    @Test
    void storeToken_persistsCardForCurrentUser() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.empty());

        service.storeToken("card-1", "LAPL", "lapl", "token-xyz");

        ArgumentCaptor<OverDriveTokenEntity> captor = ArgumentCaptor.forClass(OverDriveTokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getIdentity()).isEqualTo("card-1");
        assertThat(captor.getValue().getCardName()).isEqualTo("LAPL");
        assertThat(captor.getValue().getLibraryKey()).isEqualTo("lapl");
        assertThat(captor.getValue().getToken()).isEqualTo("token-xyz");
    }

    @Test
    void hasToken_reflectsCurrentUserCard() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").token("t").build()));
        assertThat(service.hasToken("card-1")).isTrue();
    }

    @Test
    void listCards_returnsCurrentUsersLinkedCards() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").cardName("LAPL").token("t").build(),
                OverDriveTokenEntity.builder().userId(7L).identity("card-2").cardName("BPL").token("t").build()));
        assertThat(service.listCards()).extracting(c -> c.cardId()).containsExactly("card-1", "card-2");
        assertThat(service.listIdentities()).containsExactly("card-1", "card-2");
    }

    @Test
    void removeToken_removesCurrentUsersCard() {
        authAs(7L);
        service.removeToken("card-1");
        verify(tokenRepository).deleteByUserIdAndIdentity(7L, "card-1");
    }

    @Test
    void getStoredToken_byUserAndCard() {
        when(tokenRepository.findByUserIdAndIdentity(9L, "card-9"))
                .thenReturn(Optional.of(OverDriveTokenEntity.builder().userId(9L).identity("card-9").token("tok").build()));
        assertThat(service.getStoredToken(9L, "card-9")).isEqualTo("tok");
        assertThat(service.getStoredToken(null, "card-9")).isNull();
        assertThat(service.getStoredToken(9L, null)).isNull();
    }

    @Test
    void searchCatalog_mapsItemPreferringEpubFormatAndAuthorRole() {
        org.booklore.model.dto.response.OverDriveApiResponse.Item item =
                new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");

        var narrator = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Creator();
        narrator.setName("Some Narrator");
        narrator.setRole("Narrator");
        var author = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Creator();
        author.setName("Frank Herbert");
        author.setRole("Author");
        item.setCreators(List.of(narrator, author));

        var pdf = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Format();
        pdf.setId("ebook-pdf");
        var epub = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Format();
        epub.setId("ebook-epub-adobe");
        epub.setIsbn("9780441013593");
        item.setFormats(List.of(pdf, epub));

        var lang = new org.booklore.model.dto.response.OverDriveApiResponse.Item.NamedValue();
        lang.setName("English");
        item.setLanguages(List.of(lang));

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var results = service.searchCatalog("dune", List.of("card-1"));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().titleId()).isEqualTo("title-1");
        assertThat(results.getFirst().formatId()).isEqualTo("ebook-epub-adobe");
        assertThat(results.getFirst().author()).isEqualTo("Frank Herbert");
        assertThat(results.getFirst().isbn()).isEqualTo("9780441013593");
        assertThat(results.getFirst().language()).isEqualTo("en"); // normalized from "English"
    }

    @Test
    void resolveLibrary_validKeyReturnsName() {
        when(overDriveParser.fetchLibraryName("lapl")).thenReturn("Los Angeles Public Library");

        var res = service.resolveLibrary("  lapl  ");

        assertThat(res.valid()).isTrue();
        assertThat(res.libraryKey()).isEqualTo("lapl");
        assertThat(res.name()).isEqualTo("Los Angeles Public Library");
    }

    @Test
    void resolveLibrary_unknownKeyIsInvalid() {
        when(overDriveParser.fetchLibraryName("nope")).thenReturn(null);

        var res = service.resolveLibrary("nope");

        assertThat(res.valid()).isFalse();
        assertThat(res.name()).isNull();
    }

    @Test
    void resolveLibrary_blankKeyIsInvalidWithoutLookup() {
        var res = service.resolveLibrary("   ");

        assertThat(res.valid()).isFalse();
        verifyNoInteractions(overDriveParser);
    }

    @Test
    void searchCatalog_mapsAvailabilityFields() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");
        item.setAvailable(true);
        item.setHoldable(true);
        item.setAvailableCopies(2);
        item.setOwnedCopies(5);
        item.setHoldsCount(3);
        item.setEstimatedWaitDays(14);
        item.setPreRelease(false);

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var result = service.searchCatalog("dune", List.of("card-1")).getFirst();

        assertThat(result.available()).isTrue();
        assertThat(result.holdable()).isTrue();
        assertThat(result.availableCopies()).isEqualTo(2);
        assertThat(result.ownedCopies()).isEqualTo(5);
        assertThat(result.holdsCount()).isEqualTo(3);
        assertThat(result.estimatedWaitDays()).isEqualTo(14);
        assertThat(result.preRelease()).isFalse();
    }

    @Test
    void searchCatalog_carriesLuckyDayCopies() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");
        item.setAvailable(false); // no regular copies free…
        item.setHoldable(true);
        item.setLuckyDayAvailableCopies(2); // …but two skip-the-line copies

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var result = service.searchCatalog("dune", List.of("card-1")).getFirst();

        assertThat(result.available()).isFalse(); // regular availability unchanged
        assertThat(result.luckyDayAvailableCopies()).isEqualTo(2);
        assertThat(result.availability()).singleElement()
                .satisfies(a -> assertThat(a.luckyDayAvailableCopies()).isEqualTo(2));
    }

    @Test
    void searchCatalog_pushesServerSideFacetsIntoTheQuery() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "audiobook", true, "en", 60)).thenReturn(List.of(item));

        var results = service.searchCatalog("dune", List.of("card-1"), "audiobook", true, "en", 60);

        assertThat(results).hasSize(1);
        verify(overDriveParser).searchLibrary("lapl", "dune", "audiobook", true, "en", 60);
    }

    @Test
    void searchCatalog_narrowsMediaTypesForFormatFilter() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        // Format-only narrowing passes the restricted media types through to the search call.
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook", false, null, 60)).thenReturn(List.of(item));

        var results = service.searchCatalog("dune", List.of("card-1"), "ebook", false, null, 60);

        assertThat(results).hasSize(1);
        verify(overDriveParser).searchLibrary("lapl", "dune", "ebook", false, null, 60);
    }

    @Test
    void searchCatalog_capsResultsToTheRequestedLimit() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        java.util.List<org.booklore.model.dto.response.OverDriveApiResponse.Item> many = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var it = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
            it.setId("t" + i);
            it.setTitle("Dune " + i);
            many.add(it);
        }
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 2)).thenReturn(many);

        var results = service.searchCatalog("dune", List.of("card-1"), null, false, null, 2);

        assertThat(results).hasSize(2); // merged set truncated to the requested window
    }

    @Test
    void searchCatalog_surfacesEditionAndNarratorForAudiobook() {
        var item = audiobookItem("title-1", "Abridged");
        var narrator = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Creator();
        narrator.setName("Andy Serkis");
        narrator.setRole("Narrator");
        item.setCreators(List.of(narrator));

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var result = service.searchCatalog("dune", List.of("card-1")).getFirst();
        assertThat(result.edition()).isEqualTo("Abridged"); // raw edition surfaced as-is; FE decides "abridged"
        assertThat(result.audiobook()).isTrue();
        assertThat(result.narrator()).isEqualTo("Andy Serkis");
    }

    @Test
    void searchCatalog_leavesEditionNullWhenAbsent() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");
        var ebook = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Format();
        ebook.setId("ebook-epub-adobe");
        item.setFormats(List.of(ebook));

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var result = service.searchCatalog("dune", List.of("card-1")).getFirst();
        assertThat(result.edition()).isNull();
        assertThat(result.audiobook()).isFalse();
        assertThat(result.narrator()).isNull();
    }

    /** An audiobook-format catalog item with the given edition label. */
    private static org.booklore.model.dto.response.OverDriveApiResponse.Item audiobookItem(String id, String edition) {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId(id);
        item.setTitle("Dune");
        item.setEdition(edition);
        var audio = new org.booklore.model.dto.response.OverDriveApiResponse.Item.Format();
        audio.setId("audiobook-mp3");
        item.setFormats(List.of(audio));
        return item;
    }

    @Test
    void searchCatalog_defaultsAvailabilityToSafeValuesWhenAbsent() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune"); // no availability fields set → all null

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var result = service.searchCatalog("dune", List.of("card-1")).getFirst();

        assertThat(result.available()).isFalse();
        assertThat(result.holdable()).isFalse();
        assertThat(result.preRelease()).isFalse();
        assertThat(result.availableCopies()).isNull();
    }

    @Test
    void searchCatalog_dedupePrefersAvailableCopyAcrossLibraries() {
        var unavailable = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        unavailable.setId("title-1");
        unavailable.setTitle("Dune");
        unavailable.setAvailable(false);
        unavailable.setHoldable(true);
        var available = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        available.setId("title-1"); // same title id, but borrowable in this library
        available.setTitle("Dune");
        available.setAvailable(true);

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "c1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c1").libraryKey("lapl").token("t").build()));
        when(tokenRepository.findByUserIdAndIdentity(7L, "c2")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c2").libraryKey("bpl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(unavailable));
        when(overDriveParser.searchLibrary("bpl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(available));

        var results = service.searchCatalog("dune", List.of("c1", "c2"));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().available()).isTrue();
    }

    @Test
    void searchCatalog_mergesCardLibrariesDedupedByTitle() {
        var itemA = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        itemA.setId("title-1");
        itemA.setTitle("Dune");
        var itemDup = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        itemDup.setId("title-1"); // same title id from a second library → deduped
        itemDup.setTitle("Dune");
        var itemB = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        itemB.setId("title-2");
        itemB.setTitle("Dune Messiah");

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "c1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c1").libraryKey("lapl").token("t").build()));
        when(tokenRepository.findByUserIdAndIdentity(7L, "c2")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c2").libraryKey("bpl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(itemA));
        when(overDriveParser.searchLibrary("bpl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(itemDup, itemB));

        var results = service.searchCatalog("dune", List.of("c1", "c2"));

        assertThat(results).extracting(c -> c.titleId()).containsExactly("title-1", "title-2");
    }

    @Test
    void searchCatalog_withoutCards_returnsEmptyAndDoesNotSearch() {
        assertThat(service.searchCatalog("dune", null)).isEmpty();
        assertThat(service.searchCatalog("dune", List.of())).isEmpty();
        verifyNoInteractions(overDriveParser);
    }

    @Test
    void extractTitleId_detectsIdsAndUrlsButNotIsbns() {
        assertThat(OverDriveService.extractTitleId("618973")).isEqualTo("618973");
        assertThat(OverDriveService.extractTitleId("  5183634 ")).isEqualTo("5183634");
        assertThat(OverDriveService.extractTitleId("https://share.libbyapp.com/title/618973")).isEqualTo("618973");
        assertThat(OverDriveService.extractTitleId("https://thunder.api.overdrive.com/v2/media/618973")).isEqualTo("618973");
        // Libby detail URL: last numeric path segment is the title id, not the series id (531761).
        assertThat(OverDriveService.extractTitleId(
                "https://libbyapp.com/library/kclibrary/series-531761/scope-deep/books/language-en/fulfillable-ebook-epub-open/page-1/786873"))
                .isEqualTo("786873");
        assertThat(OverDriveService.extractTitleId(
                "https://libbyapp.com/library/kclibrary/everything/page-1/618973")).isEqualTo("618973");
        assertThat(OverDriveService.extractTitleId("9780441013593")).isNull(); // ISBN-13
        assertThat(OverDriveService.extractTitleId("0441013597")).isNull();    // ISBN-10
        assertThat(OverDriveService.extractTitleId("Dune")).isNull();
        assertThat(OverDriveService.extractTitleId(null)).isNull();
    }

    @Test
    void searchCatalog_byTitleId_looksTitleUpDirectlyAtEachLibrary() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("618973");
        item.setTitle("Dune");
        item.setAvailable(true);

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.fetchTitleAtLibrary("lapl", "618973")).thenReturn(item);

        var results = service.searchCatalog("618973", List.of("card-1"));

        assertThat(results).singleElement().satisfies(r -> assertThat(r.titleId()).isEqualTo("618973"));
        // Direct id lookup, not a text search.
        verify(overDriveParser).fetchTitleAtLibrary("lapl", "618973");
        verify(overDriveParser, never()).searchLibrary(any(), any(), any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void searchCatalog_scopedToSelectedCards_searchesOnlyThoseLibraries() {
        var item = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        item.setId("title-1");
        item.setTitle("Dune");

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "c1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c1").libraryKey("lapl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(item));

        var results = service.searchCatalog("dune", List.of("c1"));

        assertThat(results).extracting(c -> c.titleId()).containsExactly("title-1");
        // Only the selected card's library was searched: no admin-key path, no other cards.
        verify(overDriveParser).searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60);
        verifyNoInteractions(appSettingService);
    }

    @Test
    void searchCatalog_scopedToSelectedCards_ignoresForeignOrUnknownCardIds() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "ghost")).thenReturn(Optional.empty());

        var results = service.searchCatalog("dune", List.of("ghost"));

        assertThat(results).isEmpty();
        verifyNoInteractions(overDriveParser);
    }

    @Test
    void searchCatalog_mergesPerLibraryAvailabilityAcrossSelectedCards() {
        var holdableOnly = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        holdableOnly.setId("title-1");
        holdableOnly.setTitle("Dune");
        holdableOnly.setAvailable(false);
        holdableOnly.setHoldable(true);
        var availableHere = new org.booklore.model.dto.response.OverDriveApiResponse.Item();
        availableHere.setId("title-1"); // same title, borrowable in the other library
        availableHere.setTitle("Dune");
        availableHere.setAvailable(true);
        availableHere.setHoldable(false);

        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "c1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c1").libraryKey("lapl").token("t").build()));
        when(tokenRepository.findByUserIdAndIdentity(7L, "c2")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("c2").libraryKey("bpl").token("t").build()));
        when(overDriveParser.searchLibrary("lapl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(holdableOnly));
        when(overDriveParser.searchLibrary("bpl", "dune", "ebook,audiobook,magazine", false, null, 60)).thenReturn(List.of(availableHere));

        var result = service.searchCatalog("dune", List.of("c1", "c2")).getFirst();

        // Aggregate: available at some library, holdable at some library.
        assertThat(result.available()).isTrue();
        assertThat(result.holdable()).isTrue();
        // Per-library breakdown keeps both entries with their own flags.
        assertThat(result.availability()).hasSize(2);
        assertThat(result.availability()).anySatisfy(a -> {
            assertThat(a.libraryKey()).isEqualTo("lapl");
            assertThat(a.available()).isFalse();
            assertThat(a.holdable()).isTrue();
        });
        assertThat(result.availability()).anySatisfy(a -> {
            assertThat(a.libraryKey()).isEqualTo("bpl");
            assertThat(a.available()).isTrue();
            assertThat(a.holdable()).isFalse();
        });
    }

    @Test
    void selectFormat_defaultPrefersOpenEpubThenAdobeEpubThenPdf() {
        var pref = OverDriveService.defaultFormatPreference();
        // Handler ready: Adobe EPUB beats open PDF per the default order.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-epub-adobe", "ebook-pdf-open"), pref, true)).isEqualTo("ebook-epub-adobe");
        // Open EPUB always wins when present.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-pdf-adobe", "ebook-epub-open"), pref, true)).isEqualTo("ebook-epub-open");
        // PDF-only open title.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-pdf-open"), pref, false)).isEqualTo("ebook-pdf-open");
    }

    @Test
    void selectFormat_skipsAdobeWhenNoHandlerAndFallsBackToOpen() {
        var pref = OverDriveService.defaultFormatPreference();
        // Adobe EPUB offered but no handler → skip to the open PDF.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-epub-adobe", "ebook-pdf-open"), pref, false)).isEqualTo("ebook-pdf-open");
        // Only Adobe formats and no handler → nothing fulfillable.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-epub-adobe", "ebook-pdf-adobe"), pref, false)).isNull();
    }

    @Test
    void selectFormat_honorsCustomPreferenceOrder() {
        // Custom order preferring PDF over EPUB.
        var pref = List.of("ebook-pdf-open", "ebook-epub-open");
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-epub-open", "ebook-pdf-open"), pref, true)).isEqualTo("ebook-pdf-open");
    }

    @Test
    void borrowAndImport_failsWithoutImportingWhenBorrowFails() {
        // Stored token for the current user's card → borrow proceeds to the (mocked) RestClient and
        // fails; nothing is imported.
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card").token("t").build()));
        assertThatThrownBy(() -> service.borrowAndImport("card", "title", 1L, 1L, "t", "a", null, null, null, null))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(overDriveImportService);
    }

    @Test
    void borrowAndImport_failsWhenNoTokenAvailable() {
        // No stored token for the current user's card → resolveToken throws, nothing imported.
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.borrowAndImport("card", "title", 1L, 1L, "t", "a", null, null, null, null))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(overDriveImportService);
    }

    // ── Card sharing ─────────────────────────────────────────────────────

    @Test
    void listCards_includesSharedCardsAsNotOwnedWithOwnerName() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().id(1L).userId(7L).identity("mine").cardName("LAPL").token("t").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of(
                org.booklore.model.entity.OverDriveCardShareEntity.builder().tokenId(2L).sharedWithUserId(7L).build()));
        when(tokenRepository.findById(2L)).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(2L).userId(3L).identity("shared").cardName("BPL").token("t").build()));
        when(userRepository.findById(3L)).thenReturn(Optional.of(
                org.booklore.model.entity.BookLoreUserEntity.builder().id(3L).username("alice").name("Alice A").build()));

        var cards = service.listCards();

        assertThat(cards).extracting(c -> c.cardId()).containsExactly("mine", "shared");
        assertThat(cards.get(0).owned()).isTrue();
        assertThat(cards.get(1).owned()).isFalse();
        assertThat(cards.get(1).ownerName()).isEqualTo("Alice A");
    }

    @Test
    void getStoredToken_resolvesSharedOwnersToken() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "shared")).thenReturn(Optional.empty());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of(
                org.booklore.model.entity.OverDriveCardShareEntity.builder().tokenId(2L).sharedWithUserId(7L).build()));
        when(tokenRepository.findById(2L)).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(2L).userId(3L).identity("shared").token("owner-token").build()));

        assertThat(service.getStoredToken("shared")).isEqualTo("owner-token");
    }

    @Test
    void setShares_addsAndRevokesToMatchDesiredSet() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "mine")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(5L).userId(7L).identity("mine").token("t").build()));
        var existing = org.booklore.model.entity.OverDriveCardShareEntity.builder()
                .id(99L).tokenId(5L).sharedWithUserId(2L).build();
        when(cardShareRepository.findByTokenId(5L)).thenReturn(List.of(existing));
        when(userRepository.existsById(3L)).thenReturn(true);

        service.setShares("mine", List.of(3L));

        verify(cardShareRepository).delete(existing); // user 2 revoked
        ArgumentCaptor<org.booklore.model.entity.OverDriveCardShareEntity> saved =
                ArgumentCaptor.forClass(org.booklore.model.entity.OverDriveCardShareEntity.class);
        verify(cardShareRepository).save(saved.capture()); // user 3 added
        assertThat(saved.getValue().getTokenId()).isEqualTo(5L);
        assertThat(saved.getValue().getSharedWithUserId()).isEqualTo(3L);
    }

    @Test
    void setShares_byNonOwnerNonAdmin_isForbidden() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "notmine")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setShares("notmine", List.of(3L)))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(cardShareRepository, never()).save(any());
    }

    @Test
    void setShares_byAdminForAnotherUsersCard_isAllowed() {
        authAsAdmin(1L);
        when(tokenRepository.findByUserIdAndIdentity(1L, "someones")).thenReturn(Optional.empty());
        when(tokenRepository.findByIdentity("someones")).thenReturn(List.of(
                OverDriveTokenEntity.builder().id(8L).userId(4L).identity("someones").token("t").build()));
        when(cardShareRepository.findByTokenId(8L)).thenReturn(List.of());
        when(userRepository.existsById(9L)).thenReturn(true);

        service.setShares("someones", List.of(9L));

        ArgumentCaptor<org.booklore.model.entity.OverDriveCardShareEntity> saved =
                ArgumentCaptor.forClass(org.booklore.model.entity.OverDriveCardShareEntity.class);
        verify(cardShareRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenId()).isEqualTo(8L);
        assertThat(saved.getValue().getSharedWithUserId()).isEqualTo(9L);
    }

    @Test
    void setShares_byAdmin_ambiguousIdentity_isRejected() {
        authAsAdmin(1L);
        when(tokenRepository.findByUserIdAndIdentity(1L, "dup")).thenReturn(Optional.empty());
        when(tokenRepository.findByIdentity("dup")).thenReturn(List.of(
                OverDriveTokenEntity.builder().id(1L).userId(2L).identity("dup").token("t").build(),
                OverDriveTokenEntity.builder().id(2L).userId(3L).identity("dup").token("t").build()));

        assertThatThrownBy(() -> service.setShares("dup", List.of(9L)))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(cardShareRepository, never()).save(any());
    }

    @Test
    void selectFormat_picksAudiobookOnlyWhenHandlerReady() {
        var formats = List.of("audiobook-overdrive", "audiobook-mp3");
        // Prefers audiobook-mp3, and only when an audiobook handler is ready.
        assertThat(OverDriveService.selectFormat(formats, OverDriveService.defaultFormatPreference(), false, true))
                .isEqualTo("audiobook-mp3");
        assertThat(OverDriveService.selectFormat(formats, OverDriveService.defaultFormatPreference(), false, false))
                .isNull();
    }

    @Test
    void selectFormat_stillPrefersEbookFormats() {
        var formats = List.of("ebook-epub-open", "audiobook-mp3");
        // A loan is one medium in practice, but ebook preference still wins when both are (hypothetically) offered.
        assertThat(OverDriveService.selectFormat(formats, OverDriveService.defaultFormatPreference(), false, true))
                .isEqualTo("ebook-epub-open");
    }

    @Test
    void resolveLinkedBookId_matchesByAsinWhenNoIsbn() {
        authAsAdmin(7L); // admin → global (unscoped) match
        when(bookRepository.findIdsByAsin("B0ABCD1234")).thenReturn(List.of(55L));
        assertThat(service.resolveLinkedBookId(null, "B0ABCD1234")).isEqualTo(55L);
    }

    @Test
    void resolveLinkedBookId_prefersIsbnOverAsin() {
        authAsAdmin(7L); // admin → global (unscoped) match
        when(bookRepository.findIdsByIsbn13("9780441013593")).thenReturn(List.of(7L));
        assertThat(service.resolveLinkedBookId("9780441013593", "B0ABCD1234")).isEqualTo(7L);
        verify(bookRepository, never()).findIdsByAsin(any()); // ASIN not consulted when ISBN matches
    }

    @Test
    void resolveLinkedBookId_scopesToUsersAccessibleLibraries() {
        // Non-admin: matches are constrained to the user's assigned libraries.
        BookLoreUser user = BookLoreUser.builder()
                .id(7L)
                .assignedLibraries(List.of(org.booklore.model.dto.Library.builder().id(3L).build()))
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(bookRepository.findIdsByAsinAndLibraryIdIn("B0ABCD1234", List.of(3L))).thenReturn(List.of(55L));

        assertThat(service.resolveLinkedBookId(null, "B0ABCD1234")).isEqualTo(55L);
        verify(bookRepository, never()).findIdsByAsin(any()); // never the unscoped query for a non-admin
    }

    @Test
    void shareableUsers_excludesSelfAndSortsByName() {
        authAs(7L);
        // The picker is only available to a user who actually owns a card to share.
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").token("t").build()));
        when(userRepository.findAll()).thenReturn(List.of(
                org.booklore.model.entity.BookLoreUserEntity.builder().id(7L).username("me").name("Me").build(),
                org.booklore.model.entity.BookLoreUserEntity.builder().id(8L).username("bob").name("Bob").build(),
                org.booklore.model.entity.BookLoreUserEntity.builder().id(9L).username("ann").name("Ann").build()));

        assertThat(service.shareableUsers()).extracting(u -> u.userId()).containsExactly(9L, 8L);
    }

    @Test
    void shareableUsers_emptyWhenUserOwnsNoCardAndIsNotAdmin() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of());
        assertThat(service.shareableUsers()).isEmpty();
        verify(userRepository, never()).findAll();
    }

    @Test
    void orderMagazineFormats_putsReflowableArticlesVariantFirst() {
        var layout = new org.booklore.service.magazine.MagazineHandler.OutputFile(
                "TIME America at 250.epub", "epub", java.nio.file.Path.of("/tmp/layout.epub"));
        var articles = new org.booklore.service.magazine.MagazineHandler.OutputFile(
                "TIME America at 250 (Articles).epub", "epub", java.nio.file.Path.of("/tmp/articles.epub"));

        // Regardless of input order, the reflowable "(Articles)" version is the primary (first).
        assertThat(OverDriveService.orderMagazineFormats(List.of(layout, articles)))
                .extracting(f -> f.fileName())
                .containsExactly("TIME America at 250 (Articles).epub", "TIME America at 250.epub");
        assertThat(OverDriveService.orderMagazineFormats(List.of(articles, layout)))
                .extracting(f -> f.fileName())
                .containsExactly("TIME America at 250 (Articles).epub", "TIME America at 250.epub");
    }

    @Test
    void parseToolEvent_recognisesStructuredEvents() {
        assertThat(OverDriveService.parseToolEvent(
                "{\"type\":\"progress\",\"phase\":\"download\",\"current\":3,\"total\":12,\"pct\":25.0}"))
                .containsEntry("type", "progress")
                .containsEntry("phase", "download")
                .containsEntry("total", 12);
        assertThat(OverDriveService.parseToolEvent("{\"type\":\"log\",\"level\":\"warn\",\"message\":\"slow\"}"))
                .containsEntry("type", "log");
        assertThat(OverDriveService.parseToolEvent("{\"type\":\"result\",\"ok\":true,\"file\":\"a.pdf\"}"))
                .containsEntry("type", "result")
                .containsEntry("ok", true);
    }

    @Test
    void parseToolEvent_toleratesUnknownFields() {
        assertThat(OverDriveService.parseToolEvent(
                "{\"type\":\"progress\",\"phase\":\"auth\",\"futureField\":123}"))
                .containsEntry("phase", "auth");
    }

    @Test
    void parseToolEvent_returnsNullForNonEvents() {
        // plain text, merged stderr diagnostics
        assertThat(OverDriveService.parseToolEvent("Downloading part 3 of 12")).isNull();
        // JSON but no/unknown type -> treated as a plain line by the caller
        assertThat(OverDriveService.parseToolEvent("{\"foo\":\"bar\"}")).isNull();
        assertThat(OverDriveService.parseToolEvent("{\"type\":\"mystery\"}")).isNull();
        // malformed / partial JSON must never throw
        assertThat(OverDriveService.parseToolEvent("{\"type\":\"progress\"")).isNull();
        assertThat(OverDriveService.parseToolEvent("")).isNull();
        assertThat(OverDriveService.parseToolEvent(null)).isNull();
    }

    // ── Concurrent borrow-and-import guard ───────────────────────────────
    //
    // Two concurrent imports of the same title used to both run the external handler and then race on
    // the same computed library path; the loser re-wrote the whole file and died on "File does not
    // exist or is not a regular file" after the winner's file move vacated that path.

    /**
     * Hold {@code findByUserIdAndIdentity} — the first thing borrow-and-import does, via resolveToken —
     * on its first call, so a second import can be attempted while the first is genuinely in flight.
     */
    private void blockFirstTokenLookupOn(CountDownLatch entered, CountDownLatch release) {
        AtomicBoolean first = new AtomicBoolean(true);
        when(tokenRepository.findByUserIdAndIdentity(any(), any())).thenAnswer(inv -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return Optional.empty();
        });
    }

    @Test
    void borrowAndImport_rejectsSecondConcurrentImportOfSameTitle() throws Exception {
        authAs(1L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstTokenLookupOn(entered, release);

        Thread inFlight = new Thread(() -> {
            try {
                service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null);
            } catch (RuntimeException ignored) {
                // the held lookup returns no token, so this import fails — we only need it in flight
            }
        });
        inFlight.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() ->
                    service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null))
                    .hasMessageContaining("already being imported");
        } finally {
            release.countDown();
            inFlight.join(5000);
        }

        // The external handler must never run for the rejected duplicate.
        verifyNoInteractions(audiobookHandler);
        verifyNoInteractions(magazineHandler);
    }

    @Test
    void borrowAndImport_allowsConcurrentImportOfDifferentTitle() throws Exception {
        authAs(1L);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstTokenLookupOn(entered, release);

        Thread inFlight = new Thread(() -> {
            try {
                service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null);
            } catch (RuntimeException ignored) {
                // see above
            }
        });
        inFlight.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            // A different title is not blocked: it gets past the guard and fails on the missing token.
            assertThatThrownBy(() ->
                    service.borrowAndImport("card-1", "title-OTHER", null, null, "T", "A", null, null, null, null))
                    .hasMessageContaining("No OverDrive token available");
        } finally {
            release.countDown();
            inFlight.join(5000);
        }
    }

    @Test
    void borrowAndImport_releasesGuardAfterImportFinishes() {
        authAs(1L);
        when(tokenRepository.findByUserIdAndIdentity(any(), any())).thenReturn(Optional.empty());

        // Two sequential imports of the same title both reach the token check — the guard is per
        // in-flight import, not a permanent "already imported" block (the UI offers "Import again").
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() ->
                    service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null))
                    .hasMessageContaining("No OverDrive token available");
        }
    }
}
