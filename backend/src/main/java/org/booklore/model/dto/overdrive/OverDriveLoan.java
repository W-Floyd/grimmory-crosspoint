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
    /**
     * The card this loan sits on. Libby's {@code /chip/sync} is chip-scoped, not card-scoped: it returns
     * every card on the chip and tags each loan with its owning card, so this is what attributes a loan
     * rather than "which card we happened to ask for".
     */
    private String cardId;
    private String title;
    /** OverDrive often puts the real book name here (title is the series/franchise, e.g. "Star Wars"). */
    private String subtitle;
    private String expireDate;
    /** When the loan was checked out (ISO-8601 from Libby sync), for the "Borrowed" column. */
    private String checkoutDate;
    private String publishDate;
    /** Flat primary-author name from sync (the {@code creators} array isn't included in sync loans). */
    private String firstCreatorName;
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

    // Copy/queue counts at the loan's own library. The sync feed already carries these, so the
    // "holding up the queue" view doesn't need a second round-trip to Thunder to learn them.

    /** Copies of this title borrowable right now at the loan's own library. */
    private Integer availableCopies;
    /** Copies of this title the loan's own library owns in total. */
    private Integer ownedCopies;
    /** How many holders are queued for this title at the loan's own library. */
    private Integer holdsCount;
    /** "Lucky Day" copies at the loan's own library, borrowable without a hold. */
    private Integer luckyDayAvailableCopies;
}