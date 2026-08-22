package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One title a user wants, waiting for a card with the room and the budget to borrow it.
 *
 * <p>The bag is a standing intention rather than a list of attempts: an entry stays until the title
 * is actually borrowed, and a pass that cannot borrow it places a hold and moves on to the next.
 */
@Entity
@Table(name = "overdrive_bookbag")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveBookbagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The OverDrive title id — the same id a loan carries, and the key the library is matched on. */
    @Column(name = "title_id", length = 255, nullable = false)
    private String titleId;

    @Column(name = "title", length = 1024)
    private String title;

    @Column(name = "author", length = 255)
    private String author;

    /** Sort key. Gaps are expected — entries are ordered by it, never counted with it. */
    @Column(name = "position", nullable = false)
    @Builder.Default
    private int position = 0;

    /** Where a hold was placed while waiting for a copy, so the entry is not held twice. */
    @Column(name = "hold_card_id", length = 255)
    private String holdCardId;

    @Column(name = "hold_placed_at")
    private Instant holdPlacedAt;

    /** Why the last pass could not borrow it, in words the user can read. Cleared on success. */
    @Column(name = "last_note", length = 512)
    private String lastNote;

    @Column(name = "last_tried_at")
    private Instant lastTriedAt;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
