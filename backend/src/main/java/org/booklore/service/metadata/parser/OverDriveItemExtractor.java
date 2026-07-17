package org.booklore.service.metadata.parser;

import org.booklore.model.dto.response.OverDriveApiResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared extraction of fields from an OverDrive Thunder catalog {@link OverDriveApiResponse.Item}.
 * Used by both the metadata provider ({@link OverDriveParser}) and the borrow/import flow
 * (OverDriveService) so the two never drift in how they read authors, covers, and ISBNs.
 */
public final class OverDriveItemExtractor {

    private OverDriveItemExtractor() {
    }

    /**
     * Author names for an item: creators explicitly roled "author", or — when none are so roled — all
     * named creators. Returns null when the item has no usable creator name.
     */
    public static List<String> authors(OverDriveApiResponse.Item item) {
        if (item.getCreators() == null || item.getCreators().isEmpty()) {
            return null;
        }
        List<String> authors = item.getCreators().stream()
                .filter(c -> c.getName() != null && c.getRole() != null && c.getRole().toLowerCase().contains("author"))
                .map(OverDriveApiResponse.Item.Creator::getName)
                .toList();
        if (authors.isEmpty()) {
            // No explicit author role — fall back to all named creators.
            authors = item.getCreators().stream()
                    .map(OverDriveApiResponse.Item.Creator::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .toList();
        }
        return authors.isEmpty() ? null : new ArrayList<>(authors);
    }

    /** The single primary author (first of {@link #authors}), or null. */
    public static String primaryAuthor(OverDriveApiResponse.Item item) {
        List<String> authors = authors(item);
        return authors == null || authors.isEmpty() ? null : authors.getFirst();
    }

    /** Best cover href, largest first (510 → 300 → 150 wide), or null. */
    public static String coverHref(OverDriveApiResponse.Item.Covers covers) {
        if (covers == null) {
            return null;
        }
        for (OverDriveApiResponse.Item.Covers.Cover cover : List.of(
                nullSafe(covers.getCover510Wide()), nullSafe(covers.getCover300Wide()), nullSafe(covers.getCover150Wide()))) {
            if (cover.getHref() != null && !cover.getHref().isBlank()) {
                return cover.getHref();
            }
        }
        return null;
    }

    /** @return [isbn13, isbn10], cleaned; either may be null. Reads format ISBNs and ISBN identifiers. */
    public static String[] isbns(OverDriveApiResponse.Item item) {
        String isbn13 = null;
        String isbn10 = null;
        if (item.getFormats() != null) {
            for (OverDriveApiResponse.Item.Format format : item.getFormats()) {
                for (String candidate : isbnCandidates(format)) {
                    String cleaned = ParserUtils.cleanIsbn(candidate);
                    if (cleaned == null) continue;
                    if (cleaned.length() == 13 && isbn13 == null) isbn13 = cleaned;
                    else if (cleaned.length() == 10 && isbn10 == null) isbn10 = cleaned;
                }
            }
        }
        return new String[]{isbn13, isbn10};
    }

    /** The preferred single ISBN for an item: ISBN-13 if present, else ISBN-10, else null. */
    public static String primaryIsbn(OverDriveApiResponse.Item item) {
        String[] isbns = isbns(item);
        return isbns[0] != null ? isbns[0] : isbns[1];
    }

    private static List<String> isbnCandidates(OverDriveApiResponse.Item.Format format) {
        List<String> candidates = new ArrayList<>();
        if (format.getIsbn() != null) {
            candidates.add(format.getIsbn());
        }
        if (format.getIdentifiers() != null) {
            format.getIdentifiers().stream()
                    .filter(id -> id.getType() != null && id.getType().toUpperCase().contains("ISBN") && id.getValue() != null)
                    .forEach(id -> candidates.add(id.getValue()));
        }
        return candidates;
    }

    private static OverDriveApiResponse.Item.Covers.Cover nullSafe(OverDriveApiResponse.Item.Covers.Cover cover) {
        return cover != null ? cover : new OverDriveApiResponse.Item.Covers.Cover();
    }
}
