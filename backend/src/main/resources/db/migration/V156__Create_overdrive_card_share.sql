-- Sharing of linked OverDrive/Libby cards with other users. A share grants the target user
-- borrow/hold/view access to the OWNER's existing overdrive_token row (the bearer token is never
-- duplicated); management (unlink, relabel, default library, refresh) stays with the owner.
CREATE TABLE overdrive_card_share (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    token_id             BIGINT       NOT NULL,
    shared_with_user_id  BIGINT       NOT NULL,
    created_at           TIMESTAMP    NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_overdrive_card_share UNIQUE (token_id, shared_with_user_id),
    CONSTRAINT fk_ocs_token FOREIGN KEY (token_id)
        REFERENCES overdrive_token (id) ON DELETE CASCADE,
    CONSTRAINT fk_ocs_user FOREIGN KEY (shared_with_user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_ocs_shared_with_user ON overdrive_card_share (shared_with_user_id);
