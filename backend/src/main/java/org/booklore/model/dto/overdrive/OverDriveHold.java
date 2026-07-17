package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * A hold item from OverDrive sync.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveHold {
    private String id;
    private String title;
    /** OverDrive often puts the real book name here (title is the series/franchise, e.g. "Star Wars"). */
    private String subtitle;
    private String estimatedWaitDays;
    /** Flat primary-author name from sync (sync omits the {@code creators} array, mirroring loans). */
    private String firstCreatorName;
    private List<OverDriveCreator> creators;
    /** Cover images from sync, used to show a thumbnail like loans do. */
    private OverDriveCover covers;
}