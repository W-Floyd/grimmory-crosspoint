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

    /**
     * When this card may act again after OverDrive reported it as churning titles, or null when it is
     * free to. Covers borrows and returns alike — an early return is half of a borrow/return cycle, so
     * pausing one while continuing the other would not be pausing at all.
     */
    @Column(name = "churn_cooldown_until")
    private java.time.Instant churnCooldownUntil;

    /**
     * When this card was frozen, or null while it is free to act. Stronger than
     * {@link #churnCooldownUntil} in three ways: that one lets fulfillment through — and the automatic
     * freeze exists precisely because fulfillment is what was refused — it leaves reads alone where a
     * freeze stops those too, and it expires where a freeze does not. Cleared by an explicit unfreeze,
     * or by a deliberate re-link when the refusal rather than a person imposed it.
     */
    @Column(name = "frozen_at")
    private java.time.Instant frozenAt;

    /** Why the card is frozen, in the words shown to whoever asks it to act. */
    @Column(name = "frozen_reason")
    private String frozenReason;

    /**
     * Who stopped the card, or null when the refusal itself did. The difference decides what lifts it:
     * an automatic freeze is a retry a re-link may fix, while a person's freeze is a decision, and a
     * refresh running for its own reasons has no business overturning one.
     */
    @Column(name = "frozen_by")
    private Long frozenBy;
}
