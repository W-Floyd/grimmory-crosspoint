-- Bring the stored deployment default down to numbers no ordinary reader would reach from below.
--
-- V933's numbers (2/5/10/30/100) were read off the refusals: the account was refused at 144-148
-- borrows in a rolling thirty days, so a hundred sat "comfortably below" that. Sizing a ceiling to the
-- edge of what got the account flagged is the wrong target, and the same card has since been refused
-- fulfillment outright rather than merely rate-limited. A default should be a number reached only by
-- someone doing something unusual, not a number reached by ordinary automation on a busy week.
--
-- Only rows still on exactly the old seed are moved. A deployment whose administrator has typed their
-- own numbers has expressed a preference, and a migration is not the place to overrule it — those are
-- left alone and can be lowered from the admin screen.
UPDATE overdrive_borrow_limit_default
   SET max_per_minute = 1,
       max_per_hour   = 2,
       max_per_day    = 3,
       max_per_week   = 10,
       max_per_month  = 40,
       updated_at     = NOW()
 WHERE max_per_minute = 2 AND max_per_hour = 5 AND max_per_day = 10
   AND max_per_week = 30 AND max_per_month = 100;

-- Per-card rows that merely restate the old seed are accepting the default rather than expressing a
-- preference, exactly as V933 reasoned about the built-in numbers. Drop them so that moving the
-- default moves them too; no row means "this card says nothing", which is what they meant.
DELETE FROM overdrive_card_limit
 WHERE max_per_minute = 2 AND max_per_hour = 5 AND max_per_day = 10
   AND max_per_week = 30 AND max_per_month = 100;
