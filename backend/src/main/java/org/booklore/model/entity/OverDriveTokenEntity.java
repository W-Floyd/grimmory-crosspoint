package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A stored OverDrive/Libby auth token, persisted so a linked account survives server restarts and
 * so background tasks (e.g. auto-return) can act on loans without the token being re-supplied.
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

    /** The Grimmory user that owns this Libby connection. */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** OverDrive identity / card id this token authenticates. */
    @Column(name = "identity", length = 255, nullable = false)
    private String identity;

    /** The bearer token used for authenticated OverDrive calls. */
    @Column(name = "token", length = 4096, nullable = false)
    private String token;

    /** Epoch-seconds expiry hint (best-effort; OverDrive may reject earlier). */
    @Column(name = "expires_at")
    private Long expiresAt;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
