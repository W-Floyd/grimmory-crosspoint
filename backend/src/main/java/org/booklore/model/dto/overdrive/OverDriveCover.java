package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Cover images for an OverDrive book.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveCover {
    private OverDriveCoverDetail cover150Wide;
    private OverDriveCoverDetail cover300Wide;
    private OverDriveCoverDetail cover510Wide;
}