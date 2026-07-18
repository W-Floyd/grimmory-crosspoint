package org.booklore.model.dto.overdrive;

/**
 * Availability of a single OverDrive catalog title at one specific library (advantage key). A title
 * that surfaces from several of the user's selected libraries carries one of these per library, so
 * the UI can tell which cards can borrow it now ({@code available}) vs only place a hold
 * ({@code holdable}).
 */
public record OverDriveLibraryAvailability(
        String libraryKey,
        boolean available,
        boolean holdable,
        Integer availableCopies,
        Integer ownedCopies,
        Integer holdsCount,
        Integer estimatedWaitDays,
        /** "Lucky Day" copies at this library, borrowable now without a hold. */
        Integer luckyDayAvailableCopies
) {}
