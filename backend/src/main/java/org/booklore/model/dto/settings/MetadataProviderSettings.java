package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

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
        private boolean preferRomaji;
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
        /**
         * OverDrive/Libby library keys (each a "preferredKey" / website id, e.g. "lapl") to search for
         * metadata, deduplicated by title. Managed on the Settings → OverDrive page. Not used for the
         * catalog/borrow flow, which is scoped to the user's own linked cards.
         */
        private List<String> libraryKeys;
        /** Libby/OverDrive sentry base URL (e.g. https://sentry.libbyapp.com). */
        private String sentryBaseUrl;
        /** OverDrive client ID (defaults to "dewey"). */
        private String clientId;
        /**
         * Preferred order of ebook fulfillment formats for borrow &amp; import, most-preferred first.
         * Values are OverDrive format ids (ebook-epub-open, ebook-epub-adobe, ebook-pdf-open,
         * ebook-pdf-adobe). Empty/null falls back to the built-in default order.
         */
        private java.util.List<String> formatPreference;
    }
}
