package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Tracks an OverDrive/Libby loan within Grimmory's database.
 * Persisted so loans survive server restarts and can be managed from the UI.
 */
@Entity
@Table(name = "overdrive_loan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveLoanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

      /** OverDrive loan ID (from the chip sync response). */
    @Column(name = "overdrive_loan_id", length = 255, nullable = false)
    private String overdriveLoanId;

      /** OverDrive format ID (the format to fulfill). */
    @Column(name = "format_id", length = 255)
    private String formatId;

      /** Library identity / card ID. */
    @Column(name = "identity", length = 255)
    private String identity;

      /** The Grimmory user that borrowed this loan. */
    @Column(name = "user_id")
    private Long userId;

      /** The title of the borrowed book (cached). */
    @Column(name = "title", length = 512)
    private String title;

      /** The author(s) (cached). */
    @Column(name = "author", length = 255)
    private String author;

      /** When the loan expires in OverDrive. */
    @Column(name = "expire_date")
    private Instant expireDate;

      /** Loan state: BORROWED, ACTIVE, RETURNED, EXPIRED. */
    @Column(name = "state", length = 32, nullable = false)
    @Builder.Default
    private String state = "BORROWED";

      /** ISBN-13 of the book (for matching to a BookEntity). */
    @Column(name = "isbn", length = 13)
    private String isbn;

      /** FK to the BookEntity in Grimmory (if matched). */
    @Column(name = "book_id")
    private Long bookId;

      /** Whether this loan has been fulfilled (downloaded). */
    @Builder.Default
    private Boolean fulfilled = false;

      /** When the loan was created. */
    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();

      /** Last sync time from OverDrive. */
    @Column(name = "last_sync")
    private Instant lastSync;
}