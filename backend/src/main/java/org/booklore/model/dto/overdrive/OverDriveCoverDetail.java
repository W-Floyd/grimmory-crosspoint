package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * A single cover image detail.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveCoverDetail {
    private String href;
    private String format;
    private int width;
    private int height;
}