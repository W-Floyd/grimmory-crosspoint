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
 *
 * <p>The scalar availability fields are the <b>aggregate</b> across every library the title surfaced
 * from (available if available at <i>any</i> of them). {@code availability} breaks that down
 * per-library so the UI can offer only the cards whose library actually has a copy available now
 * (borrow) or holdable (hold).
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
        /** "Lucky Day" copies available now (aggregate across libraries), borrowable without a hold. */
        Integer luckyDayAvailableCopies,
        boolean preRelease,
        java.util.List<String> formats,
        /** Per-library availability (one entry per library the title surfaced from). */
        java.util.List<OverDriveLibraryAvailability> availability,
        /** Id of an existing library book this title matches (by ISBN), or null if not in the library. */
        Long bookId,
        /** Normalized primary language code (e.g. "en", "es"), or null. */
        String language,
        /** Raw OverDrive edition label (e.g. "Unabridged"/"Abridged"), surfaced as-is; null when absent. */
        String edition,
        /** True when the title is an audiobook (offers an audiobook format), false for an ebook. */
        boolean audiobook,
        /** Narrator name(s) for an audiobook (comma-joined), or null. */
        String narrator,
        /** Audiobook playback length ("HH:MM:SS"), or null. */
        String duration,
        /** True when the title is a magazine (offers a magazine format). */
        boolean magazine
) {}
