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
    /**
     * The card this hold sits on. Libby's {@code /chip/sync} is chip-scoped, not card-scoped: it returns
     * every card on the chip and tags each hold with its owning card, so this is what attributes a hold
     * rather than "which card we happened to ask for".
     */
    private String cardId;
    private String title;
    /** OverDrive often puts the real book name here (title is the series/franchise, e.g. "Star Wars"). */
    private String subtitle;
    private String estimatedWaitDays;
    /** When the hold was placed (ISO-8601 from Libby sync), for the "Placed" column. */
    private String placedDate;
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

    // Queue position and copy counts at the hold's own library. The sync feed already carries these, so
    // the Holds tab only needs to call Thunder for the user's *other* libraries.

    /** This hold's place in the queue at its own library (1 = next up). */
    private Integer holdListPosition;
    /** How many holders are queued for this title at the hold's own library. */
    private Integer holdsCount;
    /** Copies of this title borrowable right now at the hold's own library. */
    private Integer availableCopies;
    /** Copies of this title the hold's own library owns in total. */
    private Integer ownedCopies;
    /** "Lucky Day" copies at the hold's own library, borrowable without a hold. */
    private Integer luckyDayAvailableCopies;
    /** Whether the title can still be held at this library. Sync field {@code isHoldable}. */
    @JsonProperty("isHoldable")
    private Boolean holdable;
}