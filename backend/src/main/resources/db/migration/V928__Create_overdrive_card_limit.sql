-- Borrow-rate ceilings for a library card, and the index that makes checking them cheap.
--
-- OverDrive does not publish the rate it will tolerate. PatronExceededChurningLimit arrives with no
-- numbers attached — only "too many titles borrowed and returned within a short period of time" — so
-- the real ceiling has to be learned by watching what was happening when it fired, and then written
-- down here. That is why every column is nullable: unset means "no ceiling known", not "zero".
--
-- Keyed on identity rather than (user, identity) because the limit belongs to the library patron. A
-- card shared with three Grimmory users is one account at the library, and its budget is spent by
-- whichever of them borrows.
CREATE TABLE overdrive_card_limit (
    identity       VARCHAR(255) NOT NULL,
    max_per_minute INT          NULL,
    max_per_hour   INT          NULL,
    max_per_day    INT          NULL,
    max_per_week   INT          NULL,
    updated_at     TIMESTAMP    NULL,
    PRIMARY KEY (identity)
);

-- Counting a card's recent borrows means asking overdrive_audit "how many on this card since T".
-- V912 indexed (user_id, created_at) for the history view, which does not serve that question: the
-- automation asks per card, across every user who holds it.
CREATE INDEX idx_overdrive_audit_identity_created ON overdrive_audit (identity, created_at);
