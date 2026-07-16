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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverDriveServiceTest {

    @Mock private OverDriveLoanRepository loanRepository;
    @Mock private AcsmHandler acsmHandler;
    @Mock private RestClient restClient;
    @Mock private OverDriveImportService overDriveImportService;
    @Mock private OverDriveParser overDriveParser;
    @Mock private OverDriveTokenRepository tokenRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private org.booklore.service.appsettings.AppSettingService appSettingService;

    private OverDriveService service;

    @BeforeEach
    void setUp() {
        service = new OverDriveService(loanRepository, acsmHandler, restClient,
                overDriveImportService, overDriveParser, tokenRepository, authenticationService, appSettingService);
    }

    private void authAs(long userId) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(userId).build());
    }

    @Test
    void storeToken_persistsForCurrentUser() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(Optional.empty());

        service.storeToken("card-1", "token-xyz");

        ArgumentCaptor<OverDriveTokenEntity> captor = ArgumentCaptor.forClass(OverDriveTokenEntity.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getIdentity()).isEqualTo("card-1");
        assertThat(captor.getValue().getToken()).isEqualTo("token-xyz");
    }

    @Test
    void hasToken_reflectsCurrentUser() {
        authAs(7L);
        when(tokenRepository.existsByUserId(7L)).thenReturn(true);
        assertThat(service.hasToken("ignored")).isTrue();
    }

    @Test
    void listIdentities_returnsCurrentUsersCard() {
        authAs(7L);
        when(tokenRepository.findByUserId(7L))
                .thenReturn(Optional.of(OverDriveTokenEntity.builder().userId(7L).identity("card-1").token("t").build()));
        assertThat(service.listIdentities()).containsExactly("card-1");
    }

    @Test
    void removeToken_removesCurrentUsersToken() {
        authAs(7L);
        service.removeToken("ignored");
        verify(tokenRepository).deleteByUserId(7L);
    }

    @Test
    void getStoredTokenForUser_returnsTokenWhenPresent() {
        when(tokenRepository.findByUserId(9L))
                .thenReturn(Optional.of(OverDriveTokenEntity.builder().userId(9L).identity("c").token("tok").build()));
        assertThat(service.getStoredTokenForUser(9L)).isEqualTo("tok");
        assertThat(service.getStoredTokenForUser(null)).isNull();
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

        when(overDriveParser.searchCatalog("dune")).thenReturn(List.of(item));

        var results = service.searchCatalog("dune");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().titleId()).isEqualTo("title-1");
        assertThat(results.getFirst().formatId()).isEqualTo("ebook-epub-adobe");
        assertThat(results.getFirst().author()).isEqualTo("Frank Herbert");
        assertThat(results.getFirst().isbn()).isEqualTo("9780441013593");
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
        // Provided token → borrow proceeds to the (mocked) RestClient and fails; nothing is imported.
        assertThatThrownBy(() -> service.borrowAndImport("card", "tok", "title", 1L, 1L, "t", "a", null, null))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(overDriveImportService);
    }

    @Test
    void borrowAndImport_failsWhenNoTokenAvailable() {
        // Blank token and no stored token for the current user → resolveToken throws, nothing imported.
        authAs(7L);
        when(tokenRepository.findByUserId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.borrowAndImport("card", "", "title", 1L, 1L, "t", "a", null, null))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(overDriveImportService);
    }
}
