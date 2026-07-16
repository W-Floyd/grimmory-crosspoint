package org.booklore.model.dto.overdrive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * Response from GET sentry.libbyapp.com/chip/sync
 * Contains loans, holds, and library information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OverDriveSyncResponse {
    private List<OverDriveLoan> loans;
    private List<OverDriveHold> holds;
    private List<OverDriveLibrary> libraries;
    private String token;
}