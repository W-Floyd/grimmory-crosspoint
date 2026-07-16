package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Publisher information for an OverDrive book.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDrivePublisher {
    private String name;
}