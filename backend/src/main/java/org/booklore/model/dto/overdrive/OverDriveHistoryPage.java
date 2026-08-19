package org.booklore.model.dto.overdrive;

import java.util.List;

/**
 * One page of a user's OverDrive activity history.
 *
 * @param entries the rows on this page, newest first
 * @param page    zero-based page index
 * @param size    page size actually applied (the request is clamped)
 * @param total   total rows the user has, across all pages
 */
public record OverDriveHistoryPage(List<OverDriveAuditEntry> entries, int page, int size, long total) {}
