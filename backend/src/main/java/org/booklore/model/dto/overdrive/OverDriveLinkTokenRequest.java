package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Request body for linking cards by pasting a Libby identity token (localStorage {@code …:sentry.identity},
 * or an {@code Authorization: Bearer} value from a signed-in browser).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveLinkTokenRequest {
    /** The Libby identity token; a leading "Bearer " and surrounding quotes are tolerated. */
    private String token;

    /**
     * Link the token's cards for another user instead of yourself — requires permission to manage any
     * user's OverDrive cards. Omit to link for yourself.
     */
    private Long userId;
}
