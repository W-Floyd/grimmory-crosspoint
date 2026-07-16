package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Response from the Libby chip endpoint: POST sentry.libbyapp.com/chip?client=dewey
 * Returns the initial identity token and expiry.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveChipResponse {
    private String identity;
    private String access_token;
    private String access_token_type;
    private int access_token_expires_in;
    private String client_id;
    private String client_id_token;
    private String client_id_expires_in;
    private String scope;
    private int id_token_expires_in;
    private String id_token;
    private String refresh_token;
    private String refresh_token_expires_in;
    private String server_sentry;
}