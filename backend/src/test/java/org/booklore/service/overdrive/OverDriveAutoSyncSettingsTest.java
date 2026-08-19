package org.booklore.service.overdrive;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.overdrive.OverDriveAutoSyncSettings;
import org.booklore.model.entity.OverDriveAuditEntity;
import org.booklore.model.entity.OverDriveAutoSyncEntity;
import org.booklore.model.entity.OverDriveLoanEntity;
import org.booklore.repository.OverDriveAutoSyncRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private java.net.http.HttpClient httpClient;
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
                importDestinationRepository, autoSyncRepository, httpClient, userRepository, authenticationService, appSettingService,
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

        OverDriveAutoSyncSettings saved = service.setAutoSyncSettings(new OverDriveAutoSyncSettings(false, true, false, 14, 48, true));

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

        OverDriveAutoSyncSettings saved = service.setAutoSyncSettings(new OverDriveAutoSyncSettings(true, false, false, 14, 48, true));

        assertThat(saved.autoImportLoans()).isTrue();
        assertThat(saved.autoBorrowHolds()).isFalse();
    }

    @Test
    void optingOutUpdatesTheExistingRow() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                OverDriveAutoSyncEntity.builder().userId(7L).autoImportLoans(true).autoBorrowHolds(true).build()));

        service.setAutoSyncSettings(new OverDriveAutoSyncSettings(false, false, false, 14, 48, true));

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
    void autoReturnAloneIsEnoughToBeInTheWorkList() {
        // Auto-return is an opt-in in its own right; a user who wants only that must still be polled.
        when(autoSyncRepository.findAllOptedIn()).thenReturn(List.of(
                OverDriveAutoSyncEntity.builder().userId(5L).autoReturnEnabled(true).build()));

        assertThat(service.autoSyncOptedInUserIds()).containsExactly(5L);
    }

    @Test
    void autoReturnAloneStillRunsAPass() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                OverDriveAutoSyncEntity.builder().userId(7L).autoReturnEnabled(true).build()));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());

        // Reaches the card lookup rather than short-circuiting on import/borrow both being off.
        service.runAutoSync();

        verify(tokenRepository).findByUserId(7L);
    }

    @Test
    void optedInUserIdsAreTheWorkList() {
        when(autoSyncRepository.findAllOptedIn()).thenReturn(List.of(
                OverDriveAutoSyncEntity.builder().userId(3L).autoImportLoans(true).build(),
                OverDriveAutoSyncEntity.builder().userId(9L).autoBorrowHolds(true).build()));

        assertThat(service.autoSyncOptedInUserIds()).containsExactly(3L, 9L);
    }

    // ── Auto-return ──────────────────────────────────────────────────────

    private OverDriveAutoSyncEntity autoReturnEnabled(int minAgeDays, int windowHours, boolean promptWhenWaitlisted) {
        return OverDriveAutoSyncEntity.builder()
                .userId(7L)
                .autoReturnEnabled(true)
                .autoReturnMinAgeDays(minAgeDays)
                .autoReturnMaxDelayHours(windowHours)
                .autoReturnPromptWhenWaitlisted(promptWhenWaitlisted)
                .build();
    }

    /** A fulfilled loan borrowed the given number of days ago. */
    private OverDriveLoanEntity fulfilledLoan(int daysAgo) {
        OverDriveLoanEntity loan = new OverDriveLoanEntity();
        loan.setUserId(7L);
        loan.setOverdriveLoanId("title-1");
        loan.setIdentity("card-1");
        loan.setTitle("A Book");
        loan.setFulfilled(true);
        loan.setState("ACTIVE");
        loan.setCreatedAt(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
        return loan;
    }

    @Test
    void aDueDateIsDrawnAfterTheMinimumAgeAndWithinTheWindow() {
        OverDriveLoanEntity loan = fulfilledLoan(20);
        Instant minReturnAt = loan.getCreatedAt().plus(14, ChronoUnit.DAYS);

        service.autoReturnDueAt(loan, minReturnAt,
                new OverDriveAutoSyncSettings(false, false, true, 14, 48, true));

        assertThat(loan.getAutoReturnDueAt()).isBetween(minReturnAt, minReturnAt.plus(48, ChronoUnit.HOURS));
    }

    @Test
    void aZeroWindowMeansReturnExactlyAtTheMinimumAge() {
        OverDriveLoanEntity loan = fulfilledLoan(20);
        Instant minReturnAt = loan.getCreatedAt().plus(14, ChronoUnit.DAYS);

        service.autoReturnDueAt(loan, minReturnAt,
                new OverDriveAutoSyncSettings(false, false, true, 14, 0, true));

        assertThat(loan.getAutoReturnDueAt()).isEqualTo(minReturnAt);
    }

    @Test
    void anAlreadyDrawnDueDateIsNotRedrawn() {
        OverDriveLoanEntity loan = fulfilledLoan(20);
        Instant minReturnAt = loan.getCreatedAt().plus(14, ChronoUnit.DAYS);
        Instant fixed = minReturnAt.plus(3, ChronoUnit.HOURS);
        loan.setAutoReturnDueAt(fixed);

        for (int i = 0; i < 20; i++) {
            // Re-rolling each pass would let the target drift forever and never come due.
            assertThat(service.autoReturnDueAt(loan, minReturnAt,
                    new OverDriveAutoSyncSettings(false, false, true, 14, 48, true))).isEqualTo(fixed);
        }
    }

    @Test
    void theDrawnDueDateVariesBetweenLoans() {
        Set<Instant> drawn = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            OverDriveLoanEntity loan = fulfilledLoan(20);
            Instant minReturnAt = Instant.parse("2026-08-01T00:00:00Z");
            drawn.add(service.autoReturnDueAt(loan, minReturnAt,
                    new OverDriveAutoSyncSettings(false, false, true, 14, 48, true)));
        }
        // The whole point is that returns do not all land on the same instant.
        assertThat(drawn).hasSizeGreaterThan(1);
    }

    private OverDriveAutoSyncSettings returnSettings(int minAgeDays, int windowHours, boolean promptWhenWaitlisted) {
        return new OverDriveAutoSyncSettings(false, false, true, minAgeDays, windowHours, promptWhenWaitlisted);
    }

    @Test
    void aWaitlistedTitleComesDueAtTheMinimumAgeWithNoDelay() {
        OverDriveLoanEntity loan = fulfilledLoan(15);

        // Three people queued, so the random window is skipped: it would extend a real wait.
        assertThat(service.autoReturnDue(loan, returnSettings(14, 48, true), 3, Instant.now())).isTrue();
        // No delay was drawn, since the waitlist path does not need one.
        assertThat(loan.getAutoReturnDueAt()).isNull();
    }

    @Test
    void aWaitlistedTitleStillWaitsForTheMinimumAge() {
        // Only 3 days old: holds waiting do not shorten the age the user chose to keep it for.
        OverDriveLoanEntity loan = fulfilledLoan(3);

        assertThat(service.autoReturnDue(loan, returnSettings(14, 48, true), 3, Instant.now())).isFalse();
    }

    @Test
    void withoutTheWaitlistRuleAQueuedTitleStillWaitsOutTheRandomWindow() {
        OverDriveLoanEntity loan = fulfilledLoan(15);
        // Window starts a day after the minimum age, so a 15-day-old loan is not yet due.
        loan.setAutoReturnDueAt(Instant.now().plus(1, ChronoUnit.DAYS));

        assertThat(service.autoReturnDue(loan, returnSettings(14, 48, false), 3, Instant.now())).isFalse();
    }

    @Test
    void aLoanPastItsDrawnDueDateIsReturned() {
        OverDriveLoanEntity loan = fulfilledLoan(20);
        loan.setAutoReturnDueAt(Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(service.autoReturnDue(loan, returnSettings(14, 48, false), 0, Instant.now())).isTrue();
    }

    @Test
    void anUnfulfilledLoanIsNeverAutoReturned() {
        OverDriveLoanEntity loan = fulfilledLoan(30);
        // Never imported: returning it would give up a book the user has no copy of.
        loan.setFulfilled(false);

        assertThat(service.autoReturnDue(loan, returnSettings(14, 0, true), 0, Instant.now())).isFalse();
    }

    @Test
    void anAlreadyReturnedLoanIsNotReturnedAgain() {
        OverDriveLoanEntity loan = fulfilledLoan(30);
        loan.setState("RETURNED");

        assertThat(service.autoReturnDue(loan, returnSettings(14, 0, true), 0, Instant.now())).isFalse();
    }

    @Test
    void aLoanYoungerThanTheMinimumAgeIsNeverDue() {
        OverDriveLoanEntity loan = fulfilledLoan(13);

        assertThat(service.autoReturnDue(loan, returnSettings(14, 0, false), 0, Instant.now())).isFalse();
    }

    @Test
    void changingTheReturnWindowClearsDrawnDueDates() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(autoReturnEnabled(14, 48, true)));
        OverDriveLoanEntity loan = fulfilledLoan(20);
        loan.setAutoReturnDueAt(Instant.now().plus(1, ChronoUnit.DAYS));
        when(loanRepository.findByUserId(7L)).thenReturn(List.of(loan));

        service.setAutoSyncSettings(new OverDriveAutoSyncSettings(false, false, true, 7, 48, true));

        // Drawn against a window the user has just changed, so it no longer means anything.
        assertThat(loan.getAutoReturnDueAt()).isNull();
    }

    @Test
    void historyRecordsWhetherTheAutomationOrTheUserActed() {
        authAs(7L);
        when(autoSyncRepository.findByUserId(7L)).thenReturn(Optional.of(
                OverDriveAutoSyncEntity.builder().userId(7L).autoReturnEnabled(true).build()));
        when(tokenRepository.findByUserId(7L)).thenReturn(List.of());
        when(cardShareRepository.findBySharedWithUserId(7L)).thenReturn(List.of());

        // Nothing to do, but the pass must leave the thread unmarked when it finishes.
        service.runAutoSync();

        service.recordAuditForTest();
        ArgumentCaptor<OverDriveAuditEntity> captor = ArgumentCaptor.forClass(OverDriveAuditEntity.class);
        verify(auditRepository).save(captor.capture());
        // A leaked flag would misattribute every later interactive action on this thread.
        assertThat(captor.getValue().isAutomated()).isFalse();
    }

    // ── History paging ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<org.springframework.data.domain.Pageable> stubHistoryPage() {
        ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(auditRepository.findByUserIdOrderByCreatedAtDesc(eq(7L), captor.capture()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        return captor;
    }

    @Test
    void historyDefaultsToTheFirstPage() {
        authAs(7L);
        var captor = stubHistoryPage();

        service.listHistory(null, null);

        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void historyPageSizeIsCappedSoOneRequestCannotAskForEverything() {
        authAs(7L);
        var captor = stubHistoryPage();

        service.listHistory(0, 100_000);

        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void nonsensicalPagingIsClampedRatherThanRejected() {
        authAs(7L);
        var captor = stubHistoryPage();

        // A paginator can ask for a negative offset or a zero size; an empty page beats a 400.
        service.listHistory(-3, 0);

        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void historyReportsTheServerSideTotalNotThePageLength() {
        authAs(7L);
        when(auditRepository.findByUserIdOrderByCreatedAtDesc(eq(7L), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(), org.springframework.data.domain.PageRequest.of(0, 25), 130));

        // The paginator sizes itself from this, so it must be the full count.
        assertThat(service.listHistory(0, 25).total()).isEqualTo(130);
    }
}
