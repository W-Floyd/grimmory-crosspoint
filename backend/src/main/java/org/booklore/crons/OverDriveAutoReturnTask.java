package org.booklore.crons;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.OverdriveProperties;
import org.booklore.model.entity.OverDriveLoanEntity;
import org.booklore.repository.OverDriveLoanRepository;
import org.booklore.service.overdrive.OverDriveService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Periodically returns OverDrive loans whose lending period has expired, when auto-return is enabled
 * ({@code app.overdrive.auto-return=true}). A loan can only be returned if a token is still stored for
 * its identity; otherwise it is marked EXPIRED so it is not retried indefinitely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverDriveAutoReturnTask {

    private final OverDriveLoanRepository loanRepository;
    private final OverDriveService overDriveService;
    private final OverdriveProperties overdriveProperties;

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void returnExpiredLoans() {
        if (!overdriveProperties.isEnabled() || !overdriveProperties.isAutoReturn()) {
            return;
        }

        List<OverDriveLoanEntity> expired = loanRepository.findExpiredLoans(Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        log.info("OverDrive auto-return: {} expired loan(s) to process", expired.size());

        for (OverDriveLoanEntity loan : expired) {
            String token = overDriveService.getStoredTokenForUser(loan.getUserId());
            if (token == null || token.isBlank()) {
                log.warn("OverDrive auto-return: no stored token for user {}, marking loan {} EXPIRED",
                        loan.getUserId(), loan.getOverdriveLoanId());
                loan.setState("EXPIRED");
                loanRepository.save(loan);
                continue;
            }
            try {
                overDriveService.returnBook(loan.getIdentity(), token, loan.getOverdriveLoanId());
                log.info("OverDrive auto-return: returned expired loan {}", loan.getOverdriveLoanId());
            } catch (Exception e) {
                log.warn("OverDrive auto-return: failed to return loan {}: {}",
                        loan.getOverdriveLoanId(), e.getMessage());
            }
        }
    }
}
