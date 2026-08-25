-- A deployment-wide default for the borrow ceilings, so cards nobody has configured follow one
-- number an administrator can move rather than a constant compiled into the jar.
--
-- Guarded because MariaDB does not roll DDL back. A migration that fails after a statement leaves the
-- change behind but unrecorded, and FlywayConfig repairs and retries on the next boot — which re-runs
-- it and crash-loops the container. Idempotent DDL makes the retry the harmless thing it should be.
CREATE TABLE IF NOT EXISTS overdrive_borrow_limit_default (
    -- Single row, pinned. A deployment has one default; the primary key is what stops a second.
    id             TINYINT   NOT NULL DEFAULT 1,
    max_per_minute INT       NULL,
    max_per_hour   INT       NULL,
    max_per_day    INT       NULL,
    max_per_week   INT       NULL,
    max_per_month  INT       NULL,
    updated_at     TIMESTAMP NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_overdrive_borrow_limit_default_single CHECK (id = 1)
);

-- Seeded with exactly the constants this replaces, so upgrading changes no card's behaviour: 100 per
-- thirty days sits below the 144-148 band where this deployment's refusals actually came, and the
-- shorter windows are burst insurance against a loop rather than measured limits.
INSERT IGNORE INTO overdrive_borrow_limit_default
    (id, max_per_minute, max_per_hour, max_per_day, max_per_week, max_per_month, updated_at)
VALUES (1, 2, 5, 10, 30, 100, NOW());

-- A card sitting on exactly the built-in numbers was never expressing a preference for them — it was
-- accepting the default. Drop those rows so that moving the default moves them: no row already means
-- "this card says nothing", which is exactly what they meant, and leaves nothing to keep in sync.
--
-- This runs before the null rewrite below, and that order is load-bearing. Nulling these rows instead
-- would leave the rewrite unable to tell a card that inherits from a card that opted out, so a repair
-- and retry — which re-runs the whole file, having already committed part of it — would read its own
-- output back and turn every inheriting card into an unlimited one.
DELETE FROM overdrive_card_limit
 WHERE max_per_minute = 2 AND max_per_hour = 5 AND max_per_day = 10
   AND max_per_week = 30 AND max_per_month = 100;

-- Null used to mean "no ceiling for this window" on a saved row, which made a row of blanks mean
-- unlimited — the opposite of what no row at all meant, and not what anyone typing blanks expects.
-- Null now means "inherit the default", so the old meaning needs somewhere to live: -1.
--
-- Idempotent by construction: it leaves no nulls behind, the statement above creates none, and the
-- application is not serving while migrations run, so nothing else can introduce one mid-retry. On a
-- deployment that never configured a card it matches nothing, which is the intended no-op.
UPDATE overdrive_card_limit
   SET max_per_minute = COALESCE(max_per_minute, -1),
       max_per_hour   = COALESCE(max_per_hour, -1),
       max_per_day    = COALESCE(max_per_day, -1),
       max_per_week   = COALESCE(max_per_week, -1),
       max_per_month  = COALESCE(max_per_month, -1);
