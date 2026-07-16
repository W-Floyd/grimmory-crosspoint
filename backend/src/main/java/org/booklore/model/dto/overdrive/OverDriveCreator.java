package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A creator (author, narrator, etc.) for an OverDrive item.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveCreator {
    private String name;
    private String role;
}