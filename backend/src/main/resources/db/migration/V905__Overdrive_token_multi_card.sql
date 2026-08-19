-- Support multiple linked Libby cards per user (across one or more setup codes). Each row becomes a
-- single (user, card) token instead of one token per user: add a display name, and move uniqueness
-- from (user_id) to (user_id, identity). Existing rows (one per user) satisfy the new constraint.

ALTER TABLE overdrive_token ADD COLUMN card_name VARCHAR(255);

ALTER TABLE overdrive_token DROP INDEX uq_overdrive_token_user;
ALTER TABLE overdrive_token ADD CONSTRAINT uq_overdrive_token_user_identity UNIQUE (user_id, identity);

CREATE INDEX idx_overdrive_token_user ON overdrive_token(user_id);
