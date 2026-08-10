package org.booklore.service.overdrive;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.overdrive.OverDriveLoan;
import org.booklore.model.dto.overdrive.OverDriveSyncResponse;
import org.booklore.model.entity.OverDriveLoanEntity;
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
    @Mock private org.booklore.service.ebook.EbookHandler ebookHandler;
    @Mock private org.booklore.service.book.BookService bookService;
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
                ebookHandler, bookService,
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

    /** A non-admin who may manage sharing on any card, but not administer the cards themselves. */
    private void authAsShareManager(long userId) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setCanAccessOverdrive(true);
        perms.setCanManageAllOverdriveShares(true);
        when(authenticationService.getAuthenticatedUser())
                .thenReturn(BookLoreUser.builder().id(userId).permissions(perms).build());
    }

    /** A non-admin with full cross-user card administration (which implies share management). */
    private void authAsCardManager(long userId) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setCanAccessOverdrive(true);
        perms.setCanManageAllOverdriveCards(true);
        when(authenticationService.getAuthenticatedUser())
                .thenReturn(BookLoreUser.builder().id(userId).permissions(perms).build());
    }

    /** An ordinary OverDrive user: feature access, but confined to their own cards. */
    private void authAsOverdriveUser(long userId) {
        BookLoreUser.UserPermissions perms = new BookLoreUser.UserPermissions();
        perms.setCanAccessOverdrive(true);
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
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-1")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(3L).userId(7L).identity("card-1").token("t").build()));
        service.removeToken("card-1");
        verify(tokenRepository).deleteByUserIdAndIdentity(7L, "card-1");
    }

    @Test
    void removeToken_forACardYouDoNotHave_is404RatherThanASilentNoOp() {
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeToken("nope"))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).deleteByUserIdAndIdentity(any(), any());
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

    // ── Chip recovery ────────────────────────────────────────────────────

    @Test
    void isMissingChip_detectsSentrysForbiddenBody() {
        var forbidden = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", new org.springframework.http.HttpHeaders(),
                "{\"result\":\"missing_chip\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        assertThat(OverDriveService.isMissingChip(forbidden)).isTrue();
    }

    @Test
    void isMissingChip_detectsItThroughTheCallSitesWrapper() {
        // sync()/borrow() wrap their failures; the 403 body survives in the message (and the cause).
        var wrapped = new org.springframework.web.client.RestClientException(
                "OverDrive sync failed: 403 Forbidden: \"{\"result\":\"missing_chip\"}\"");
        assertThat(OverDriveService.isMissingChip(wrapped)).isTrue();
    }

    @Test
    void isMissingChip_ignoresOtherFailures() {
        var whoa = org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", new org.springframework.http.HttpHeaders(),
                "{\"result\":\"whoa\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        assertThat(OverDriveService.isMissingChip(whoa)).isFalse();
        assertThat(OverDriveService.isMissingChip(new IllegalStateException("boom"))).isFalse();
        assertThat(OverDriveService.isMissingChip(new IllegalStateException((String) null))).isFalse();
    }

    @Test
    void borrowAndImport_failsWithoutImportingWhenBorrowFails() {
        // Stored token for the current user's card → borrow proceeds to the (mocked) RestClient and
        // fails; nothing is imported.
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card").token("t").build()));
        assertThatThrownBy(() -> service.borrowAndImport("card", "title", 1L, 1L, "t", "a", null, null, null, null, null))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(overDriveImportService);
    }

    @Test
    void borrowAndImport_failsWhenNoTokenAvailable() {
        // No stored token for the current user's card → resolveToken throws, nothing imported.
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.borrowAndImport("card", "title", 1L, 1L, "t", "a", null, null, null, null, null))
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
    void refreshCard_renewsTheOwnersRowForASharee() {
        // A sharee's card can only keep working if renewal writes the OWNER's token row (the row the
        // share points at). Proof that it resolved that row and used its credentials: it got as far as
        // minting a chip. The (mocked) RestClient then fails, so the renewal itself reports failure —
        // with the old owner-only lookup it would have bailed out before touching the network at all.
        OverDriveCredentialCipher cipher = enabledCipher();
        OverDriveService shared = serviceWith(cipher);
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "shared")).thenReturn(Optional.empty());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of(
                org.booklore.model.entity.OverDriveCardShareEntity.builder().tokenId(2L).sharedWithUserId(7L).build()));
        when(tokenRepository.findById(2L)).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(2L).userId(3L).identity("shared").token("owner-token")
                        .credCard(cipher.encrypt("2999900011")).credPin(cipher.encrypt("1234"))
                        .websiteId("123").ilsName("lapl").build()));

        assertThatThrownBy(() -> shared.refreshCard("shared")).isInstanceOf(RuntimeException.class);

        verify(restClient).post(); // reached requestChip() → the owner's row and credentials were resolved
        verify(tokenRepository, never()).save(any()); // nothing persisted when the re-link fails
    }

    @Test
    void refreshCard_leavesTheNetworkAloneWhenTheCardHasNoStoredCredentials() {
        // Setup-code / pasted-token links have no card+PIN, so there is nothing to renew from.
        OverDriveService shared = serviceWith(enabledCipher());
        authAs(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(1L).userId(7L).identity("card").token("t").build()));

        assertThatThrownBy(() -> shared.refreshCard("card"))
                .hasMessageContaining("no stored card+PIN");
        verifyNoInteractions(restClient);
    }

    /** A credential cipher with a real key, so credential storage / the re-link path is enabled. */
    private OverDriveCredentialCipher enabledCipher() {
        OverDriveCredentialCipher cipher = new OverDriveCredentialCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(cipher.isEnabled()).isTrue();
        return cipher;
    }

    /** A service built on the given cipher (the default one in {@link #setUp} has no key). */
    private OverDriveService serviceWith(OverDriveCredentialCipher cipher) {
        return new OverDriveService(loanRepository, bookRepository, acsmHandler, audiobookHandler, magazineHandler,
                ebookHandler, bookService,
                restClient, overDriveImportService, overDriveParser, tokenRepository, cardShareRepository, auditRepository,
                importDestinationRepository, userRepository, authenticationService, appSettingService, cipher,
                notificationService, bookFileAttachmentService);
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
    void setShares_byShareManagerForAnotherUsersCard_isAllowed() {
        authAsShareManager(5L);
        when(tokenRepository.findByUserIdAndIdentity(5L, "someones")).thenReturn(Optional.empty());
        when(tokenRepository.findByIdentity("someones")).thenReturn(List.of(
                OverDriveTokenEntity.builder().id(8L).userId(4L).identity("someones").token("t").build()));
        when(cardShareRepository.findByTokenId(8L)).thenReturn(List.of());
        when(userRepository.existsById(9L)).thenReturn(true);

        service.setShares("someones", List.of(9L));

        ArgumentCaptor<org.booklore.model.entity.OverDriveCardShareEntity> saved =
                ArgumentCaptor.forClass(org.booklore.model.entity.OverDriveCardShareEntity.class);
        verify(cardShareRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenId()).isEqualTo(8L);
    }

    @Test
    void setShares_byPlainOverdriveUserForAnotherUsersCard_isForbidden() {
        authAsOverdriveUser(5L);
        when(tokenRepository.findByUserIdAndIdentity(5L, "someones")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setShares("someones", List.of(9L)))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).findByIdentity(any());
        verify(cardShareRepository, never()).save(any());
    }

    @Test
    void shareableUsers_emptyForAPlainOverdriveUserWithNoCardOfTheirOwn() {
        authAsOverdriveUser(5L);
        when(tokenRepository.findByUserId(5L)).thenReturn(List.of());

        assertThat(service.shareableUsers()).isEmpty();
        verify(userRepository, never()).findAll();
    }

    @Test
    void setShares_byCardManager_isAllowedBecauseCardManagementImpliesShareManagement() {
        authAsCardManager(5L);
        when(tokenRepository.findByUserIdAndIdentity(5L, "someones")).thenReturn(Optional.empty());
        when(tokenRepository.findByIdentity("someones")).thenReturn(List.of(
                OverDriveTokenEntity.builder().id(8L).userId(4L).identity("someones").token("t").build()));
        when(cardShareRepository.findByTokenId(8L)).thenReturn(List.of());
        when(userRepository.existsById(9L)).thenReturn(true);

        service.setShares("someones", List.of(9L));

        verify(cardShareRepository).save(any());
    }

    @Test
    void setShares_withAnExplicitOwner_resolvesAnIdentityLinkedByMultipleUsers() {
        authAsCardManager(5L);
        when(tokenRepository.findByUserIdAndIdentity(3L, "dup")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(2L).userId(3L).identity("dup").token("t").build()));
        when(cardShareRepository.findByTokenId(2L)).thenReturn(List.of());
        when(userRepository.existsById(9L)).thenReturn(true);

        service.setShares("dup", List.of(9L), 3L);

        ArgumentCaptor<org.booklore.model.entity.OverDriveCardShareEntity> saved =
                ArgumentCaptor.forClass(org.booklore.model.entity.OverDriveCardShareEntity.class);
        verify(cardShareRepository).save(saved.capture());
        assertThat(saved.getValue().getTokenId()).isEqualTo(2L);
        // Never consults the ambiguous by-identity lookup when the owner is named.
        verify(tokenRepository, never()).findByIdentity(any());
    }

    @Test
    void removeToken_forAnotherUsersCard_isAllowedForACardManagerAndDropsItsShares() {
        authAsCardManager(5L);
        when(tokenRepository.findByUserIdAndIdentity(4L, "theirs")).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().id(8L).userId(4L).identity("theirs").cardName("LAPL").token("t").build()));
        when(cardShareRepository.findByTokenId(8L)).thenReturn(List.of(
                org.booklore.model.entity.OverDriveCardShareEntity.builder().id(1L).tokenId(8L).sharedWithUserId(9L).build()));

        service.removeToken("theirs", 4L);

        verify(cardShareRepository).delete(any());
        verify(tokenRepository).deleteByUserIdAndIdentity(4L, "theirs");
    }

    @Test
    void removeToken_forAnotherUsersCard_isForbiddenForAShareManager() {
        authAsShareManager(5L);

        assertThatThrownBy(() -> service.removeToken("theirs", 4L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).deleteByUserIdAndIdentity(any(), any());
    }

    @Test
    void setCardLabel_forAnotherUsersCard_isForbiddenWithoutCardManagement() {
        authAsOverdriveUser(5L);

        assertThatThrownBy(() -> service.setCardLabel("theirs", "Nickname", 4L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void setCardLabel_forAnotherUsersCard_isAllowedForACardManager() {
        authAsCardManager(5L);
        OverDriveTokenEntity row = OverDriveTokenEntity.builder()
                .id(8L).userId(4L).identity("theirs").token("t").build();
        when(tokenRepository.findByUserIdAndIdentity(4L, "theirs")).thenReturn(Optional.of(row));

        service.setCardLabel("theirs", "Nickname", 4L);

        verify(tokenRepository).save(row);
        assertThat(row.getCardName()).isEqualTo("Nickname");
    }

    @Test
    void listAllCards_isForbiddenWithoutCardManagement() {
        authAsShareManager(5L);

        assertThatThrownBy(() -> service.listAllCards())
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).findAll();
    }

    @Test
    void listAllCards_returnsEveryUsersCardsWithTheirOwner() {
        authAsCardManager(5L);
        when(tokenRepository.findAll()).thenReturn(List.of(
                OverDriveTokenEntity.builder().id(1L).userId(4L).identity("a").cardName("LAPL").token("t").build(),
                OverDriveTokenEntity.builder().id(2L).userId(9L).identity("b").cardName("BPL").token("t").build()));
        when(userRepository.findById(4L)).thenReturn(Optional.of(
                org.booklore.model.entity.BookLoreUserEntity.builder().id(4L).username("ann").name("Ann").build()));
        when(userRepository.findById(9L)).thenReturn(Optional.of(
                org.booklore.model.entity.BookLoreUserEntity.builder().id(9L).username("bob").name("Bob").build()));

        assertThat(service.listAllCards())
                .extracting(c -> c.ownerUserId() + ":" + c.ownerName() + ":" + c.cardId())
                .containsExactly("4:Ann:a", "9:Bob:b");
    }

    @Test
    void shareableUsers_scopedToAnotherOwner_excludesThatOwnerRatherThanTheCaller() {
        authAsCardManager(5L);
        when(userRepository.findAll()).thenReturn(List.of(
                overdriveUser(4L, "ann", "Ann"),
                overdriveUser(5L, "me", "Me"),
                overdriveUser(9L, "bob", "Bob")));

        // Sharing user 4's card: user 4 already has it, but the manager themselves is a valid target.
        assertThat(service.shareableUsers(4L)).extracting(u -> u.userId()).containsExactly(9L, 5L);
    }

    @Test
    void shareableUsers_scopedToAnotherOwner_isForbiddenWithoutShareManagement() {
        authAsOverdriveUser(5L);

        assertThatThrownBy(() -> service.shareableUsers(4L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(userRepository, never()).findAll();
    }

    @Test
    void storeToken_forAnotherUser_isForbiddenWithoutCardManagement() {
        authAsOverdriveUser(5L);

        assertThatThrownBy(() -> service.storeToken("card-1", null, null, "tok", 4L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void storeToken_forAnotherUser_writesTheRowAgainstThatUser() {
        authAsCardManager(5L);
        when(userRepository.existsById(4L)).thenReturn(true);
        when(tokenRepository.findByUserIdAndIdentity(4L, "card-1")).thenReturn(Optional.empty());

        service.storeToken("card-1", "LAPL", "lapl", "tok", 4L);

        ArgumentCaptor<OverDriveTokenEntity> captor = ArgumentCaptor.forClass(OverDriveTokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(4L);
    }

    @Test
    void storeToken_forAnUnknownUser_isRejected() {
        authAsCardManager(5L);
        when(userRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.storeToken("card-1", null, null, "tok", 404L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).save(any());
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
                overdriveUser(7L, "me", "Me"),
                overdriveUser(8L, "bob", "Bob"),
                overdriveUser(9L, "ann", "Ann"),
                // Sharing a card with someone who cannot reach OverDrive would be a no-op.
                org.booklore.model.entity.BookLoreUserEntity.builder().id(10L).username("zoe").name("Zoe").build()));

        assertThat(service.shareableUsers()).extracting(u -> u.userId()).containsExactly(9L, 8L);
    }

    /** A user entity carrying the OverDrive permission, as the share picker requires. */
    private static org.booklore.model.entity.BookLoreUserEntity overdriveUser(Long id, String username, String name) {
        var user = org.booklore.model.entity.BookLoreUserEntity.builder().id(id).username(username).name(name).build();
        var perms = org.booklore.model.entity.UserPermissionsEntity.builder().permissionAccessOverdrive(true).build();
        perms.setUser(user);
        user.setPermissions(perms);
        return user;
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

    // ── Replace (delete the existing copy, then re-import) ───────────────

    @Test
    void deleteReplacedBook_isANoOpForAPlainImport() {
        service.deleteReplacedBook(null, "loan-1");

        verifyNoInteractions(bookService);
        verifyNoInteractions(bookRepository);
    }

    @Test
    void deleteReplacedBook_deletesTheOldCopyAndClearsTheLoanLink() {
        authAs(1L);
        // Present before the delete, gone after it.
        when(bookRepository.existsById(99L)).thenReturn(true, false);
        OverDriveLoanEntity row = new OverDriveLoanEntity();
        row.setOverdriveLoanId("loan-1");
        row.setBookId(99L);
        when(loanRepository.findByUserIdAndOverdriveLoanId(1L, "loan-1")).thenReturn(Optional.of(row));

        service.deleteReplacedBook(99L, "loan-1");

        verify(bookService).deleteBooks(java.util.Set.of(99L));
        // Cleared before deleting, so a subsequent import failure can't leave a dangling book link.
        verify(loanRepository).save(row);
        assertThat(row.getBookId()).isNull();
    }

    @Test
    void deleteReplacedBook_failsUpFrontWhenTheCopyToReplaceIsGone() {
        when(bookRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteReplacedBook(99L, "loan-1"))
                .hasMessageContaining("no longer exists");

        verify(bookService, never()).deleteBooks(any());
        verify(loanRepository, never()).save(any());
    }

    @Test
    void deleteReplacedBook_refusesWhenTheDeleteDidNotTake() {
        // deleteBooks echoes the requested ids back as "deleted" even when it skipped a book outside the
        // user's libraries, so a still-present book afterwards must abort the replace rather than let the
        // re-import collide on the old file's path.
        authAs(1L);
        when(bookRepository.existsById(99L)).thenReturn(true, true);
        when(loanRepository.findByUserIdAndOverdriveLoanId(1L, "loan-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteReplacedBook(99L, "loan-1"))
                .hasMessageContaining("not replaced");
    }

    @Test
    void selectFormat_picksReadInBrowserEbookOnlyWhenHandlerReady() {
        // The Libby-Read-only case: no open format, no Adobe format, so nothing to fulfill without
        // the ebook handler.
        List<String> formats = List.of("ebook-overdrive", "ebook-kobo");

        assertThat(OverDriveService.selectFormat(
                formats, OverDriveService.defaultFormatPreference(), false, false, true))
                .isEqualTo("ebook-overdrive");
        assertThat(OverDriveService.selectFormat(
                formats, OverDriveService.defaultFormatPreference(), false, false, false))
                .isNull();
        // An ACSM handler cannot help here — there is no Adobe format and no ACSM to hand it.
        assertThat(OverDriveService.selectFormat(
                formats, OverDriveService.defaultFormatPreference(), true, true, false))
                .isNull();
    }

    @Test
    void selectFormat_prefersRealDownloadFormatsOverReadInBrowser() {
        // ebook-overdrive is a rebuild from web-reader assets, so it is the last resort even when the
        // handler is ready and the alternative needs an ACSM.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-overdrive", "ebook-epub-open"),
                OverDriveService.defaultFormatPreference(), false, false, true))
                .isEqualTo("ebook-epub-open");
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-overdrive", "ebook-epub-adobe"),
                OverDriveService.defaultFormatPreference(), true, false, true))
                .isEqualTo("ebook-epub-adobe");
        // ...but with no ACSM handler the Adobe format is unusable, so the rebuild wins.
        assertThat(OverDriveService.selectFormat(
                List.of("ebook-overdrive", "ebook-epub-adobe"),
                OverDriveService.defaultFormatPreference(), false, false, true))
                .isEqualTo("ebook-overdrive");
    }

    @Test
    void noImportableFormatMessage_pointsAtTheEbookHandlerForReadInBrowserTitles() {
        String message = OverDriveService.noImportableFormatMessage(
                "1084737", List.of("ebook-overdrive", "ebook-kobo"));

        assertThat(message)
                .contains("Libby's read-in-browser format requires a configured ebook handler.")
                .doesNotContain("ACSM");
    }

    @Test
    void noImportableFormatMessage_doesNotMentionAcsmWhenNoAdobeFormatIsOffered() {
        // The Kobo hand-off format alone: no handler Grimmory has could ever fulfill it, so the message
        // must not send the operator chasing an ACSM (or any other) setting.
        String message = OverDriveService.noImportableFormatMessage("1084737", List.of("ebook-kobo"));

        assertThat(message)
                .contains("loan 1084737")
                .contains("ebook-kobo")
                .contains("None of these can be downloaded")
                .doesNotContain("ACSM")
                .doesNotContain("audiobook handler")
                .doesNotContain("ebook handler");
    }

    @Test
    void noImportableFormatMessage_pointsAtTheAcsmHandlerOnlyForAdobeFormats() {
        String message = OverDriveService.noImportableFormatMessage(
                "42", List.of("ebook-epub-adobe", "ebook-kobo"));

        assertThat(message).contains("Adobe formats require a configured external ACSM handler.");
        assertThat(message).doesNotContain("None of these can be downloaded");
    }

    @Test
    void noImportableFormatMessage_pointsAtTheAudiobookHandlerForAudiobookFormats() {
        String message = OverDriveService.noImportableFormatMessage("42", List.of("audiobook-mp3"));

        assertThat(message).contains("Audiobook formats require a configured audiobook handler.");
        assertThat(message).doesNotContain("ACSM");
    }

    @Test
    void noImportableFormatMessage_namesEveryUnconfiguredHandlerTheLoanCouldUse() {
        String message = OverDriveService.noImportableFormatMessage(
                "42", List.of("ebook-pdf-adobe", "audiobook-mp3"));

        assertThat(message)
                .contains("Adobe formats require a configured external ACSM handler.")
                .contains("Audiobook formats require a configured audiobook handler.");
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
                service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null, null);
            } catch (RuntimeException ignored) {
                // the held lookup returns no token, so this import fails — we only need it in flight
            }
        });
        inFlight.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() ->
                    service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null, null))
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
                service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null, null);
            } catch (RuntimeException ignored) {
                // see above
            }
        });
        inFlight.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            // A different title is not blocked: it gets past the guard and fails on the missing token.
            assertThatThrownBy(() ->
                    service.borrowAndImport("card-1", "title-OTHER", null, null, "T", "A", null, null, null, null, null))
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
                    service.borrowAndImport("card-1", "title-9", null, null, "T", "A", null, null, null, null, null))
                    .hasMessageContaining("No OverDrive token available");
        }
    }

    // ── syncAll: one upstream call per chip ──────────────────────────────

    /** A card row the current user owns, authenticated by {@code token}. */
    private void stubCard(long userId, String identity, String token) {
        when(tokenRepository.findByUserIdAndIdentity(userId, identity)).thenReturn(Optional.of(
                OverDriveTokenEntity.builder().userId(userId).identity(identity).token(token).build()));
    }

    /**
     * A service whose Libby calls hit a deep-stubbed RestClient returning {@code body}, plus a counter of
     * how many chip syncs actually went upstream.
     */
    private record SyncHarness(OverDriveService service, java.util.concurrent.atomic.AtomicInteger calls) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SyncHarness syncHarness(OverDriveSyncResponse body) {
        RestClient client = org.mockito.Mockito.mock(RestClient.class);
        // RestClient's fluent spec types are self-referentially generic, which deep stubs can't follow —
        // wire the chain explicitly instead.
        RestClient.RequestHeadersUriSpec spec = org.mockito.Mockito.mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.ResponseSpec responseSpec = org.mockito.Mockito.mock(RestClient.ResponseSpec.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(client.get()).thenReturn(spec);
        when(spec.uri(org.mockito.ArgumentMatchers.anyString())).thenReturn(spec);
        when(spec.headers(any())).thenReturn(spec);
        when(spec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(OverDriveSyncResponse.class))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    return org.springframework.http.ResponseEntity.ok(body);
                });
        OverDriveService svc = new OverDriveService(loanRepository, bookRepository, acsmHandler, audiobookHandler,
                magazineHandler, ebookHandler, bookService,
                client, overDriveImportService, overDriveParser, tokenRepository, cardShareRepository, auditRepository,
                importDestinationRepository, userRepository, authenticationService, appSettingService,
                new OverDriveCredentialCipher(""), notificationService, bookFileAttachmentService);
        return new SyncHarness(svc, calls);
    }

    @Test
    void syncAll_collapsesCardsSharingAChipIntoOneUpstreamCall() {
        authAs(7L);
        // Three cards linked by one setup code share a token — i.e. one chip — and a fourth is its own.
        stubCard(7L, "card-a", "chip-1");
        stubCard(7L, "card-b", "chip-1");
        stubCard(7L, "card-c", "chip-1");
        stubCard(7L, "card-d", "chip-2");
        SyncHarness h = syncHarness(new OverDriveSyncResponse());

        var result = h.service().syncAll(List.of("card-a", "card-b", "card-c", "card-d"));

        // Two chips → two calls, not four. Every requested card still gets its chip's response.
        assertThat(h.calls()).hasValue(2);
        assertThat(result).containsOnlyKeys("card-a", "card-b", "card-c", "card-d");
        // The three cards on chip-1 are handed the very same response object — one fetch, shared.
        assertThat(result.get("card-a")).isSameAs(result.get("card-b")).isSameAs(result.get("card-c"));
    }

    @Test
    void syncAll_deduplicatesRepeatedIdentities() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        SyncHarness h = syncHarness(new OverDriveSyncResponse());

        assertThat(h.service().syncAll(List.of("card-a", "card-a"))).containsOnlyKeys("card-a");
        assertThat(h.calls()).hasValue(1);
    }

    @Test
    void syncAll_skipsCardsWithNoStoredToken() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-gone")).thenReturn(Optional.empty());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        SyncHarness h = syncHarness(new OverDriveSyncResponse());

        // An unlinked card is simply absent rather than failing the whole batch.
        assertThat(h.service().syncAll(List.of("card-a", "card-gone"))).containsOnlyKeys("card-a");
        assertThat(h.calls()).hasValue(1);
    }

    @Test
    void syncAll_returnsNothingForNoIdentities() {
        assertThat(service.syncAll(List.of())).isEmpty();
        assertThat(service.syncAll(null)).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    void syncAll_persistsEachLoanUnderItsOwnCard() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        stubCard(7L, "card-b", "chip-1");

        // A chip sync carries both cards' loans; each must be stored against the card it sits on, not
        // against whichever card the call happened to be made with.
        OverDriveSyncResponse body = new OverDriveSyncResponse();
        OverDriveLoan onA = new OverDriveLoan();
        onA.setId("loan-1");
        onA.setCardId("card-a");
        OverDriveLoan onB = new OverDriveLoan();
        onB.setId("loan-2");
        onB.setCardId("card-b");
        body.setLoans(List.of(onA, onB));
        when(loanRepository.findByUserIdAndOverdriveLoanId(any(), any())).thenReturn(Optional.empty());

        syncHarness(body).service().syncAll(List.of("card-a", "card-b"));

        ArgumentCaptor<OverDriveLoanEntity> saved = ArgumentCaptor.forClass(OverDriveLoanEntity.class);
        verify(loanRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OverDriveLoanEntity::getOverdriveLoanId, OverDriveLoanEntity::getIdentity)
                .contains(org.assertj.core.groups.Tuple.tuple("loan-1", "card-a"),
                          org.assertj.core.groups.Tuple.tuple("loan-2", "card-b"));
    }
}
