package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Request body for linking a library card via a Libby 8-digit setup code.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveSetupCodeRequest {
    /** The Libby 8-digit setup code (libbyapp.com → Settings → "Copy to another device"). */
    private String code;
}
