package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * An identifier (ISBN, etc.) for an OverDrive format.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveIdentifier {
    private String type;
    private String value;
}