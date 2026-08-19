-- Optional random delay applied to each firing of a scheduled task.
--
-- A cron slot fires at exactly the same wall-clock time every run, which for a task that reaches out
-- to a third party makes the traffic trivially machine-shaped and synchronises every deployment onto
-- the same instant. Jitter slides each firing later by a random amount in [0, jitter_seconds].
--
-- Delay-only, never early: a negative offset could land before the trigger's last scheduled execution
-- and fire twice. Keep the value well under the cron interval — jitter approaching the interval would
-- push a firing past the following slot and skip it.
ALTER TABLE task_cron_configuration
    ADD COLUMN jitter_seconds INT NOT NULL DEFAULT 0;

-- The OverDrive poller talks to Libby, so give it a spread by default. 20 minutes on a two-hour
-- schedule is a wide window with no risk of overrunning the next slot.
UPDATE task_cron_configuration SET jitter_seconds = 1200 WHERE task_type = 'OVERDRIVE_AUTO_SYNC';
