package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * A loan item from OverDrive sync.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveLoan {
    private String id;
    private String title;
    private String expireDate;
    private String publishDate;
    private List<OverDriveCreator> creators;
    private OverDriveFormat format;
    private String fullDescription;
    private String description;
    private List<OverDriveSubject> subjects;
    private List<OverDriveLanguage> languages;
    private OverDrivePublisher publisher;
    private OverDriveCover covers;
    private OverDriveSeries detailedSeries;
    private Double starRating;
    private List<OverDriveIdentifier> identifiers;
    private List<OverDriveFormat> formats;
}