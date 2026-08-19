package org.booklore.model.dto.overdrive;

import lombok.Data;

/**
 * Request to link a library card directly by number + PIN (produces a fulfillment-capable primary
 * chip, unlike a setup-code clone). {@code libraryKey} is the OverDrive advantage key (e.g. "lapl").
 *
 * <p>{@code linkToCardId} adds the card to the Libby identity ("chip") an existing card already sits
 * on, instead of minting a fresh one. This is what the Libby web client itself does when an account
 * already has a card, and it means all those cards then sync in a single upstream call. Omit it to
 * create a separate identity, which is the right choice for a genuinely different Libby account.
 *
 * <p>{@code userId} links the card for another user — for a manager entering a card number + PIN the
 * user handed over. Omit it to link for yourself; supplying it requires permission to manage any user's
 * OverDrive cards.
 */
@Data
public class OverDriveLinkCardRequest {
    private String libraryKey;
    private String cardNumber;
    private String pin;
    private Long userId;
    private String linkToCardId;
}
