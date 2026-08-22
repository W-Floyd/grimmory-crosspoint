package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * How fast a library card may be borrowed against, as configured by an administrator.
 *
 * <p>Keyed on the card identity rather than on a Grimmory user: the ceiling belongs to the library
 * patron, so a card shared between users has one budget that all of them spend from.
 *
 * <p>Every ceiling is nullable, and null means "not known" rather than zero. OverDrive publishes no
 * numbers — the churning limit arrives as prose — so a ceiling only exists once somebody has watched
 * the account trip and written down what it was doing.
 */
@Entity
@Table(name = "overdrive_card_limit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveCardLimitEntity {

    @Id
    @Column(name = "identity", length = 255, nullable = false)
    private String identity;

    @Column(name = "max_per_minute")
    private Integer maxPerMinute;

    @Column(name = "max_per_hour")
    private Integer maxPerHour;

    @Column(name = "max_per_day")
    private Integer maxPerDay;

    @Column(name = "max_per_week")
    private Integer maxPerWeek;

    /** Over a rolling thirty days — the window this deployment's refusals actually correlate with. */
    @Column(name = "max_per_month")
    private Integer maxPerMonth;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
