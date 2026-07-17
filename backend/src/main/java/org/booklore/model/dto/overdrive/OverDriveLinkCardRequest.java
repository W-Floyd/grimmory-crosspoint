package org.booklore.model.dto.overdrive;

import lombok.Data;

/**
 * Request to link a library card directly by number + PIN (produces a fulfillment-capable primary
 * chip, unlike a setup-code clone). {@code libraryKey} is the OverDrive advantage key (e.g. "lapl").
 */
@Data
public class OverDriveLinkCardRequest {
    private String libraryKey;
    private String cardNumber;
    private String pin;
}
