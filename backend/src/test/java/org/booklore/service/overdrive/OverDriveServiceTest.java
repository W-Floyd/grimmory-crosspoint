package org.booklore.service.overdrive;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.overdrive.OverDriveAutoSyncSettings;
import org.booklore.model.dto.overdrive.OverDriveLoan;
import org.booklore.model.dto.overdrive.OverDriveSyncResponse;
import org.booklore.model.entity.OverDriveCardShareEntity;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    @Mock private org.booklore.repository.OverDriveCardLimitRepository cardLimitRepository;
    @Mock private org.booklore.repository.OverDriveBookbagRepository bookbagRepository;
    @Mock private org.booklore.repository.OverDriveImportDestinationRepository importDestinationRepository;
    @Mock private org.booklore.repository.OverDriveAutoSyncRepository autoSyncRepository;
    @Mock private java.net.http.HttpClient httpClient;
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
                cardLimitRepository, bookbagRepository, importDestinationRepository, autoSyncRepository, httpClient, userRepository, authenticationService, appSettingService, cipher,
                notificationService, bookFileAttachmentService);
    }

    /** A fresh pass counter. Each of these tests exercises one phase on its own. */
    private static OverDriveService.LoanActionPacer pacer() {
        return new OverDriveService.LoanActionPacer();
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
                cardLimitRepository, bookbagRepository, importDestinationRepository, autoSyncRepository, httpClient, userRepository, authenticationService, appSettingService, cipher,
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
        verify(userRepository, never()).findAllWithPermissions();
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
        when(userRepository.findAllWithPermissions()).thenReturn(List.of(
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
        verify(userRepository, never()).findAllWithPermissions();
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
    void redeemSetupCode_forAnotherUser_isForbiddenWithoutCardManagement() {
        authAsOverdriveUser(5L);

        // Rejected on the permission check, before the code is ever sent to OverDrive.
        assertThatThrownBy(() -> service.redeemSetupCode("12345678", 4L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void linkToken_forAnotherUser_isForbiddenWithoutCardManagement() {
        authAsOverdriveUser(5L);

        assertThatThrownBy(() -> service.linkToken("eyJhbGciOi.abc.def", 4L))
                .isInstanceOf(org.booklore.exception.APIException.class);
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void redeemSetupCode_forAnotherUser_checksThePermissionBeforeValidatingTheCode() {
        authAsOverdriveUser(5L);

        // An invalid code would also be rejected, but authorization is the failure that must win.
        assertThatThrownBy(() -> service.redeemSetupCode("nonsense", 4L))
                .isInstanceOf(org.booklore.exception.APIException.class)
                .hasMessageContaining("yourself");
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
    void resolveLinkedBookId_prefersTheOverdriveIdOverIsbnAndAsin() {
        authAsAdmin(7L);
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of(99L));

        assertThat(service.resolveLinkedBookId("2056901", "9780441013593", "B0ABCD1234")).isEqualTo(99L);
        // The id names one edition; the weaker keys are not consulted once it hits.
        verify(bookRepository, never()).findIdsByIsbn13(any());
        verify(bookRepository, never()).findIdsByAsin(any());
    }

    @Test
    void resolveLinkedBookId_fallsBackToIsbnWhenTheTitleHasNoRecordedId() {
        authAsAdmin(7L);
        // Books imported before ids were recorded have none, so the older keys still have to work.
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());
        when(bookRepository.findIdsByIsbn13("9780441013593")).thenReturn(List.of(7L));

        assertThat(service.resolveLinkedBookId("2056901", "9780441013593", null)).isEqualTo(7L);
    }

    @Test
    void resolveLinkedBookId_byOverdriveId_staysWithinTheUsersLibraries() {
        BookLoreUser user = BookLoreUser.builder()
                .id(7L)
                .assignedLibraries(List.of(org.booklore.model.dto.Library.builder().id(3L).build()))
                .build();
        when(authenticationService.getAuthenticatedUser()).thenReturn(user);
        when(bookRepository.findIdsByOverdriveIdAndLibraryIdIn("2056901", List.of(3L))).thenReturn(List.of(99L));

        assertThat(service.resolveLinkedBookId("2056901", null, null)).isEqualTo(99L);
        verify(bookRepository, never()).findIdsByOverdriveId(any()); // never the unscoped query
    }

    @Test
    void resolveLoanBookId_matchesOnTheLoanIdBecauseALoanIdIsATitleId() {
        authAsAdmin(7L);
        // No row of our own for this loan — borrowed in the Libby app, or imported by another user.
        when(loanRepository.findByUserIdAndOverdriveLoanId(7L, "2056901")).thenReturn(Optional.empty());
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of(99L));

        assertThat(service.resolveLoanBookId("2056901", null, null)).isEqualTo(99L);
    }

    // ── History rows that recorded only an id ────────────────────────────

    private static org.booklore.model.entity.OverDriveAuditEntity auditRow(String titleId, String title) {
        org.booklore.model.entity.OverDriveAuditEntity row = new org.booklore.model.entity.OverDriveAuditEntity();
        row.setId(1L);
        row.setAction("HOLD_PLACED");
        row.setTitleId(titleId);
        row.setTitle(title);
        return row;
    }

    @Test
    void historyNamesATitleThatWasRecordedAsABareId() {
        authAs(7L);
        when(auditRepository.findByUserIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(auditRow("2056901", null))));
        when(bookRepository.findOverdriveIdTitlePairs(java.util.Set.of("2056901")))
                .thenReturn(List.<Object[]>of(new Object[]{"2056901", "Mockingjay"}));

        var page = service.listHistory(0, 25);

        // Holds and failed borrows were written with no title, leaving the table showing a number.
        // Those rows can't be rewritten, but an id is an id — the library knows what it is called.
        assertThat(page.entries()).singleElement()
                .extracting(org.booklore.model.dto.overdrive.OverDriveAuditEntry::title)
                .isEqualTo("Mockingjay");
    }

    @Test
    void historyKeepsTheRecordedTitleAndDoesNotLookItUp() {
        authAs(7L);
        when(auditRepository.findByUserIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(auditRow("2056901", "As Recorded"))));

        var page = service.listHistory(0, 25);

        // What the row said at the time wins — a book since renamed in the library must not rewrite
        // history — and a page of named rows costs no query at all.
        assertThat(page.entries().getFirst().title()).isEqualTo("As Recorded");
        verify(bookRepository, never()).findOverdriveIdTitlePairs(any());
    }

    @Test
    void historyLeavesTheIdShowingWhenTheLibraryCannotNameIt() {
        authAs(7L);
        when(auditRepository.findByUserIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(auditRow("2056901", null))));
        when(bookRepository.findOverdriveIdTitlePairs(java.util.Set.of("2056901"))).thenReturn(List.of());

        var page = service.listHistory(0, 25);

        // A hold on something never borrowed has no book to name it. The row keeps its id, and the UI
        // still links it to Libby.
        assertThat(page.entries().getFirst().title()).isNull();
        assertThat(page.entries().getFirst().titleId()).isEqualTo("2056901");
    }

    @Test
    void aCardListSaysWhichCeilingHasStoppedACard() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").cardName("MCPL").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(cardLimitRepository.findByIdentityIn(java.util.Set.of("card-a"))).thenReturn(List.of(
                org.booklore.model.entity.OverDriveCardLimitEntity.builder()
                        .identity("card-a").maxPerMonth(100).build()));
        borrowCounts("card-a", 100);

        var card = service.listCards().getFirst();

        // Both meters can show room on a card that has stopped borrowing, so the list has to say why.
        assertThat(card.borrowLimitReached()).isEqualTo("100 of 100 allowed this 30 days");
        assertThat(card.churnCooldownUntil()).isNull();
    }

    @Test
    void aRestingCardReportsTheRestRatherThanACeiling() {
        authAs(7L);
        Instant until = Instant.now().plus(java.time.Duration.ofDays(5));
        OverDriveTokenEntity row = OverDriveTokenEntity.builder()
                .userId(7L).identity("card-a").cardName("MCPL").build();
        row.setChurnCooldownUntil(until);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(row));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(cardLimitRepository.findByIdentityIn(java.util.Set.of("card-a"))).thenReturn(List.of());

        var card = service.listCards().getFirst();

        // OverDrive's own refusal outranks a ceiling we set, and saying both would say it twice — so
        // the ceiling is not even measured.
        assertThat(card.churnCooldownUntil()).isEqualTo(until.toString());
        assertThat(card.borrowLimitReached()).isNull();
        verifyNoInteractions(auditRepository);
    }

    @Test
    void aCardWithRoomLeftReportsNoObstacle() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").cardName("MCPL").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(cardLimitRepository.findByIdentityIn(java.util.Set.of("card-a"))).thenReturn(List.of());
        borrowCounts("card-a", 0);

        var card = service.listCards().getFirst();

        assertThat(card.churnCooldownUntil()).isNull();
        assertThat(card.borrowLimitReached()).isNull();
    }

    // ── The bookbag ──────────────────────────────────────────────────────

    private static org.booklore.model.entity.OverDriveBookbagEntity bagged(long id, String titleId, int position) {
        return org.booklore.model.entity.OverDriveBookbagEntity.builder()
                .id(id).userId(7L).titleId(titleId).title("Title " + titleId).position(position).build();
    }

    @Test
    void queueingATitleAlreadyInTheBagChangesNothing() {
        authAs(7L);
        var existing = bagged(1L, "2056901", 3);
        when(bookbagRepository.findByUserIdAndTitleId(7L, "2056901")).thenReturn(Optional.of(existing));

        var entry = service.addToBookbag("2056901", "Dune", "Frank Herbert", false);

        // Pressing the button twice means "I want this", not "move it to the back".
        assertThat(entry.position()).isEqualTo(3);
        verify(bookbagRepository, never()).save(any());
    }

    @Test
    void aNewTitleGoesToTheBackOfTheBag() {
        authAs(7L);
        when(bookbagRepository.findByUserIdAndTitleId(7L, "2056901")).thenReturn(Optional.empty());
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L))
                .thenReturn(List.of(bagged(1L, "aaa", 1), bagged(2L, "bbb", 4)));
        when(bookbagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.addToBookbag("2056901", "Dune", "Frank Herbert", false).position()).isEqualTo(5);
    }

    @Test
    void queueingToTheFrontJumpsAheadOfEverythingAlreadyThere() {
        authAs(7L);
        when(bookbagRepository.findByUserIdAndTitleId(7L, "2056901")).thenReturn(Optional.empty());
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L))
                .thenReturn(List.of(bagged(1L, "aaa", 1), bagged(2L, "bbb", 4)));
        when(bookbagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Positions are only ever compared, never counted, so going below the current minimum is fine.
        assertThat(service.addToBookbag("2056901", "Dune", null, true).position()).isEqualTo(0);
    }

    @Test
    void askingForTheFrontMovesATitleAlreadyQueued() {
        authAs(7L);
        var existing = bagged(1L, "2056901", 3);
        when(bookbagRepository.findByUserIdAndTitleId(7L, "2056901")).thenReturn(Optional.of(existing));
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L))
                .thenReturn(List.of(bagged(9L, "aaa", 1), existing));

        service.addToBookbag("2056901", "Dune", null, true);

        // "Put this first" is an instruction about order, where a plain add is not — so unlike a plain
        // add, this one does move an entry that is already queued.
        assertThat(existing.getPosition()).isEqualTo(0);
        verify(bookbagRepository).save(existing);
    }

    @Test
    void borrowingNowFromSomeoneElsesBagIsNotFound() {
        authAs(7L);
        var theirs = org.booklore.model.entity.OverDriveBookbagEntity.builder()
                .id(9L).userId(8L).titleId("2056901").build();
        when(bookbagRepository.findById(9L)).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.borrowBookbagEntryNow(9L)).hasMessageContaining("not in your bookbag");
    }

    @Test
    void borrowingNowSaysWhyWhenNothingCanLendIt() {
        authAsAdmin(7L);
        var entry = bagged(1L, "2056901", 1);
        when(bookbagRepository.findById(1L)).thenReturn(Optional.of(entry));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").libraryKey("lapl").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(overDriveParser.fetchAvailabilityBulk(any(), any())).thenReturn(Map.of());

        // The impatient path still reports the real obstacle rather than failing vaguely.
        assertThatThrownBy(() -> service.borrowBookbagEntryNow(1L))
                .hasMessageContaining("None of your libraries carry this title");
        verify(bookbagRepository, never()).delete(any());
    }

    @Test
    void removingSomeoneElsesBookbagEntryIsNotFound() {
        authAs(7L);
        var theirs = org.booklore.model.entity.OverDriveBookbagEntity.builder()
                .id(9L).userId(8L).titleId("2056901").build();
        when(bookbagRepository.findById(9L)).thenReturn(Optional.of(theirs));

        // The bag is personal; another user's entry must not even be acknowledged as existing.
        assertThatThrownBy(() -> service.removeFromBookbag(9L)).hasMessageContaining("not in your bookbag");
        verify(bookbagRepository, never()).delete(any());
    }

    @Test
    void reorderingPutsUnmentionedEntriesBehindTheOnesGiven() {
        authAs(7L);
        var a = bagged(1L, "aaa", 1);
        var b = bagged(2L, "bbb", 2);
        var c = bagged(3L, "ccc", 3);
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L)).thenReturn(List.of(a, b, c));

        service.reorderBookbag(List.of(3L, 1L));

        // A client that sends a partial order must not silently drop what it left out.
        assertThat(c.getPosition()).isEqualTo(1);
        assertThat(a.getPosition()).isEqualTo(2);
        assertThat(b.getPosition()).isEqualTo(3);
    }

    @Test
    void aBookbagTitleAlreadyInTheLibraryIsDroppedRatherThanBorrowedAgain() {
        authAsAdmin(7L);
        var entry = bagged(1L, "2056901", 1);
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L)).thenReturn(List.of(entry));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").libraryKey("lapl").token("chip-1").build(),
                OverDriveTokenEntity.builder().userId(7L).identity("card-b").libraryKey("bpl").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(overDriveParser.fetchAvailabilityBulk(any(), any())).thenReturn(Map.of());
        // The user got it another way, or a hold we placed came in and was imported.
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of(42L));

        var outcome = service.runBookbag(7L, Set.of(), new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), pacer());

        assertThat(outcome).isEqualTo(new OverDriveService.BookbagOutcome(0, 0, 0));
        verify(bookbagRepository).delete(entry);
    }

    @Test
    void anEmptyBagCostsNothing() {
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L)).thenReturn(List.of());

        assertThat(service.runBookbag(7L, Set.of(), new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), pacer()))
                .isEqualTo(new OverDriveService.BookbagOutcome(0, 0, 0));
        verifyNoInteractions(overDriveParser, tokenRepository);
    }

    @Test
    void aBagIsWorkedEvenWithEveryAutomationSwitchOff() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(bookbagRepository.countByUserId(7L)).thenReturn(1L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());

        service.runAutoSync();

        // Queueing a title is itself an instruction to borrow it, so the pass must not short-circuit
        // on the switches — it gets as far as looking for cards.
        verify(tokenRepository).findByUserId(7L);
    }

    @Test
    void aUserWhoseOnlyIntentIsAFullBagIsStillPolled() {
        when(autoSyncRepository.findAllOptedIn()).thenReturn(List.of());
        when(bookbagRepository.findDistinctUserIds()).thenReturn(List.of(11L));

        assertThat(service.autoSyncOptedInUserIds()).containsExactly(11L);
    }

    @Test
    void theWorkListDoesNotRepeatAUserWhoBothOptedInAndHasABag() {
        when(autoSyncRepository.findAllOptedIn()).thenReturn(List.of(
                org.booklore.model.entity.OverDriveAutoSyncEntity.builder().userId(11L).autoImportLoans(true).build()));
        when(bookbagRepository.findDistinctUserIds()).thenReturn(List.of(11L));

        assertThat(service.autoSyncOptedInUserIds()).containsExactly(11L);
    }

    // ── Learning and enforcing a card's borrow rate ──────────────────────

    private void limits(String identity, Integer perMinute, Integer perHour, Integer perDay, Integer perWeek) {
        limits(identity, perMinute, perHour, perDay, perWeek, null);
    }

    private void limits(String identity, Integer perMinute, Integer perHour, Integer perDay,
                        Integer perWeek, Integer perMonth) {
        when(cardLimitRepository.findById(identity)).thenReturn(Optional.of(
                org.booklore.model.entity.OverDriveCardLimitEntity.builder()
                        .identity(identity).maxPerMinute(perMinute).maxPerHour(perHour)
                        .maxPerDay(perDay).maxPerWeek(perWeek).maxPerMonth(perMonth).build()));
    }

    @Test
    void anUnconfiguredCardFallsBackToTheDefaultCeilings() {
        notResting("card-a");
        when(cardLimitRepository.findById("card-a")).thenReturn(Optional.empty());
        borrowCounts("card-a", 0);

        // Unmeasured is not the same as known to be free, and the cost of guessing high is an account
        // refused for days — so an untouched card is still bounded.
        assertThat(service.defaultBorrowLimits().perMonth()).isEqualTo(100);
        assertThat(service.borrowBlockedReason("card-a")).isNull();
    }

    @Test
    void anUnconfiguredCardIsBlockedOnceItPassesADefault() {
        notResting("card-a");
        when(cardLimitRepository.findById("card-a")).thenReturn(Optional.empty());
        borrowCounts("card-a", 2);

        // Two in a minute is the default burst guard — the wall a runaway loop should hit early.
        assertThat(service.borrowBlockedReason("card-a")).contains("this minute");
    }

    @Test
    void anExplicitlyBlankLimitMeansNoCeiling_notTheDefault() {
        notResting("card-a");
        limits("card-a", null, null, null, null, null);

        // Saving a row of blanks is how an administrator opts a card out; it must not silently
        // reinstate the defaults it was set to override.
        assertThat(service.borrowBlockedReason("card-a")).isNull();
        verifyNoInteractions(auditRepository);
    }

    @Test
    void aCardAtItsCeilingIsBlocked_namingTheWindow() {
        notResting("card-a");
        limits("card-a", null, 5, null, null);
        borrowCounts("card-a", 5);

        assertThat(service.borrowBlockedReason("card-a")).isEqualTo("this card has already borrowed 5 of 5 allowed this hour");
    }

    @Test
    void theShortestFullWindowIsTheOneReported() {
        notResting("card-a");
        limits("card-a", 2, 5, null, null);
        borrowCounts("card-a", 9);

        // Both are full; the minute is the one that clears soonest, so it is the useful thing to say.
        assertThat(service.borrowBlockedReason("card-a")).contains("this minute");
    }

    @Test
    void restingOutranksACeilingWeSetOurselves() {
        Instant until = Instant.now().plus(java.time.Duration.ofDays(3));
        when(tokenRepository.findByIdentity("card-a")).thenReturn(List.of(restingCard("card-a", until)));

        // OverDrive's own refusal is the more important fact, and the ceiling is not even measured.
        assertThat(service.borrowBlockedReason("card-a")).contains("too many titles borrowed and returned");
        verifyNoInteractions(auditRepository, cardLimitRepository);
    }

    @Test
    void aCardUnderItsCeilingCanStillBorrow() {
        notResting("card-a");
        limits("card-a", null, 5, null, null);
        borrowCounts("card-a", 4);

        assertThat(service.borrowBlockedReason("card-a")).isNull();
    }

    @Test
    void aMonthlyCeilingIsEnforcedWhenNoShorterWindowIsFull() {
        notResting("card-a");
        limits("card-a", null, null, null, null, 145);
        borrowCounts("card-a", 145);

        // The window this deployment's refusals actually correlate with: a card can be well inside
        // every shorter limit and still have crossed a monthly cap.
        assertThat(service.borrowBlockedReason("card-a"))
                .isEqualTo("this card has already borrowed 145 of 145 allowed this 30 days");
    }

    @Test
    void aShorterFullWindowStillOutranksTheMonthlyOne() {
        notResting("card-a");
        limits("card-a", null, 6, null, null, 145);
        borrowCounts("card-a", 200);

        // Both are full; the hour clears first, so it is the one worth telling the caller about.
        assertThat(service.borrowBlockedReason("card-a")).contains("this hour");
    }

    @Test
    void aLimitOfZeroIsRefusedRatherThanSilentlyGroundingTheCard() {
        authAsCardManager(7L);

        assertThatThrownBy(() -> service.setCardBorrowLimits("card-a",
                new OverDriveService.BorrowLimits(null, 0, null, null, null)))
                .hasMessageContaining("would stop this card borrowing altogether");
        verify(cardLimitRepository, never()).save(any());
    }

    @Test
    void settingLimitsNeedsCardAdministration() {
        authAsOverdriveUser(7L);

        // The ceilings are policy on a shared library account, not a personal preference.
        assertThatThrownBy(() -> service.setCardBorrowLimits("card-a",
                new OverDriveService.BorrowLimits(null, 5, null, null, null)))
                .hasMessageContaining("Only an administrator");
        assertThatThrownBy(() -> service.listCardBorrowBudgets())
                .hasMessageContaining("Only an administrator");
    }

    @Test
    void clearingALimitIsAllowed() {
        authAsCardManager(7L);
        when(cardLimitRepository.findById("card-a")).thenReturn(Optional.empty());

        service.setCardBorrowLimits("card-a", new OverDriveService.BorrowLimits(null, null, null, null, null));

        ArgumentCaptor<org.booklore.model.entity.OverDriveCardLimitEntity> saved =
                ArgumentCaptor.forClass(org.booklore.model.entity.OverDriveCardLimitEntity.class);
        verify(cardLimitRepository).save(saved.capture());
        // Null is "no ceiling known", which is a legitimate state to return a card to.
        assertThat(saved.getValue().getMaxPerHour()).isNull();
    }

    // ── Resting a card OverDrive flagged for churning ────────────────────

    /** The body Thunder returns when an account has borrowed and returned too much. */
    private static final String CHURN_BODY = "400 Bad Request: \"{\"result\":\"upstream_failure\",\"upstream\":"
            + "{\"errorCode\":\"PatronExceededChurningLimit\",\"service\":\"THUNDER\",\"httpStatus\":400,"
            + "\"userExplanation\":\"There have been too many titles borrowed and returned from your account "
            + "within a short period of time.\"}}\"";

    @Test
    void theChurningLimitIsRecognisedThroughAWrappedFailure() {
        // Call sites wrap the upstream body in their own exception, so the cause chain and the message
        // both have to be searched — same shape as the missing-chip detector.
        assertThat(OverDriveService.isChurningLimit(new RuntimeException(CHURN_BODY))).isTrue();
        assertThat(OverDriveService.isChurningLimit(
                new IllegalStateException("import failed", new RuntimeException(CHURN_BODY)))).isTrue();
    }

    @Test
    void anOrdinaryFailureIsNotMistakenForTheChurningLimit() {
        assertThat(OverDriveService.isChurningLimit(new RuntimeException("400 Bad Request: title not found"))).isFalse();
        assertThat(OverDriveService.isChurningLimit(new RuntimeException("missing_chip"))).isFalse();
        assertThat(OverDriveService.isChurningLimit(null)).isFalse();
    }

    /** Stub this card's borrow counts; the same number is reported for every window. */
    private void borrowCounts(String identity, long perWindow) {
        when(auditRepository.countBorrowsPerWindow(org.mockito.ArgumentMatchers.eq(identity),
                any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{perWindow, perWindow, perWindow, perWindow, perWindow}));
    }

    /** A card nobody has rested. */
    private void notResting(String identity) {
        when(tokenRepository.findByIdentity(identity)).thenReturn(List.of());
    }

    private OverDriveTokenEntity restingCard(String identity, Instant until) {
        OverDriveTokenEntity row = OverDriveTokenEntity.builder()
                .userId(7L).identity(identity).token("chip-1").build();
        row.setChurnCooldownUntil(until);
        return row;
    }

    @Test
    void aRestingCardRefusesToReturnATitle() {
        Instant until = Instant.now().plus(java.time.Duration.ofDays(5));
        when(tokenRepository.findByIdentity("card-a")).thenReturn(List.of(restingCard("card-a", until)));

        // An early return is the other half of the cycle OverDrive objected to, so it stops as well —
        // and the refusal says when the card frees up rather than just failing.
        assertThatThrownBy(() -> service.returnBook("card-a", "loan-1"))
                .hasMessageContaining("too many titles borrowed and returned")
                .hasMessageContaining(until.toString());
        verifyNoInteractions(restClient);
    }

    @Test
    void aRestingCardIsNeverChosenToBorrowWith() {
        when(tokenRepository.findByIdentity("card-b"))
                .thenReturn(List.of(restingCard("card-b", Instant.now().plus(java.time.Duration.ofDays(5)))));
        notResting("card-c");
        when(cardLimitRepository.findById(any())).thenReturn(Optional.empty());
        when(auditRepository.countBorrowsPerWindow(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L, 0L}));

        // bpl has far more copies, but its card is resting after a churning limit — every path that
        // takes a copy out goes through here, including the immediate one behind Borrow now.
        assertThat(service.borrowableCardFor(List.of(
                        availability("bpl", true, false, null, 20),
                        availability("kcpl", true, false, null, 2)),
                threeLibraries(), Map.of())).isEqualTo("card-c");
    }

    @Test
    void aCooldownThatHasRunOutStopsBlockingTheCard() {
        authAs(7L);
        when(tokenRepository.findByIdentity("card-a"))
                .thenReturn(List.of(restingCard("card-a", Instant.now().minusSeconds(60))));

        // Past its end the card is simply free again — no unsetting step to forget.
        assertThatThrownBy(() -> service.returnBook("card-a", "loan-1"))
                .hasMessageNotContaining("too many titles borrowed and returned");
    }

    // ── Telling the user when a loan goes back ───────────────────────────

    private OverDriveLoanEntity fulfilledLoan(String loanId, java.time.Instant borrowedAt) {
        OverDriveLoanEntity row = loanRow(loanId, "card-a", "BORROWED");
        row.setFulfilled(true);
        row.setCreatedAt(borrowedAt);
        return row;
    }

    private void optedIntoAutoReturn(int minAgeDays, int windowHours) {
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                org.booklore.model.entity.OverDriveAutoSyncEntity.builder()
                        .userId(7L).autoReturnEnabled(true)
                        .autoReturnMinAgeDays(minAgeDays).autoReturnMaxDelayHours(windowHours).build()));
    }

    @Test
    void theScheduleReportsTheExactTimeOnceThePollerHasDrawnIt() {
        authAs(7L);
        optedIntoAutoReturn(14, 48);
        var borrowedAt = Instant.parse("2026-08-01T10:00:00Z");
        var drawn = Instant.parse("2026-08-16T03:20:00Z");
        OverDriveLoanEntity loan = fulfilledLoan("loan-1", borrowedAt);
        loan.setAutoReturnDueAt(drawn);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(loan));

        var schedule = service.autoReturnSchedule().get("loan-1");

        assertThat(schedule.dueAt()).isEqualTo(drawn.toString());
        assertThat(schedule.earliestAt()).isEqualTo(borrowedAt.plus(14, java.time.temporal.ChronoUnit.DAYS).toString());
    }

    @Test
    void theScheduleGivesOnlyTheEarliestDateUntilTheTimeIsDrawn_andDoesNotDrawIt() {
        authAs(7L);
        optedIntoAutoReturn(14, 48);
        var borrowedAt = Instant.parse("2026-08-01T10:00:00Z");
        OverDriveLoanEntity loan = fulfilledLoan("loan-1", borrowedAt);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(loan));

        var schedule = service.autoReturnSchedule().get("loan-1");

        // Showing a page must not decide when a book goes back: drawing here would move that choice
        // out of the pass that owns it, and fix it earlier than the user's window intends.
        assertThat(schedule.dueAt()).isNull();
        assertThat(schedule.windowHours()).isEqualTo(48);
        assertThat(loan.getAutoReturnDueAt()).isNull();
        verify(loanRepository, never()).save(any());
    }

    @Test
    void nothingIsScheduledWhenTheUserHasAutoReturnSwitchedOff() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.empty());

        // Better to show no date at all than one that will never arrive.
        assertThat(service.autoReturnSchedule()).isEmpty();
        verifyNoInteractions(loanRepository);
    }

    @Test
    void aLoanGrimmoryNeverDownloadedIsNotScheduledForReturn() {
        authAs(7L);
        optedIntoAutoReturn(14, 48);
        OverDriveLoanEntity unfulfilled = loanRow("loan-1", "card-a", "BORROWED");
        unfulfilled.setFulfilled(false);
        unfulfilled.setCreatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(unfulfilled));

        // Auto-return leaves it alone, so promising a date would be a lie.
        assertThat(service.autoReturnSchedule()).isEmpty();
    }

    @Test
    void adoptingHoldsQueuesEachHeldTitleOnce_oldestFirst() {
        authAsAdmin(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        stubCard(7L, "card-a", "chip-1");
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L)).thenReturn(List.of());

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        var newer = waitingHold("111", "card-a", "30");
        newer.setPlacedDate("2026-08-20T10:00:00Z");
        var older = waitingHold("222", "card-a", "40");
        older.setPlacedDate("2026-07-01T10:00:00Z");
        // The same title held at two libraries is one book, not two entries.
        var duplicate = waitingHold("222", "card-b", "40");
        duplicate.setPlacedDate("2026-07-15T10:00:00Z");
        body.setHolds(List.of(newer, older, duplicate));

        int added = syncHarness(body).service().adoptHoldsIntoBookbag();

        assertThat(added).isEqualTo(2);
        ArgumentCaptor<List<org.booklore.model.entity.OverDriveBookbagEntity>> saved =
                ArgumentCaptor.forClass(List.class);
        verify(bookbagRepository).saveAll(saved.capture());
        // Oldest hold first: it is the one waited longest for.
        assertThat(saved.getValue()).extracting(org.booklore.model.entity.OverDriveBookbagEntity::getTitleId)
                .containsExactly("222", "111");
        // Adopted, not re-placed — the existing queue position is kept.
        assertThat(saved.getValue().getFirst().getHoldCardId()).isEqualTo("card-a");
    }

    @Test
    void adoptingHoldsSkipsTitlesAlreadyQueued() {
        authAsAdmin(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        stubCard(7L, "card-a", "chip-1");
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L))
                .thenReturn(List.of(bagged(1L, "111", 1)));

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        body.setHolds(List.of(waitingHold("111", "card-a", "30")));

        assertThat(syncHarness(body).service().adoptHoldsIntoBookbag()).isZero();
        verify(bookbagRepository, never()).saveAll(any());
    }

    @Test
    void aBookbagEntryWhoseHoldHasGoneQueuesAgain() {
        authAsAdmin(7L);
        var entry = bagged(1L, "2056901", 1);
        entry.setHoldCardId("card-a");
        entry.setHoldPlacedAt(Instant.now().minusSeconds(86_400));
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L)).thenReturn(List.of(entry));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").libraryKey("lapl").token("chip-1").build(),
                OverDriveTokenEntity.builder().userId(7L).identity("card-b").libraryKey("bpl").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(overDriveParser.fetchAvailabilityBulk(any(), any())).thenReturn(Map.of());
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        // The user holds nothing: the hold was cancelled by hand, or lapsed unclaimed.
        service.runBookbag(7L, Set.of(), new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), pacer());

        // Forgetting it lets the entry queue again, rather than waiting forever on a hold that has
        // stopped existing.
        assertThat(entry.getHoldCardId()).isNull();
        assertThat(entry.getHoldPlacedAt()).isNull();
    }

    @Test
    void aBookbagEntryWaitsWhileItsHoldIsStillLive() {
        authAsAdmin(7L);
        var entry = bagged(1L, "2056901", 1);
        entry.setHoldCardId("card-a");
        when(bookbagRepository.findByUserIdOrderByPositionAscIdAsc(7L)).thenReturn(List.of(entry));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").libraryKey("lapl").token("chip-1").build(),
                OverDriveTokenEntity.builder().userId(7L).identity("card-b").libraryKey("bpl").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());
        when(overDriveParser.fetchAvailabilityBulk(any(), any())).thenReturn(Map.of());
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        service.runBookbag(7L, Set.of("2056901"), new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), pacer());

        assertThat(entry.getHoldCardId()).isEqualTo("card-a");
        assertThat(entry.getLastNote()).contains("Waiting on the hold");
    }

    // ── Checking a waiting hold against the user's other libraries ───────

    private static org.booklore.model.dto.overdrive.OverDriveHold waitingHold(
            String titleId, String cardId, String waitDays) {
        org.booklore.model.dto.overdrive.OverDriveHold hold = new org.booklore.model.dto.overdrive.OverDriveHold();
        hold.setId(titleId);
        hold.setCardId(cardId);
        hold.setTitle("Dune");
        hold.setAvailable(false);
        hold.setEstimatedWaitDays(waitDays);
        return hold;
    }

    private static org.booklore.model.dto.overdrive.OverDriveLibraryAvailability availability(
            String libraryKey, boolean available, boolean holdable, Integer waitDays, Integer ownedCopies) {
        return new org.booklore.model.dto.overdrive.OverDriveLibraryAvailability(
                libraryKey, available, holdable, available ? 1 : 0, ownedCopies, null, waitDays, 0);
    }

    /** The user's other libraries: lapl is the card holding the hold, bpl and kcpl are alternatives. */
    private static Map<String, String> threeLibraries() {
        return new java.util.LinkedHashMap<>(Map.of("lapl", "card-a", "bpl", "card-b", "kcpl", "card-c"));
    }

    private static OverDriveAutoSyncSettings holdShopping(boolean autoBorrow) {
        return new OverDriveAutoSyncSettings(false, autoBorrow, false, 14, 48, true, true);
    }

    @Test
    void anyShorterQueueIsWorthMovingTo_matchingTheHoldsTab() {
        var hold = waitingHold("2056901", "card-a", "30");

        // The same rule the button applies: shorter is better, with no threshold of its own to
        // disagree about.
        assertThat(service.soonerElsewhere(hold, List.of(availability("bpl", false, true, 29, 5)),
                threeLibraries(), Map.of(), Map.of()))
                .isEqualTo(new OverDriveService.SoonerQueue("card-b", 29, 30));
        // Equal is not shorter.
        assertThat(service.soonerElsewhere(hold, List.of(availability("bpl", false, true, 30, 5)),
                threeLibraries(), Map.of(), Map.of())).isNull();
        assertThat(service.soonerElsewhere(hold, List.of(availability("bpl", false, true, 31, 5)),
                threeLibraries(), Map.of(), Map.of())).isNull();
    }

    @Test
    void theShortestQueueWins_thenMoreCopies_thenTheCardCarryingFewestHolds() {
        var hold = waitingHold("2056901", "card-a", "60");
        assertThat(service.soonerElsewhere(hold, List.of(
                        availability("bpl", false, true, 20, 3),
                        availability("kcpl", false, true, 10, 1)),
                threeLibraries(), Map.of(), Map.of()).cardId()).isEqualTo("card-c");

        // Equal waits: the bigger pool churns faster and gains more from holds ahead lapsing.
        assertThat(service.soonerElsewhere(hold, List.of(
                        availability("bpl", false, true, 20, 12),
                        availability("kcpl", false, true, 20, 2)),
                threeLibraries(), Map.of(), Map.of()).cardId()).isEqualTo("card-b");

        // Equal on both: spread the holds, so no single card fills its slots. Same last tie-break the
        // Holds tab uses.
        assertThat(service.soonerElsewhere(hold, List.of(
                        availability("bpl", false, true, 20, 5),
                        availability("kcpl", false, true, 20, 5)),
                threeLibraries(), Map.of(), Map.of("card-b", 9, "card-c", 2)).cardId()).isEqualTo("card-c");
    }

    @Test
    void aHoldWithNoWaitEstimateIsNeverMoved() {
        // Nothing to compare against, so "better" would be a guess — and guessing wrong costs a queue
        // position the user cannot get back.
        assertThat(service.soonerElsewhere(waitingHold("2056901", "card-a", "unknown"),
                List.of(availability("bpl", false, true, 1, 5)), threeLibraries(), Map.of(), Map.of())).isNull();
        assertThat(service.soonerElsewhere(waitingHold("2056901", "card-a", null),
                List.of(availability("bpl", false, true, 1, 5)), threeLibraries(), Map.of(), Map.of())).isNull();
    }

    @Test
    void aHoldIsNeverMovedToTheCardItIsAlreadyOn_norToOneAtItsHoldLimit() {
        var hold = waitingHold("2056901", "card-a", "60");
        // The card's own library reporting a shorter wait is the same queue, not a better one.
        assertThat(service.soonerElsewhere(hold, List.of(availability("lapl", false, true, 5, 5)),
                threeLibraries(), Map.of(), Map.of())).isNull();
        // A card that can take no more holds cannot be moved to.
        assertThat(service.soonerElsewhere(hold, List.of(availability("bpl", false, true, 5, 5)),
                threeLibraries(), Map.of("card-b", 0), Map.of())).isNull();
    }

    @Test
    void borrowingPrefersTheLibraryWithMoreCopiesFree() {
        when(tokenRepository.findByIdentity(any())).thenReturn(List.of());
        when(cardLimitRepository.findById(any())).thenReturn(Optional.empty());
        when(auditRepository.countBorrowsPerWindow(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L, 0L}));

        // Both can lend it. Taking the lone copy at bpl empties that shelf and starts a queue there,
        // while kcpl has four to spare. This preference used to live in the search page, which chose
        // the card before asking the server; the queue moved that choice here.
        String card = service.borrowableCardFor(List.of(
                        availability("bpl", true, false, null, 1),
                        availability("kcpl", true, false, null, 12)),
                threeLibraries(), Map.of());

        assertThat(card).isEqualTo("card-b"); // one available copy each, from availability(...)
    }

    @Test
    void borrowingCountsEveryFreeCopy_regularAndLuckyDay() {
        when(tokenRepository.findByIdentity(any())).thenReturn(List.of());
        when(cardLimitRepository.findById(any())).thenReturn(Optional.empty());
        when(auditRepository.countBorrowsPerWindow(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L, 0L}));

        var oneRegular = new org.booklore.model.dto.overdrive.OverDriveLibraryAvailability(
                "bpl", true, false, 1, 4, null, null, 0);
        var oneRegularPlusThreeLucky = new org.booklore.model.dto.overdrive.OverDriveLibraryAvailability(
                "kcpl", true, false, 1, 4, null, null, 3);

        // Lucky Day copies are borrowable now without a hold, so they are part of the shelf.
        assertThat(service.borrowableCardFor(List.of(oneRegular, oneRegularPlusThreeLucky),
                threeLibraries(), Map.of())).isEqualTo("card-c");
    }

    @Test
    void borrowingSkipsACardThatIsFullOrOutOfBudget() {
        when(tokenRepository.findByIdentity(any())).thenReturn(List.of());
        when(cardLimitRepository.findById(any())).thenReturn(Optional.empty());
        when(auditRepository.countBorrowsPerWindow(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L, 0L}));
        var plenty = new org.booklore.model.dto.overdrive.OverDriveLibraryAvailability(
                "bpl", true, false, 9, 20, null, null, 0);
        var few = new org.booklore.model.dto.overdrive.OverDriveLibraryAvailability(
                "kcpl", true, false, 1, 2, null, null, 0);

        // bpl has far more copies, but its card cannot take another checkout — so the scarcer library
        // is used rather than nothing at all.
        assertThat(service.borrowableCardFor(List.of(plenty, few), threeLibraries(), Map.of("card-b", 0)))
                .isEqualTo("card-c");
    }

    @Test
    void aLibraryThatOnlyOffersAWaitlistIsNotTreatedAsHavingTheBookOnTheShelf() {
        var hold = waitingHold("2056901", "card-a", "60");
        assertThat(service.availableElsewhere(hold, List.of(availability("bpl", false, true, 5, 5)),
                threeLibraries(), Map.of())).isNull();
        assertThat(service.availableElsewhere(hold, List.of(availability("bpl", true, false, null, 5)),
                threeLibraries(), Map.of())).isEqualTo("card-b");
    }

    @Test
    void aLuckyDayCopyCountsAsOnTheShelf_butStillNeedsACheckoutSlot() {
        var hold = waitingHold("2056901", "card-a", "60");
        var luckyDay = new org.booklore.model.dto.overdrive.OverDriveLibraryAvailability(
                "bpl", false, true, 0, 5, null, 60, 2);

        // It skips the queue, so it is borrowable now — but it is still a loan.
        assertThat(service.availableElsewhere(hold, List.of(luckyDay), threeLibraries(), Map.of()))
                .isEqualTo("card-b");
        assertThat(service.availableElsewhere(hold, List.of(luckyDay), threeLibraries(), Map.of("card-b", 0)))
                .isNull();
    }

    @Test
    void holdShoppingDoesNothingWithoutASecondLibrary() {
        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        body.setHolds(List.of(waitingHold("2056901", "card-a", "30")));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-a").libraryKey("lapl").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());

        var outcome = service.runHoldShopping(7L, holdShopping(false),
                Map.of("card-a", body), new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), pacer());

        // One library is nowhere else to look; don't spend an availability call finding that out.
        assertThat(outcome).isEqualTo(new OverDriveService.HoldShoppingOutcome(0, 0, 0));
        verifyNoInteractions(overDriveParser);
    }

    @Test
    void holdShoppingIgnoresAHoldThatIsAlreadyReadyToBorrow() {
        OverDriveSyncResponse body = syncCovering(List.of("card-a", "card-b"));
        var ready = waitingHold("2056901", "card-a", "0");
        ready.setAvailable(true);
        body.setHolds(List.of(ready));

        var outcome = service.runHoldShopping(7L, holdShopping(false),
                Map.of("card-a", body, "card-b", body), new java.util.HashMap<>(), new java.util.HashMap<>(),
                new java.util.HashMap<>(), pacer());

        // A ready hold is auto-borrow's business. Shopping it around would cancel a copy already won —
        // and the card lookup is never even reached.
        assertThat(outcome).isEqualTo(new OverDriveService.HoldShoppingOutcome(0, 0, 0));
        verifyNoInteractions(overDriveParser, tokenRepository);
    }

    // ── Keeping the loan cache honest ────────────────────────────────────

    /** A sync response covering {@code cards}, listing {@code loanIds} as still held. */
    private static OverDriveSyncResponse syncCovering(List<String> cards, String... loanIds) {
        OverDriveSyncResponse body = new OverDriveSyncResponse();
        body.setCards(cards.stream().map(id -> {
            OverDriveSyncResponse.Card c = new OverDriveSyncResponse.Card();
            c.setCardId(id);
            return c;
        }).toList());
        body.setLoans(java.util.Arrays.stream(loanIds).map(id -> {
            OverDriveLoan loan = new OverDriveLoan();
            loan.setId(id);
            loan.setCardId(cards.getFirst());
            return loan;
        }).toList());
        return body;
    }

    private static OverDriveLoanEntity loanRow(String loanId, String identity, String state) {
        OverDriveLoanEntity row = new OverDriveLoanEntity();
        row.setOverdriveLoanId(loanId);
        row.setIdentity(identity);
        row.setUserId(7L);
        row.setState(state);
        return row;
    }

    @Test
    void syncMarksLoansTheUserNoLongerHoldsAsReturned() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        OverDriveLoanEntity stillHeld = loanRow("loan-1", "card-a", "BORROWED");
        OverDriveLoanEntity handedBack = loanRow("loan-2", "card-a", "BORROWED");
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(stillHeld, handedBack));
        when(loanRepository.findByUserIdAndOverdriveLoanId(any(), any())).thenReturn(Optional.empty());

        syncHarness(syncCovering(List.of("card-a"), "loan-1")).service().sync("card-a");

        // Returned in the Libby app: the feed simply stops listing it, and the row has to follow.
        assertThat(handedBack.getState()).isEqualTo("RETURNED");
        assertThat(handedBack.getLastSync()).isNotNull();
        assertThat(stillHeld.getState()).isEqualTo("BORROWED");
    }

    @Test
    void syncRecordsALoanGonePastItsExpiryAsExpiredRatherThanReturned() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        OverDriveLoanEntity lapsed = loanRow("loan-2", "card-a", "BORROWED");
        lapsed.setExpireDate(Instant.now().minusSeconds(3600));
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(lapsed));

        syncHarness(syncCovering(List.of("card-a"))).service().sync("card-a");

        // OverDrive doesn't say why a loan left the feed; its own expiry date is the only evidence.
        assertThat(lapsed.getState()).isEqualTo("EXPIRED");
    }

    @Test
    void syncReconcilesEvenWhenTheFeedCarriesNoLoansField() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        OverDriveLoanEntity last = loanRow("loan-1", "card-a", "BORROWED");
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(last));

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        body.setLoans(null); // returning your last book can leave the field out entirely

        syncHarness(body).service().sync("card-a");

        // Skipping reconciliation on a missing loans field would strand the final row as borrowed
        // forever — exactly the case where there is something to retire.
        assertThat(last.getState()).isEqualTo("RETURNED");
    }

    @Test
    void syncLeavesLoansOnCardsItDidNotCoverAlone() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        OverDriveLoanEntity elsewhere = loanRow("loan-9", "card-z", "BORROWED");
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(elsewhere));

        syncHarness(syncCovering(List.of("card-a"))).service().sync("card-a");

        // card-z is on another chip. Absence from this response is no evidence at all about its loans —
        // marking them returned would be inventing history.
        assertThat(elsewhere.getState()).isEqualTo("BORROWED");
        verify(loanRepository, never()).save(elsewhere);
    }

    @Test
    void syncDoesNotReviveAlreadyReconciledRows() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        OverDriveLoanEntity done = loanRow("loan-2", "card-a", "RETURNED");
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(done));

        syncHarness(syncCovering(List.of("card-a"))).service().sync("card-a");

        // Already terminal: nothing to reconcile, and no write to make on every subsequent poll.
        verify(loanRepository, never()).save(done);
    }

    @Test
    void reborrowingATitleClearsTheStateLeftBehindByTheLastLoan() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        // A loan id is a title id, so the same row comes back when the title is borrowed again.
        OverDriveLoanEntity previous = loanRow("loan-1", "card-a", "RETURNED");
        previous.setFulfilled(true);
        previous.setAutoImportFailures(3);
        previous.setAutoReturnDueAt(Instant.now().minusSeconds(86_400));
        previous.setCreatedAt(Instant.now().minus(java.time.Duration.ofDays(30)));
        when(loanRepository.findByUserIdAndOverdriveLoanId(7L, "loan-1")).thenReturn(Optional.of(previous));
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(previous));

        syncHarness(syncCovering(List.of("card-a"), "loan-1")).service().sync("card-a");

        // Carrying the old checkout's state over would make a book borrowed minutes ago look like one
        // held for a month — instantly due for automatic return, and never imported.
        assertThat(previous.getState()).isEqualTo("BORROWED");
        assertThat(previous.getFulfilled()).isFalse();
        assertThat(previous.getAutoImportFailures()).isZero();
        assertThat(previous.getAutoReturnDueAt()).isNull();
        assertThat(previous.getCreatedAt()).isAfter(Instant.now().minusSeconds(60));
    }

    @Test
    void loanAgeComesFromTheCheckoutDateNotFromWhenTheRowWasWritten() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        when(loanRepository.findByUserIdAndOverdriveLoanId(any(), any())).thenReturn(Optional.empty());

        OverDriveSyncResponse body = new OverDriveSyncResponse();
        OverDriveLoan loan = new OverDriveLoan();
        loan.setId("loan-1");
        loan.setCardId("card-a");
        loan.setCheckoutDate("2026-08-01T10:15:00Z");
        body.setLoans(List.of(loan));

        syncHarness(body).service().sync("card-a");

        // Auto-return measures the minimum age against this. A loan borrowed in Libby a week ago and
        // first seen here today is a week old, not new.
        ArgumentCaptor<OverDriveLoanEntity> saved = ArgumentCaptor.forClass(OverDriveLoanEntity.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(Instant.parse("2026-08-01T10:15:00Z"));
    }

    @Test
    void anUnparseableCheckoutDateLeavesTheExistingAgeAlone() {
        authAs(7L);
        stubCard(7L, "card-a", "chip-1");
        OverDriveLoanEntity existing = loanRow("loan-1", "card-a", "BORROWED");
        Instant borrowedAt = Instant.now().minus(java.time.Duration.ofDays(5));
        existing.setCreatedAt(borrowedAt);
        when(loanRepository.findByUserIdAndOverdriveLoanId(7L, "loan-1")).thenReturn(Optional.of(existing));

        OverDriveSyncResponse body = new OverDriveSyncResponse();
        OverDriveLoan loan = new OverDriveLoan();
        loan.setId("loan-1");
        loan.setCardId("card-a");
        loan.setCheckoutDate("not a date");
        body.setLoans(List.of(loan));

        syncHarness(body).service().sync("card-a");

        // Resetting the age on a garbled field would silently postpone every automatic return.
        assertThat(existing.getCreatedAt()).isEqualTo(borrowedAt);
    }

    // ── Not re-acquiring what the library already has ────────────────────

    /** Opt the user into auto-sync with the given toggles, and give them one linked card. */
    private void optInWithOneCard(long userId, boolean autoImport, boolean autoBorrow) {
        when(autoSyncRepository.findByUserId(userId)).thenReturn(Optional.of(
                org.booklore.model.entity.OverDriveAutoSyncEntity.builder()
                        .userId(userId).autoImportLoans(autoImport).autoBorrowHolds(autoBorrow).build()));
        when(tokenRepository.findByUserId(userId)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(userId).identity("card-a").token("chip-1").build()));
        when(cardShareRepository.findBySharedWithUserId(userId)).thenReturn(List.of());
        stubCard(userId, "card-a", "chip-1");
    }

    @Test
    void autoImportLinksALoanTheLibraryAlreadyHasInsteadOfDownloadingItAgain() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, false);
        OverDriveLoanEntity pending = loanRow("2056901", "card-a", "BORROWED");
        pending.setFulfilled(false);
        pending.setFormatId("ebook-epub-adobe");
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(pending));
        when(loanRepository.findByUserIdAndOverdriveLoanId(7L, "2056901")).thenReturn(Optional.of(pending));
        // The loan id is the title id, and the library already holds that edition.
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of(42L));

        var outcome = syncHarness(syncCovering(List.of("card-a"), "2056901")).service().runAutoSync();

        // Re-importing would spend a handler run and a download to produce a second copy of a file
        // already on disk.
        verifyNoInteractions(overDriveImportService, acsmHandler);
        assertThat(outcome.loansLinked()).isEqualTo(1);
        assertThat(outcome.loansImported()).isZero();
        assertThat(pending.getBookId()).isEqualTo(42L);
        // Marked done so it is not reconsidered every pass — and so it becomes returnable, which is the
        // point: a loan whose book we already have is the one worth handing back.
        assertThat(pending.getFulfilled()).isTrue();
    }

    @Test
    void autoImportStillImportsALoanTheLibraryDoesNotHave() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, false);
        OverDriveLoanEntity pending = loanRow("2056901", "card-a", "BORROWED");
        pending.setFulfilled(false);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(pending));
        when(loanRepository.findByUserIdAndOverdriveLoanId(7L, "2056901")).thenReturn(Optional.of(pending));
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        var outcome = syncHarness(syncCovering(List.of("card-a"), "2056901")).service().runAutoSync();

        // No match, so the import runs as before — and fails here, because this harness stubs no
        // fulfilment. The failure is the proof it was attempted rather than quietly skipped.
        assertThat(outcome.loansLinked()).isZero();
        assertThat(outcome.failures()).isEqualTo(1);
        assertThat(pending.getFulfilled()).isFalse();
    }

    @Test
    void autoImportWillNotLinkOnAnIsbnMatchAlone() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, false);
        OverDriveLoanEntity pending = loanRow("2056901", "card-a", "BORROWED");
        pending.setFulfilled(false);
        pending.setIsbn("9780441013593");
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(pending));
        when(loanRepository.findByUserIdAndOverdriveLoanId(7L, "2056901")).thenReturn(Optional.of(pending));
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        var outcome = syncHarness(syncCovering(List.of("card-a"), "2056901")).service().runAutoSync();

        // Linking marks the loan fulfilled, which makes it returnable. An ISBN is shared across
        // OverDrive editions and with print, so trusting one here could hand back a loan whose file was
        // never downloaded — the ISBN query is not even asked.
        verify(bookRepository, never()).findIdsByIsbn13(any());
        assertThat(outcome.loansLinked()).isZero();
        assertThat(pending.getFulfilled()).isFalse();
    }

    @Test
    void autoBorrowSkipsAReadyHoldWhoseTitleIsAlreadyInTheLibrary() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, true);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of());

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        org.booklore.model.dto.overdrive.OverDriveHold ready = new org.booklore.model.dto.overdrive.OverDriveHold();
        ready.setId("2056901");
        ready.setCardId("card-a");
        ready.setTitle("Dune");
        ready.setAvailable(true);
        body.setHolds(List.of(ready));
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of(42L));

        var outcome = syncHarness(body).service().runAutoSync();

        // A hold placed months ago can come in long after the book arrived by another route; borrowing
        // it anyway burns a checkout slot to fetch a file we already have.
        assertThat(outcome.holdsBorrowed()).isZero();
        assertThat(outcome.failures()).isZero();
        verifyNoInteractions(overDriveImportService, acsmHandler);
    }

    /** Give a sync response a card row reporting {@code count} of {@code limit} checkouts used. */
    private static void withLoanCounts(OverDriveSyncResponse body, String cardId, int count, int limit) {
        OverDriveSyncResponse.Card card = new OverDriveSyncResponse.Card();
        card.setCardId(cardId);
        OverDriveSyncResponse.Counts counts = new OverDriveSyncResponse.Counts();
        counts.setLoan(count);
        card.setCounts(counts);
        OverDriveSyncResponse.Limits limits = new OverDriveSyncResponse.Limits();
        limits.setLoan(limit);
        card.setLimits(limits);
        body.setCards(List.of(card));
    }

    /** A ready-to-borrow hold on {@code cardId}. */
    private static org.booklore.model.dto.overdrive.OverDriveHold readyHold(String titleId, String cardId) {
        org.booklore.model.dto.overdrive.OverDriveHold hold = new org.booklore.model.dto.overdrive.OverDriveHold();
        hold.setId(titleId);
        hold.setCardId(cardId);
        hold.setTitle("Dune");
        hold.setAvailable(true);
        return hold;
    }

    @Test
    void autoBorrowLeavesAReadyHoldAloneWhenTheCardIsAtItsCheckoutLimit() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, true);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of());

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        withLoanCounts(body, "card-a", 10, 10); // full
        body.setHolds(List.of(readyHold("2056901", "card-a")));
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        var outcome = syncHarness(body).service().runAutoSync();

        // Borrowing would be refused upstream. Asking anyway costs a request per ready hold on every
        // poll for as long as the card stays full — and it is a skip, not a failure.
        assertThat(outcome.holdsBorrowed()).isZero();
        assertThat(outcome.failures()).isZero();
    }

    @Test
    void autoBorrowSpendsTheCardsRemainingSlotsAndThenStops() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, true);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of());

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        withLoanCounts(body, "card-a", 9, 10); // one slot left
        body.setHolds(List.of(readyHold("111", "card-a"), readyHold("222", "card-a")));
        when(bookRepository.findIdsByOverdriveId(any())).thenReturn(List.of());

        var outcome = syncHarness(body).service().runAutoSync();

        // The counts are a snapshot from the start of the pass, so each attempt has to be spent locally.
        // Only the first hold is tried (and fails in this harness); the second is skipped for room —
        // and the slot goes on the attempt, since a borrow that fails at import has still taken it.
        assertThat(outcome.failures()).isEqualTo(1);
    }

    @Test
    void autoBorrowTreatsAnUnreportedLimitAsNoLimit() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, true);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of());

        // syncCovering builds card rows with no counts or limits at all.
        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        body.setHolds(List.of(readyHold("2056901", "card-a")));
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        var outcome = syncHarness(body).service().runAutoSync();

        // Refusing to borrow because a library declined to say what its cap is would be worse than
        // trying and being turned down — the borrow is attempted (and fails here for want of a stub).
        assertThat(outcome.failures()).isEqualTo(1);
    }

    @Test
    void autoBorrowStillTakesAReadyHoldTheLibraryDoesNotAlreadyHave() {
        authAsAdmin(7L);
        optInWithOneCard(7L, true, true);
        when(loanRepository.findByUserId(7L)).thenReturn(List.of());

        OverDriveSyncResponse body = syncCovering(List.of("card-a"));
        org.booklore.model.dto.overdrive.OverDriveHold ready = new org.booklore.model.dto.overdrive.OverDriveHold();
        ready.setId("2056901");
        ready.setCardId("card-a");
        ready.setTitle("Dune");
        ready.setAvailable(true);
        body.setHolds(List.of(ready));
        when(bookRepository.findIdsByOverdriveId("2056901")).thenReturn(List.of());

        var outcome = syncHarness(body).service().runAutoSync();

        // The borrow is attempted and fails in this harness (no borrow endpoint stubbed) — which is
        // what distinguishes "skipped because we own it" above from "did nothing either way".
        assertThat(outcome.failures()).isEqualTo(1);
    }

    @Test
    void shareableUsers_excludesSelfAndSortsByName() {
        authAs(7L);
        // The picker is only available to a user who actually owns a card to share.
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(
                OverDriveTokenEntity.builder().userId(7L).identity("card-1").token("t").build()));
        when(userRepository.findAllWithPermissions()).thenReturn(List.of(
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
        verify(userRepository, never()).findAllWithPermissions();
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
                cardLimitRepository, bookbagRepository, importDestinationRepository, autoSyncRepository, httpClient, userRepository, authenticationService, appSettingService,
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

    // ── Sharing a Libby chip between cards ───────────────────────────────

    @Test
    void sharingAChipRequiresTheCardToBeLinkedForThatUser() {
        when(tokenRepository.findByUserIdAndIdentity(7L, "not-mine")).thenReturn(Optional.empty());

        // Otherwise a link could be pointed at a card id the user does not hold.
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.chipTokenOf("not-mine", 7L))
                .isInstanceOf(org.booklore.exception.APIException.class)
                .hasMessageContaining("No such card");
    }

    @Test
    void sharingAChipUsesTheExistingCardsToken() {
        authAsOverdriveUser(7L);
        OverDriveTokenEntity row = new OverDriveTokenEntity();
        row.setUserId(7L);
        row.setIdentity("card-a");
        row.setToken("token-a");
        row.setExpiresAt(Instant.now().getEpochSecond() + 3600);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-a")).thenReturn(Optional.of(row));

        // The link call has to go out on the identity the other card already sits on.
        assertThat(service.chipTokenOf("card-a", 7L)).isEqualTo("token-a");
    }

    /** A linked card row, with stored credentials unless {@code withCredentials} is false. */
    private OverDriveTokenEntity cardRow(String id, String token, boolean withCredentials) {
        OverDriveTokenEntity row = new OverDriveTokenEntity();
        row.setUserId(7L);
        row.setIdentity(id);
        row.setCardName("Card " + id);
        row.setToken(token);
        row.setExpiresAt(Instant.now().getEpochSecond() + 3600);
        if (withCredentials) {
            row.setWebsiteId("100614");
            row.setIlsName("ils");
            row.setCredCard("enc-card");
            row.setCredPin("enc-pin");
        }
        return row;
    }

    @Test
    void unifyingReportsCardsThatCannotMoveInsteadOfSilentlySkippingThem() {
        authAsOverdriveUser(7L);
        OverDriveTokenEntity target = cardRow("card-a", "token-a", true);
        // Linked by setup code, so there is no card + PIN to sign it in with again.
        OverDriveTokenEntity noCreds = cardRow("card-b", "token-b", false);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-a")).thenReturn(Optional.of(target));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(target, noCreds));

        var result = serviceWith(enabledCipher()).unifyChips("card-a");

        assertThat(result.moved()).isEmpty();
        assertThat(result.skipped()).singleElement()
                .satisfies(sk -> {
                    assertThat(sk.cardId()).isEqualTo("card-b");
                    assertThat(sk.reason()).contains("No stored card + PIN");
                });
    }

    @Test
    void unifyingLeavesCardsAlreadyOnTheChipAlone() {
        authAsOverdriveUser(7L);
        OverDriveTokenEntity target = cardRow("card-a", "token-a", true);
        OverDriveTokenEntity sibling = cardRow("card-b", "token-a", true); // same chip already
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-a")).thenReturn(Optional.of(target));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of(target, sibling));

        var result = serviceWith(enabledCipher()).unifyChips("card-a");

        // Nothing to do, and nothing to complain about.
        assertThat(result.moved()).isEmpty();
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void unifyingRejectsACardTheUserDoesNotHave() {
        authAsOverdriveUser(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unifyChips("nope"))
                .isInstanceOf(org.booklore.exception.APIException.class);
    }

    @Test
    void aCardSharedWithYouCannotBeUsedAsAChipTarget() {
        // Owned by user 9 and shared with 7: borrowable, but not theirs to extend.
        OverDriveTokenEntity shared = cardRow("card-shared", "token-x", true);
        shared.setUserId(9L);
        shared.setId(55L);
        OverDriveCardShareEntity share = new OverDriveCardShareEntity();
        share.setTokenId(55L);
        share.setSharedWithUserId(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-shared")).thenReturn(Optional.empty());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of(share));
        when(tokenRepository.findById(55L)).thenReturn(Optional.of(shared));

        // Joining it would put the caller's card on somebody else's Libby account.
        assertThatThrownBy(() -> service.chipTokenOf("card-shared", 7L))
                .isInstanceOf(org.booklore.exception.APIException.class)
                .hasMessageContaining("shared with you");
    }

    @Test
    void unifyingOntoACardSharedWithYouIsRefused() {
        authAsOverdriveUser(7L);
        OverDriveTokenEntity shared = cardRow("card-shared", "token-x", true);
        shared.setUserId(9L);
        shared.setId(55L);
        OverDriveCardShareEntity share = new OverDriveCardShareEntity();
        share.setTokenId(55L);
        share.setSharedWithUserId(7L);
        when(tokenRepository.findByUserIdAndIdentity(7L, "card-shared")).thenReturn(Optional.empty());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of(share));
        when(tokenRepository.findById(55L)).thenReturn(Optional.of(shared));

        assertThatThrownBy(() -> service.unifyChips("card-shared"))
                .isInstanceOf(org.booklore.exception.APIException.class)
                .hasMessageContaining("shared with you");
    }
}
