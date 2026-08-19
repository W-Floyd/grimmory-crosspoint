-- Seed the OverDrive auto-sync poller, disabled. Enabling it is an operator decision (it makes
-- Grimmory reach out to Libby unattended); which users it acts for is decided per user in
-- overdrive_auto_sync. Every two hours by default — holds stay collectable for days, so polling
-- harder buys nothing and only adds load on a third party.
INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
SELECT 'OVERDRIVE_AUTO_SYNC', '0 0 */2 * * *', FALSE, -1
WHERE NOT EXISTS (
    SELECT 1 FROM task_cron_configuration WHERE task_type = 'OVERDRIVE_AUTO_SYNC'
);
