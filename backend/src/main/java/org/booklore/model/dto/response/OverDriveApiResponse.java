package org.booklore.model.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

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

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Creator {
            private String name;
            private String role;
        }

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Covers {
            private Cover cover150Wide;
            private Cover cover300Wide;
            private Cover cover510Wide;

            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            public static class Cover {
                private String href;
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
