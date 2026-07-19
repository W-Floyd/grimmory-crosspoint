package org.booklore.model.dto.overdrive;

/**
 * One entry in a user's OverDrive activity history, for the History tab.
 */
public record OverDriveAuditEntry(
        Long id,
        String action,
        String identity,
        String libraryKey,
        String cardName,
        String titleId,
        String loanId,
        Long bookId,
        String title,
        String detail,
        boolean success,
        String createdAt) {}
