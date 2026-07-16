package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class MetadataProviderSettings {
    private Amazon amazon;
    private Google google;
    private Goodreads goodReads;
    private Hardcover hardcover;
    private Comicvine comicvine;
    private Ranobedb ranobedb;
    private Douban douban;
    @JsonProperty("lubimyczytac")
    private Lubimyczytac lubimyczytac;
    private Audible audible;
    private Overdrive overdrive;

    @Data
    public static class Amazon {
        private boolean enabled;
        private String cookie;
        private String domain;
    }

    @Data
    public static class Google {
        private boolean enabled;
        private String language;
        private String apiKey;
    }

    @Data
    public static class Goodreads {
        private boolean enabled;
    }

    @Data
    public static class Hardcover {
        private boolean enabled;
        private String apiKey;
    }

    @Data
    public static class Comicvine {
        private boolean enabled;
        private String apiKey;
    }

    @Data
    public static class Ranobedb {
        private boolean enabled;
    }

    @Data
    public static class Douban {
        private boolean enabled;
    }

    @Data
    public static class Lubimyczytac {
        private boolean enabled;
    }

    @Data
    public static class Audible {
        private boolean enabled;
        private String domain;
    }

    @Data
    public static class Overdrive {
        private boolean enabled;
        /** OverDrive/Libby library key (the "preferredKey" / website id, e.g. "lapl"). */
        private String libraryKey;
        /** Libby/OverDrive sentry base URL (e.g. https://sentry.libbyapp.com). */
        private String sentryBaseUrl;
        /** OverDrive client ID (defaults to "dewey"). */
        private String clientId;
        /** Whether to auto-return books after reading. */
        private boolean autoReturn;
        /** Whether to auto-borrow when metadata refresh finds a copy. */
        private boolean autoBorrow;
        /**
         * Preferred order of ebook fulfillment formats for borrow &amp; import, most-preferred first.
         * Values are OverDrive format ids (ebook-epub-open, ebook-epub-adobe, ebook-pdf-open,
         * ebook-pdf-adobe). Empty/null falls back to the built-in default order.
         */
        private java.util.List<String> formatPreference;
    }
}
