-- Give each deployment its own base schedule for the OverDrive poller.
--
-- V917 seeded a fixed '0 0 */2 * * *', so every install that enables the task would poll Libby at
-- exactly midnight, 02:00, 04:00 ... together. Per-firing jitter spreads a single install across its
-- window, but it cannot separate installs from each other: they all start from the same slot.
--
-- This picks a random minute and a random one of the two even/odd hour phases, once, at migration
-- time — so this deployment lands on (say) '0 37 1/2 * * *' and stays there. Still every two hours,
-- still adjustable in Settings -> Tasks, but no longer in lockstep with every other deployment.
--
-- Only rewrites the untouched V917 default: an operator who has already chosen a schedule keeps it.
UPDATE task_cron_configuration
   SET cron_expression = CONCAT('0 ', FLOOR(RAND() * 60), ' ', FLOOR(RAND() * 2), '/2 * * *')
 WHERE task_type = 'OVERDRIVE_AUTO_SYNC'
   AND cron_expression = '0 0 */2 * * *';
