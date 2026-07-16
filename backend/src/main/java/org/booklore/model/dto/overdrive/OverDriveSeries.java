package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Series information for an OverDrive book.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveSeries {
    private String seriesName;
    private String readingOrder;
}