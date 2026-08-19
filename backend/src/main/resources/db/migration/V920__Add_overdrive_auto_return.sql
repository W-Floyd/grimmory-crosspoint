-- Per-user opt-in for automatically returning loans once they have been held long enough.
--
-- Two behaviours, deliberately different:
--
--   * Normally the return happens at a random point in a window *after* the minimum age, not on the
--     first poll that crosses it. Returning every eligible loan the instant it ages out would put a
--     visible, uniform "returned at exactly N days" signature on the account.
--
--   * When somebody is waiting for the title, the return happens as soon as the minimum age is
--     reached instead. Holding a copy past the point you agreed to keep it, purely to look organic,
--     costs a real person real waiting time. That trade goes the other way.
ALTER TABLE overdrive_auto_sync
    ADD COLUMN auto_return_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    -- How long a loan must be held before it becomes eligible to be returned at all.
    ADD COLUMN auto_return_min_age_days     INT     NOT NULL DEFAULT 14,
    -- Width of the random window after that age. 0 returns exactly at the minimum age.
    ADD COLUMN auto_return_max_delay_hours  INT     NOT NULL DEFAULT 48,
    -- Skip the random delay when the title has holds queued, so waiters are not held up.
    ADD COLUMN auto_return_prompt_when_waitlisted BOOLEAN NOT NULL DEFAULT TRUE;

-- The moment this loan becomes due for automatic return, chosen once and then left alone.
--
-- It has to be persisted rather than recomputed each poll: drawing a fresh random delay on every tick
-- would re-roll the due time continuously, so a loan could drift indefinitely and never actually come
-- due. Cleared whenever the settings change, so a new window is drawn against the new configuration.
ALTER TABLE overdrive_loan
    ADD COLUMN auto_return_due_at TIMESTAMP NULL;

CREATE INDEX idx_overdrive_loan_auto_return ON overdrive_loan (auto_return_due_at);
