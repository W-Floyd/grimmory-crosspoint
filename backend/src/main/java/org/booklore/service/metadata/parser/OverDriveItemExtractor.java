package org.booklore.service.metadata.parser;

import org.booklore.model.dto.response.OverDriveApiResponse;

import org.booklore.util.LanguageNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Narrator names (creators roled "narrator"), comma-joined, or null when none — for audiobooks. */
    public static String narrator(OverDriveApiResponse.Item item) {
        if (item.getCreators() == null || item.getCreators().isEmpty()) {
            return null;
        }
        List<String> narrators = item.getCreators().stream()
                .filter(c -> c.getName() != null && c.getRole() != null && c.getRole().toLowerCase().contains("narrator"))
                .map(OverDriveApiResponse.Item.Creator::getName)
                .filter(n -> !n.isBlank())
                .toList();
        return narrators.isEmpty() ? null : String.join(", ", narrators);
    }

    /** Href of the largest available cover rendition (by width), determined at runtime, or null. */
    public static String coverHref(OverDriveApiResponse.Item.Covers covers) {
        if (covers == null) {
            return null;
        }
        String bestHref = null;
        int bestWidth = -1;
        for (Map.Entry<String, OverDriveApiResponse.Item.Covers.Cover> entry : covers.getVariants().entrySet()) {
            OverDriveApiResponse.Item.Covers.Cover cover = entry.getValue();
            if (cover == null || cover.getHref() == null || cover.getHref().isBlank()) {
                continue;
            }
            int width = coverWidth(entry.getKey(), cover.getWidth());
            if (width > bestWidth) {
                bestWidth = width;
                bestHref = cover.getHref();
            }
        }
        return bestHref != null ? encodeCoverUrl(bestHref) : null;
    }

    /**
     * The pixel width of a cover rendition: its explicit {@code width} when present, otherwise parsed
     * from the OverDrive key convention ({@code coverNNNWide} → NNN), else 0. Lets callers pick the
     * largest rendition without hardcoding which sizes exist.
     */
    public static int coverWidth(String key, Integer explicitWidth) {
        if (explicitWidth != null && explicitWidth > 0) {
            return explicitWidth;
        }
        if (key != null) {
            Matcher matcher = COVER_WIDTH_PATTERN.matcher(key);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return 0;
    }

    private static final Pattern COVER_WIDTH_PATTERN = Pattern.compile("(\\d+)");

    /**
     * Percent-encode characters OverDrive leaves literal in cover URLs (the {@code {crid}} braces, and
     * the occasional space/pipe/caret) so the URL parses as a valid URI when the cover is downloaded.
     * Browsers accept the encoded form too, so display is unaffected.
     */
    public static String encodeCoverUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("{", "%7B")
                .replace("}", "%7D")
                .replace(" ", "%20")
                .replace("|", "%7C")
                .replace("^", "%5E");
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

    /**
     * The primary language code for a title: the language entry's {@code id} (which OverDrive gives as
     * the code, e.g. "es") when present, otherwise the normalized display name, else null.
     */
    public static String languageCode(OverDriveApiResponse.Item item) {
        if (item.getLanguages() == null || item.getLanguages().isEmpty()) {
            return null;
        }
        OverDriveApiResponse.Item.NamedValue lang = item.getLanguages().getFirst();
        if (lang.getId() != null && !lang.getId().isBlank()) {
            return lang.getId().trim().toLowerCase(Locale.ROOT);
        }
        return lang.getName() != null ? LanguageNormalizer.normalize(lang.getName()) : null;
    }

    /** The ASIN (Amazon edition id) from the title's format identifiers, or null. */
    public static String asin(OverDriveApiResponse.Item item) {
        if (item.getFormats() == null) {
            return null;
        }
        for (OverDriveApiResponse.Item.Format format : item.getFormats()) {
            if (format.getIdentifiers() == null) {
                continue;
            }
            for (OverDriveApiResponse.Item.Format.Identifier id : format.getIdentifiers()) {
                if (id.getType() != null && id.getType().equalsIgnoreCase("ASIN")
                        && id.getValue() != null && !id.getValue().isBlank()) {
                    return id.getValue().trim();
                }
            }
        }
        return null;
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

}
