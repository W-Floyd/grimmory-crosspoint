package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The deployment-wide borrow ceilings a card falls back to when it sets none of its own.
 *
 * <p>One row, pinned to id 1. These used to be constants in the service, which meant the only way to
 * change what an unconfigured card was allowed was to ship a new build — awkward for a number whose
 * whole purpose is to be adjusted as evidence about the account accumulates.
 *
 * <p>Null here means the same as it does on a card: no ceiling for that window. There is nothing
 * further to inherit from, so a null default is genuinely unlimited.
 */
@Entity
@Table(name = "overdrive_borrow_limit_default")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveBorrowLimitDefaultEntity {

    /** Always 1: a deployment has one default, and the primary key is what enforces that. */
    public static final byte SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Byte id;

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
