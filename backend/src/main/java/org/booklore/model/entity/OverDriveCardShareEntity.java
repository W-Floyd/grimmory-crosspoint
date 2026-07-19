package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A grant giving another Grimmory user borrow/hold/view access to a linked OverDrive card
 * ({@link OverDriveTokenEntity}). Access is to the owner's existing token row — the bearer token is
 * never copied — so token refresh/re-mint keeps working for every sharee. Card management (unlink,
 * relabel, default library, refresh) stays with the owner.
 */
@Entity
@Table(name = "overdrive_card_share")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverDriveCardShareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The shared card's token row id ({@link OverDriveTokenEntity#getId()}). */
    @Column(name = "token_id", nullable = false)
    private Long tokenId;

    /** The user this card is shared with. */
    @Column(name = "shared_with_user_id", nullable = false)
    private Long sharedWithUserId;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
