package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    /** True when the hold is ready to borrow now (a copy is reserved for the holder). Sync field {@code isAvailable}. */
    @JsonProperty("isAvailable")
    private Boolean available;
    /** For a ready hold, the deadline to borrow it before the hold is released. */
    private String expireDate;
    /** Flat primary-author name from sync (sync omits the {@code creators} array, mirroring loans). */
    private String firstCreatorName;
    private List<OverDriveCreator> creators;
    /** Cover images from sync, used to show a thumbnail like loans do. */
    private OverDriveCover covers;
}