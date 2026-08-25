package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * What an external download handler printed during one run.
 *
 * <p>Stored as a single blob rather than a row per line: the output is written once, read rarely, and
 * only ever read whole, so a row per line would buy nothing and cost thousands of inserts per pass.
 */
@Entity
@Table(name = "overdrive_tool_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveToolLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title_id", length = 255)
    private String titleId;

    @Column(name = "identity", length = 255)
    private String identity;

    /** Whether the automation produced this — the run nobody was watching, and the reason to keep it. */
    @Column(name = "automated", nullable = false)
    private boolean automated;

    @Column(name = "succeeded", nullable = false)
    private boolean succeeded;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Lob
    @Column(name = "output", columnDefinition = "MEDIUMTEXT")
    private String output;
}
