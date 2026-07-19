package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One entry in a user's OverDrive activity history. Append-only. Card name/library key are
 * denormalised at write time so the entry still reads correctly after the card is relabelled or
 * unlinked.
 */
@Entity
@Table(name = "overdrive_audit")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user this history entry belongs to. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The action name ({@link org.booklore.model.enums.OverDriveAuditAction}). */
    @Column(name = "action", length = 40, nullable = false)
    private String action;

    /** OverDrive card identity the action targeted, if any. */
    @Column(name = "identity", length = 255)
    private String identity;

    @Column(name = "library_key", length = 255)
    private String libraryKey;

    /** Card display name at the time of the action (denormalised snapshot). */
    @Column(name = "card_name", length = 255)
    private String cardName;

    @Column(name = "title_id", length = 255)
    private String titleId;

    @Column(name = "loan_id", length = 255)
    private String loanId;

    @Column(name = "book_id")
    private Long bookId;

    /** Title of the affected book, where known. */
    @Column(name = "title", length = 1024)
    private String title;

    /** Free-text extra context (e.g. destination library, share count). */
    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "success", nullable = false)
    @Builder.Default
    private boolean success = true;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
