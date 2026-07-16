package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Format information for an OverDrive book (ISBN, identifiers, etc.).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveFormat {
    private String id;
    private String isbn;
    private String mpid;
}