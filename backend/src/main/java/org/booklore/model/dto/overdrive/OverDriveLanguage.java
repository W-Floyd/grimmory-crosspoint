package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A language for an OverDrive book.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveLanguage {
    private String name;
    private String language;
}