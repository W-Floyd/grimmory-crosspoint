package org.booklore.crons;

import org.booklore.model.dto.settings.OverdriveProperties;
import org.booklore.model.entity.OverDriveLoanEntity;
import org.booklore.repository.OverDriveLoanRepository;
import org.booklore.service.overdrive.OverDriveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverDriveAutoReturnTaskTest {

    @Mock private OverDriveLoanRepository loanRepository;
    @Mock private OverDriveService overDriveService;

    private OverdriveProperties properties;
    private OverDriveAutoReturnTask task;

    @BeforeEach
    void setUp() {
        properties = new OverdriveProperties();
        task = new OverDriveAutoReturnTask(loanRepository, overDriveService, properties);
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);
        properties.setAutoReturn(true);
        task.returnExpiredLoans();
        verifyNoInteractions(loanRepository, overDriveService);
    }

    @Test
    void doesNothingWhenAutoReturnOff() {
        properties.setEnabled(true);
        properties.setAutoReturn(false);
        task.returnExpiredLoans();
        verifyNoInteractions(loanRepository, overDriveService);
    }

    @Test
    void returnsExpiredLoanWhenTokenAvailable() {
        properties.setEnabled(true);
        properties.setAutoReturn(true);
        OverDriveLoanEntity loan = new OverDriveLoanEntity();
        loan.setOverdriveLoanId("loan-1");
        loan.setIdentity("lib-a");
        loan.setUserId(7L);
        when(loanRepository.findExpiredLoans(any())).thenReturn(List.of(loan));
        when(overDriveService.getStoredToken(7L, "lib-a")).thenReturn("tok");

        task.returnExpiredLoans();

        verify(overDriveService).returnBook("lib-a", "tok", "loan-1");
    }

    @Test
    void marksExpiredWhenNoTokenAvailable() {
        properties.setEnabled(true);
        properties.setAutoReturn(true);
        OverDriveLoanEntity loan = new OverDriveLoanEntity();
        loan.setOverdriveLoanId("loan-2");
        loan.setIdentity("lib-b");
        loan.setUserId(8L);
        when(loanRepository.findExpiredLoans(any())).thenReturn(List.of(loan));
        when(overDriveService.getStoredToken(8L, "lib-b")).thenReturn(null);

        task.returnExpiredLoans();

        verify(overDriveService, never()).returnBook(any(), any(), any());
        verify(loanRepository).save(loan);
        assertThat(loan.getState()).isEqualTo("EXPIRED");
    }
}
