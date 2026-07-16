package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A subject/category for an OverDrive book.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveSubject {
    private String name;
}