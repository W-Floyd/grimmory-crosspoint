-- When a card is resting after OverDrive told us it had been churning titles.
--
-- Borrowing and returning the same account repeatedly trips PatronExceededChurningLimit at Thunder:
--
--   "There have been too many titles borrowed and returned from your account within a short period
--    of time. Please try again in several days. If you're still not able to borrow titles after 7
--    days, please contact support."
--
-- Automation is what makes this easy to hit — a poller that borrows what comes in and hands back what
-- has aged out is a borrow/return cycle by construction. Left alone it would keep trying every couple
-- of hours, which is both useless (the borrows are refused) and exactly the behaviour the limit
-- exists to discourage.
--
-- So the card stops for the period the message names. Returns stop too, not just borrows: an early
-- return is half of a churn cycle, and going quiet on one side while still cycling on the other is
-- not going quiet. Syncing continues — it only reads, and the loan cache has to stay honest.
-- Guarded because MariaDB does not roll DDL back. A migration that fails after this statement leaves
-- the change behind but unrecorded, and FlywayConfig repairs and retries on the next boot — which
-- re-runs this statement, fails on the duplicate, and crash-loops the container. Idempotent DDL makes
-- the retry the harmless thing it is supposed to be.
ALTER TABLE overdrive_token
    ADD COLUMN IF NOT EXISTS churn_cooldown_until TIMESTAMP NULL;

-- Seed the cooldown from history, so the first poll after this deploys does not walk straight back
-- into the limit.
--
-- The account was already flagged before Grimmory could act on it: the failures are sitting in
-- overdrive_audit as refused borrows, and without this the poller would resume in the usual couple of
-- hours and start attempting them again. Backdating from the last recorded refusal serves the rest of
-- the period rather than starting a fresh seven days from deploy time, so a card flagged six days ago
-- is nearly free rather than newly grounded.
--
-- Matched on identity alone, not (user, identity): the limit belongs to the library patron, so every
-- Grimmory user holding that card is looking at the same flagged account and all of their rows rest
-- together.
--
-- Cards whose last refusal is already more than the period old are left alone — they are free, and
-- freezing them now would punish an account that has served its time.
UPDATE overdrive_token t
   SET t.churn_cooldown_until = (
           SELECT TIMESTAMPADD(DAY, 7, MAX(a.created_at))
             FROM overdrive_audit a
            WHERE a.identity = t.identity
              AND a.success = FALSE
              AND a.detail LIKE '%PatronExceededChurningLimit%')
 WHERE (
           SELECT TIMESTAMPADD(DAY, 7, MAX(a.created_at))
             FROM overdrive_audit a
            WHERE a.identity = t.identity
              AND a.success = FALSE
              AND a.detail LIKE '%PatronExceededChurningLimit%') > NOW();
