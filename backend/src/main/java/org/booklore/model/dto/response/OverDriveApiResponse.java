package org.booklore.model.dto.response;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response shape of the OverDrive "Thunder" catalog API (the unofficial, no-auth API the Libby web
 * app uses), e.g. {@code GET https://thunder.api.overdrive.com/v2/libraries/{libraryKey}/media?query=...}.
 * The API is undocumented; fields here are lenient ({@code ignoreUnknown}) and reflect the observed
 * search-result item shape. Only read-only catalog metadata is consumed — no lending/DRM.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveApiResponse {
    private Integer totalItems;
    private List<Item> items;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private String id;
        private String title;
        private String subtitle;
        private String description;
        private String fullDescription;
        private List<Creator> creators;
        private Covers covers;
        private Publisher publisher;
        private String publishDate;
        private List<NamedValue> subjects;
        private List<NamedValue> languages;
        private List<Format> formats;
        private DetailedSeries detailedSeries;
        private Double starRating;

        // Availability fields (present on the browse/media responses; see docs §7b). Nullable — the
        // metadata-only search path does not depend on them, so absence is tolerated.
        @JsonProperty("isAvailable")
        private Boolean available;
        @JsonProperty("isHoldable")
        private Boolean holdable;
        @JsonProperty("isPreReleaseTitle")
        private Boolean preRelease;
        private Integer availableCopies;
        private Integer ownedCopies;
        private Integer holdsCount;
        private Integer estimatedWaitDays;
        private String availabilityType;
        /** "Lucky Day" / skip-the-line copies: borrowable now without a hold, outside the regular pool. */
        private Integer luckyDayAvailableCopies;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Creator {
            private String name;
            private String role;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Covers {
            /**
             * All cover renditions keyed by their OverDrive name (e.g. {@code cover150Wide},
             * {@code cover510Wide}). The set of sizes varies per title and can change over time, so we
             * capture whatever the API returns rather than hardcoding a fixed list; the largest is chosen
             * at read time (by {@code width}, falling back to the width parsed from the key).
             */
            private final Map<String, Cover> variants = new LinkedHashMap<>();

            @JsonAnySetter
            void putVariant(String name, Cover cover) {
                if (cover != null) {
                    variants.put(name, cover);
                }
            }

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Cover {
                private String href;
                private Integer width;
                private Integer height;
            }
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Publisher {
            private String name;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class NamedValue {
            private String name;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Format {
            private String id;
            private String isbn;
            private List<Identifier> identifiers;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Identifier {
                private String type;
                private String value;
            }
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class DetailedSeries {
            private String seriesName;
            private String readingOrder;
        }
    }
}
