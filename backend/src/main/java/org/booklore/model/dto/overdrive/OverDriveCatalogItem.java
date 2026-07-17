package org.booklore.model.dto.overdrive;

/**
 * A borrowable OverDrive catalog search result: the fields the UI needs to display a title and then
 * borrow it. {@code titleId} is the OverDrive reserve/title id; {@code formatId} is the ebook format
 * id passed to the borrow endpoint.
 *
 * <p>The availability fields ({@code available}, {@code holdable}, copy counts, {@code holdsCount},
 * {@code estimatedWaitDays}, {@code preRelease}) let the UI offer <b>Borrow</b> when the title is
 * available now vs <b>Place Hold</b> when it is not. They default to safe values when Thunder omits
 * them (not available, not holdable).
 *
 * <p>{@code formats} lists the importable formats this title offers, in the operator's preference
 * order (see {@code formatPreference()}); {@code formatId} is the default (first) one. The UI shows
 * the list so the user may borrow a non-default version.
 */
public record OverDriveCatalogItem(
        String titleId,
        String formatId,
        String title,
        String subtitle,
        String author,
        String coverUrl,
        String isbn,
        boolean available,
        boolean holdable,
        Integer availableCopies,
        Integer ownedCopies,
        Integer holdsCount,
        Integer estimatedWaitDays,
        boolean preRelease,
        java.util.List<String> formats
) {}
