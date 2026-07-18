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
    private List<Card> cards;
    private String token;

    /** A card entry in the sync, carrying the account's loan/hold usage vs. limits. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Card {
        private String cardId;
        private Limits limits;
        private Counts counts;
        private Boolean canPlaceHolds;
    }

    /** Maximum loans/holds the card allows. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Limits {
        private Integer loan;
        private Integer hold;
    }

    /** Current number of loans/holds on the card. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Counts {
        private Integer loan;
        private Integer hold;
    }
}