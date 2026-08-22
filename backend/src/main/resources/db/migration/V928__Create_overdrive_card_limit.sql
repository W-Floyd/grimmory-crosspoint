-- Borrow-rate ceilings for a library card, and the index that makes checking them cheap.
--
-- OverDrive does not publish the rate it will tolerate. PatronExceededChurningLimit arrives with no
-- numbers attached — only "too many titles borrowed and returned within a short period of time" — so
-- the real ceiling has to be learned by watching what was happening when it fired, and then written
-- down here. That is why every column is nullable: unset means "no ceiling set here", not "zero" — a
-- card with no row at all falls back to conservative defaults in code rather than to no ceiling.
--
-- Keyed on identity rather than (user, identity) because the limit belongs to the library patron. A
-- card shared with three Grimmory users is one account at the library, and its budget is spent by
-- whichever of them borrows.
-- Guarded because MariaDB does not roll DDL back. A migration that fails after this statement leaves
-- the change behind but unrecorded, and FlywayConfig repairs and retries on the next boot — which
-- re-runs this statement, fails on the duplicate, and crash-loops the container. Idempotent DDL makes
-- the retry the harmless thing it is supposed to be.
CREATE TABLE IF NOT EXISTS overdrive_card_limit (
    identity       VARCHAR(255) NOT NULL,
    max_per_minute INT          NULL,
    max_per_hour   INT          NULL,
    max_per_day    INT          NULL,
    max_per_week   INT          NULL,
    -- Thirty days is the window this deployment's refusals actually correlate with: two, at 148 and
    -- 144 borrows in the preceding month, on a card that had already survived a 68-borrow week.
    max_per_month  INT          NULL,
    updated_at     TIMESTAMP    NULL,
    PRIMARY KEY (identity)
);

-- Counting a card's recent borrows means asking overdrive_audit "how many on this card since T".
-- V912 indexed (user_id, created_at) for the history view, which does not serve that question: the
-- automation asks per card, across every user who holds it.
CREATE INDEX IF NOT EXISTS idx_overdrive_audit_identity_created ON overdrive_audit (identity, created_at);
