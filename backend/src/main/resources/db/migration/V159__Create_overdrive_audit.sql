-- Per-user OverDrive activity history (borrows, returns, holds, imports/downloads, card & share
-- changes). Append-only; denormalised card_name/library_key so entries read correctly even after a
-- card is unlinked or relabelled.
CREATE TABLE overdrive_audit (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    user_id      BIGINT        NOT NULL,
    action       VARCHAR(40)   NOT NULL,
    identity     VARCHAR(255),
    library_key  VARCHAR(255),
    card_name    VARCHAR(255),
    title_id     VARCHAR(255),
    loan_id      VARCHAR(255),
    book_id      BIGINT,
    title        VARCHAR(1024),
    detail       VARCHAR(1024),
    success      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP     NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_overdrive_audit_user_created ON overdrive_audit (user_id, created_at);
