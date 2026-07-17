package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A stored OverDrive/Libby auth token for a single linked library card. A user may have several
 * (across one or more Libby accounts); cards from the same setup code share a token value. Persisted
 * so linked cards survive restarts and background tasks (auto-return) can act without re-supply.
 */
@Entity
@Table(name = "overdrive_token")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The Grimmory user that owns this linked card. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** OverDrive identity / card id this token authenticates. Unique per user. */
    @Column(name = "identity", length = 255, nullable = false)
    private String identity;

    /** Human-friendly card/library name for display in the card picker. */
    @Column(name = "card_name", length = 255)
    private String cardName;

    /** OverDrive library "advantage key" (e.g. "lapl") for this card, used to scope catalog search. */
    @Column(name = "library_key", length = 255)
    private String libraryKey;

    /** The bearer token used for authenticated OverDrive calls. */
    @Column(name = "token", length = 4096, nullable = false)
    private String token;

    /** Epoch-seconds expiry hint (best-effort; OverDrive may reject earlier). */
    @Column(name = "expires_at")
    private Long expiresAt;

    /** OverDrive websiteId for the card's library (for the card-link/auth endpoints). Card+PIN links only. */
    @Column(name = "website_id", length = 32)
    private String websiteId;

    /** ILS name for the card's library (e.g. "jocolibks"), used when re-linking. Card+PIN links only. */
    @Column(name = "ils_name", length = 128)
    private String ilsName;

    /** AES-GCM encrypted library card number, for silent re-link when the token expires (opt-in). */
    @Column(name = "cred_card", length = 512)
    private String credCard;

    /** AES-GCM encrypted library card PIN, for silent re-link when the token expires (opt-in). */
    @Column(name = "cred_pin", length = 512)
    private String credPin;

    /** Default destination library for borrow &amp; import on this card; null falls back to Bookdrop. */
    @Column(name = "default_library_id")
    private Long defaultLibraryId;

    /** Default destination library path within {@link #defaultLibraryId}. */
    @Column(name = "default_path_id")
    private Long defaultPathId;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
