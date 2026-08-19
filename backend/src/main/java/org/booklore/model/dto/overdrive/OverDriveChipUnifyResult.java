package org.booklore.model.dto.overdrive;

import java.util.List;

/**
 * Outcome of consolidating a user's cards onto one Libby identity.
 *
 * @param targetCardId the card whose chip the others were moved onto
 * @param moved        cards now sharing that chip
 * @param skipped      cards left where they were, each with the reason
 */
public record OverDriveChipUnifyResult(String targetCardId, List<String> moved, List<Skipped> skipped) {

    /** A card that could not be moved, and why — surfaced so the user can act on it. */
    public record Skipped(String cardId, String cardName, String reason) {}
}
