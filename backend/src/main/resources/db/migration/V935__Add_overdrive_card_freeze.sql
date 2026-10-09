-- When a card was frozen after OverDrive refused to fulfill on it at all.
--
-- Distinct from churn_cooldown_until, and deliberately so. The churn cooldown is OverDrive asking us
-- to borrow less for a week, and it lets fulfillment through on purpose: finishing a loan already
-- taken spends no checkout, so there is no reason to leave a paid-for copy unfetched. A "whoa" is the
-- opposite situation — the fulfill endpoint itself returns 403 with OverDrive's reverse-engineering
-- notice — so the one thing the cooldown permits is the one thing that must stop.
--
-- A freeze also stops reads, which the cooldown leaves alone. Syncing costs the account nothing by
-- itself, but a frozen card is one that has been refused or deliberately stood down, and the useful
-- property is that it stops appearing upstream at all. The price is that its cached loans stand still
-- until it is unfrozen.
--
-- Observed shape of the failure (card 107603589, 2026-09-22): every pass fulfilled with the stored
-- token and got missing_chip, re-minted and got missing_chip again, re-linked from the stored card +
-- PIN to a brand new chip, and that fresh identity got "whoa". The re-link is the recovery, so a
-- "whoa" after one means recovery is exhausted: retrying only mints another chip on a card that has
-- already refused six of them in a day.
--
-- A timestamp, not a deadline, because the freeze does not expire. Nothing observed says the refusal
-- lifts with time, so there is no period to serve — a card comes back when a person re-links it
-- deliberately, which is the only event that means something actually changed. frozen_at records when
-- it stopped, for the history; frozen_reason is what the refusal said, shown to whoever asks the card
-- to act. Frozen is frozen_at IS NOT NULL.
--
-- Guarded because MariaDB does not roll DDL back. A migration that fails after this statement leaves
-- the change behind but unrecorded, and FlywayConfig repairs and retries on the next boot — which
-- re-runs this statement, fails on the duplicate, and crash-loops the container. Idempotent DDL makes
-- the retry the harmless thing it is supposed to be.
--
-- frozen_by separates the two ways a card stops. Null is the automatic freeze above, which a
-- deliberate re-link lifts: a person re-linking has changed something, and that is the retry worth
-- allowing. A user id is somebody stopping the card on purpose — their own, or an administrator's with
-- a reason posted against it — and that survives a re-link, because a card taken out of service should
-- not come back because an unrelated refresh happened to run.
ALTER TABLE overdrive_token
    ADD COLUMN IF NOT EXISTS frozen_at TIMESTAMP NULL,
    ADD COLUMN IF NOT EXISTS frozen_reason VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS frozen_by BIGINT NULL;

-- Seed from history, so a card that is failing right now stops on deploy rather than on its next
-- refusal. Without this the freeze would only take hold after one more pass had minted one more chip.
--
-- Matched on identity alone, like V927: the refusal belongs to the library patron, so every Grimmory
-- user holding that card is looking at the same refused account and all of their rows freeze together.
--
-- Recent refusals only. A card refused months ago and working since is not frozen — it recovered, and
-- grounding it now on the strength of an old row would take a working card out of service.
--
-- Both sides of the identity comparison are collated explicitly. overdrive_audit is utf8mb4_general_ci
-- and overdrive_token utf8mb4_unicode_ci on at least one real database, and MariaDB refuses to compare
-- two implicit collations that differ. Same charset either way, so no character can be lost.
UPDATE overdrive_token t
   SET t.frozen_at = (
           SELECT MAX(a.created_at)
             FROM overdrive_audit a
            WHERE a.identity COLLATE utf8mb4_general_ci = t.identity COLLATE utf8mb4_general_ci
              AND a.success = FALSE
              AND a.detail LIKE '%not fulfillment-capable%'),
       t.frozen_reason = 'OverDrive refused fulfillment on this card ("whoa") even after a re-link.'
 WHERE (
           SELECT MAX(a.created_at)
             FROM overdrive_audit a
            WHERE a.identity COLLATE utf8mb4_general_ci = t.identity COLLATE utf8mb4_general_ci
              AND a.success = FALSE
              AND a.detail LIKE '%not fulfillment-capable%') > NOW() - INTERVAL 30 DAY;
