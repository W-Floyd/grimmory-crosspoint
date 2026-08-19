package org.booklore.util;

import org.booklore.model.dto.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code {overdriveId}} path token.
 *
 * <p>A work can have several OverDrive editions sharing a title, author and year — the id is what
 * separates them. Because it lives in metadata rather than in a filename produced once at import, the
 * import and any later Move &amp; Organize resolve the same path, instead of the reorganise stripping
 * a suffix it knows nothing about.
 */
class PathPatternResolverOverdriveIdTest {

    private BookMetadata edition(String overdriveId) {
        return BookMetadata.builder()
                .title("Love from Paddington")
                .authors(List.of("Michael Bond"))
                .overdriveId(overdriveId)
                .build();
    }

    @Test
    void theTokenResolvesToTheEditionId() {
        String path = PathPatternResolver.resolvePattern(edition("1986375"),
                "{title} - {authors} [od-{overdriveId}].{extension}", "book.epub");

        assertThat(path).isEqualTo("Love from Paddington - Michael Bond [od-1986375].epub");
    }

    @Test
    void twoEditionsOfOneWorkResolveToDifferentPaths() {
        String pattern = "{title} - {authors} [od-{overdriveId}].{extension}";

        assertThat(PathPatternResolver.resolvePattern(edition("1986375"), pattern, "book.epub"))
                .isNotEqualTo(PathPatternResolver.resolvePattern(edition("1689083"), pattern, "book.epub"));
    }

    @Test
    void aBookWithNoOverdriveIdStillResolves() {
        // Everything not imported from OverDrive — the token simply contributes nothing.
        String path = PathPatternResolver.resolvePattern(edition(null), "{title} - {authors}.{extension}", "book.epub");

        assertThat(path).isEqualTo("Love from Paddington - Michael Bond.epub");
    }

    @Test
    void patternsWithoutTheTokenAreUnaffected() {
        String path = PathPatternResolver.resolvePattern(edition("1986375"), "{title}.{extension}", "book.epub");

        assertThat(path).isEqualTo("Love from Paddington.epub");
    }
}
