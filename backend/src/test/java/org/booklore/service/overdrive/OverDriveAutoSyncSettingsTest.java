package org.booklore.service.overdrive;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.overdrive.OverDriveAutoSyncSettings;
import org.booklore.model.entity.OverDriveAutoSyncEntity;
import org.booklore.repository.OverDriveAutoSyncRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The per-user opt-in for unattended OverDrive activity: what is stored, how the two flags relate,
 * and that a user who has not opted in is never acted on.
 */
@ExtendWith(MockitoExtension.class)
class OverDriveAutoSyncSettingsTest {

    @Mock private org.booklore.repository.OverDriveLoanRepository loanRepository;
    @Mock private org.booklore.repository.BookRepository bookRepository;
    @Mock private org.booklore.service.acsm.AcsmHandler acsmHandler;
    @Mock private org.booklore.service.audiobook.AudiobookHandler audiobookHandler;
    @Mock private org.booklore.service.magazine.MagazineHandler magazineHandler;
    @Mock private org.booklore.service.ebook.EbookHandler ebookHandler;
    @Mock private org.booklore.service.book.BookService bookService;
    @Mock private RestClient restClient;
    @Mock private OverDriveImportService overDriveImportService;
    @Mock private org.booklore.service.metadata.parser.OverDriveParser overDriveParser;
    @Mock private org.booklore.repository.OverDriveTokenRepository tokenRepository;
    @Mock private org.booklore.repository.OverDriveCardShareRepository cardShareRepository;
    @Mock private org.booklore.repository.OverDriveAuditRepository auditRepository;
    @Mock private org.booklore.repository.OverDriveImportDestinationRepository importDestinationRepository;
    @Mock private OverDriveAutoSyncRepository autoSyncRepository;
    @Mock private org.booklore.repository.UserRepository userRepository;
    @Mock private AuthenticationService authenticationService;
    @Mock private org.booklore.service.appsettings.AppSettingService appSettingService;
    @Mock private org.booklore.service.NotificationService notificationService;
    @Mock private org.booklore.service.book.BookFileAttachmentService bookFileAttachmentService;

    private OverDriveService service;

    @BeforeEach
    void setUp() {
        service = new OverDriveService(loanRepository, bookRepository, acsmHandler, audiobookHandler, magazineHandler,
                ebookHandler, bookService,
                restClient, overDriveImportService, overDriveParser, tokenRepository, cardShareRepository, auditRepository,
                importDestinationRepository, autoSyncRepository, userRepository, authenticationService, appSettingService,
                new OverDriveCredentialCipher(""), notificationService, bookFileAttachmentService);
    }

    private void authAs(long userId) {
        when(authenticationService.getAuthenticatedUser()).thenReturn(BookLoreUser.builder().id(userId).build());
    }

    @Test
    void userWhoNeverOptedInIsOptedOut() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.empty());

        OverDriveAutoSyncSettings settings = service.getAutoSyncSettings();

        assertThat(settings.autoImportLoans()).isFalse();
        assertThat(settings.autoBorrowHolds()).isFalse();
    }

    @Test
    void storedSettingsAreReadBack() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                OverDriveAutoSyncEntity.builder().userId(7L).autoImportLoans(true).autoBorrowHolds(false).build()));

        OverDriveAutoSyncSettings settings = service.getAutoSyncSettings();

        assertThat(settings.autoImportLoans()).isTrue();
        assertThat(settings.autoBorrowHolds()).isFalse();
    }

    @Test
    void enablingAutoBorrowAlsoEnablesAutoImport() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.empty());

        OverDriveAutoSyncSettings saved = service.setAutoSyncSettings(new OverDriveAutoSyncSettings(false, true));

        // A borrowed hold that nothing then fetches has burned the hold for nothing, so the pair is
        // normalised on write rather than every reader having to re-derive it.
        assertThat(saved.autoImportLoans()).isTrue();
        assertThat(saved.autoBorrowHolds()).isTrue();

        ArgumentCaptor<OverDriveAutoSyncEntity> captor = ArgumentCaptor.forClass(OverDriveAutoSyncEntity.class);
        verify(autoSyncRepository).save(captor.capture());
        assertThat(captor.getValue().isAutoImportLoans()).isTrue();
        assertThat(captor.getValue().isAutoBorrowHolds()).isTrue();
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
    }

    @Test
    void autoImportCanBeEnabledWithoutAutoBorrow() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.empty());

        OverDriveAutoSyncSettings saved = service.setAutoSyncSettings(new OverDriveAutoSyncSettings(true, false));

        assertThat(saved.autoImportLoans()).isTrue();
        assertThat(saved.autoBorrowHolds()).isFalse();
    }

    @Test
    void optingOutUpdatesTheExistingRow() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                OverDriveAutoSyncEntity.builder().userId(7L).autoImportLoans(true).autoBorrowHolds(true).build()));

        service.setAutoSyncSettings(new OverDriveAutoSyncSettings(false, false));

        ArgumentCaptor<OverDriveAutoSyncEntity> captor = ArgumentCaptor.forClass(OverDriveAutoSyncEntity.class);
        verify(autoSyncRepository).save(captor.capture());
        assertThat(captor.getValue().isAutoImportLoans()).isFalse();
        assertThat(captor.getValue().isAutoBorrowHolds()).isFalse();
    }

    @Test
    void autoSyncDoesNothingForAUserWhoHasNotOptedIn() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.empty());

        OverDriveService.AutoSyncOutcome outcome = service.runAutoSync();

        assertThat(outcome.cardsSynced()).isZero();
        assertThat(outcome.holdsBorrowed()).isZero();
        assertThat(outcome.loansImported()).isZero();
        // Opted out means untouched: no card lookup, no Libby call, no loan writes.
        verifyNoInteractions(tokenRepository, loanRepository, restClient);
    }

    @Test
    void autoSyncStopsWhenTheUserHasNoCards() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                OverDriveAutoSyncEntity.builder().userId(7L).autoImportLoans(true).autoBorrowHolds(true).build()));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());

        OverDriveService.AutoSyncOutcome outcome = service.runAutoSync();

        assertThat(outcome.cardsSynced()).isZero();
        verifyNoInteractions(restClient);
    }

    @Test
    void optedInUserIdsAreTheWorkList() {
        when(autoSyncRepository.findAllOptedIn()).thenReturn(List.of(
                OverDriveAutoSyncEntity.builder().userId(3L).autoImportLoans(true).build(),
                OverDriveAutoSyncEntity.builder().userId(9L).autoBorrowHolds(true).build()));

        assertThat(service.autoSyncOptedInUserIds()).containsExactly(3L, 9L);
    }
}
