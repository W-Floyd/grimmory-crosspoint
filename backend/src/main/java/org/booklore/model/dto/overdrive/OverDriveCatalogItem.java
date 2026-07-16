package org.booklore.model.dto.overdrive;

/**
 * A borrowable OverDrive catalog search result: the fields the UI needs to display a title and then
 * borrow it. {@code titleId} is the OverDrive reserve/title id; {@code formatId} is the ebook format
 * id passed to the borrow endpoint.
 */
public record OverDriveCatalogItem(
        String titleId,
        String formatId,
        String title,
        String author,
        String coverUrl,
        String isbn
) {}
