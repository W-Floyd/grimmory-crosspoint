package org.booklore.service.overdrive;

/**
 * What a particular build of the Libby web client sends, as far as our requests depend on it.
 *
 * <p>OverDrive serves exactly one build at a time, but which one is up to them, and a rollback would
 * put an older build back in front of real users. Keeping each build's wire shape behind its own
 * profile means a version we have already verified stays speakable, rather than being whatever the
 * newest constant happens to say.
 *
 * <p>Only the parts that are a property of the Libby build belong here. The browser's own headers
 * (user agent, Accept-Language) belong to the browser, not the app, and are not versioned with it.
 * Where a field is the same across every known build it still lives here, because the next build is
 * the one that might change it — see scripts/libby-client/PRIORS.md for what each was read from.
 */
public record LibbyClientProfile(String version, ShibbolethScheme shibboleth) {

    /** How the {@code /chip} re-mint derives its Accept-Language shibboleth ({@code obf/shib.js}). */
    public enum ShibbolethScheme {
        /** Strip the bearer to [a-z], reverse it, take two characters at the request path's length. */
        REVERSED_ALPHA_AT_PATH_LENGTH
    }

    /**
     * Query string for {@code POST /chip}. The {@code v} parameter carries the chip-id prefix and only
     * exists on a re-mint, which has an identity to take it from; an anonymous mint omits it.
     */
    public String chipQuery(String chipIdPrefix) {
        String query = "c=d:" + version + "&s=0";
        return chipIdPrefix != null ? query + "&v=" + chipIdPrefix : query;
    }
}
