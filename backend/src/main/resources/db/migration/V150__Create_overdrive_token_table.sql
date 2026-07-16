-- Persist OverDrive/Libby auth tokens (one per Grimmory user) so a linked account survives restarts
-- and background tasks (auto-return) can act on loans without the token being re-supplied by the UI.

CREATE TABLE IF NOT EXISTS overdrive_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    identity VARCHAR(255) NOT NULL,
    token VARCHAR(4096) NOT NULL,
    expires_at BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_overdrive_token_user UNIQUE (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
