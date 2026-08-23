-- What happened around a card that was supposed to be resting.
--
-- Read-only. The cooldown is stored as its end (overdrive_token.churn_cooldown_until) and always runs
-- seven days, so the start is that value minus seven days — every query below derives it that way.
--
-- Query 2 is the one that matters: actions recorded against a card during its own cooldown. Borrows,
-- returns and holds are all supposed to be refused in that window, so anything listed there is either
-- a path that does not check, or a check that is not working.

SELECT '=== 1. Cards currently resting, and when their cooldown began ===' AS report;

SELECT DISTINCT
    t.identity,
    t.card_name,
    TIMESTAMPADD(DAY, -7, t.churn_cooldown_until) AS resting_since,
    t.churn_cooldown_until                        AS resting_until,
    t.churn_cooldown_until > NOW()                AS still_resting
FROM overdrive_token t
WHERE t.churn_cooldown_until IS NOT NULL
ORDER BY t.churn_cooldown_until DESC;

SELECT '=== 2. Actions taken against a card DURING its cooldown (should be none) ===' AS report;

SELECT
    a.created_at,
    a.identity,
    a.action,
    a.success,
    LEFT(COALESCE(a.title, a.title_id, ''), 40) AS title,
    LEFT(COALESCE(a.detail, ''), 120)           AS detail
FROM overdrive_audit a
JOIN (SELECT DISTINCT identity COLLATE utf8mb4_general_ci AS identity, churn_cooldown_until
        FROM overdrive_token WHERE churn_cooldown_until IS NOT NULL) t
  ON a.identity COLLATE utf8mb4_general_ci = t.identity
WHERE a.created_at > TIMESTAMPADD(DAY, -7, t.churn_cooldown_until)
  AND a.created_at <= LEAST(t.churn_cooldown_until, NOW())
  -- Card bookkeeping is expected during a rest; only library actions are not.
  AND a.action IN ('BORROW','BORROW_AND_IMPORT','IMPORT','RETURN','AUTO_RETURN',
                   'HOLD_PLACED','HOLD_CANCELLED','DOWNLOAD')
ORDER BY a.created_at;

SELECT '=== 3. Every churning refusal, newest first ===' AS report;

SELECT created_at, identity, action, LEFT(detail, 200) AS detail
FROM overdrive_audit
WHERE detail LIKE '%PatronExceededChurningLimit%'
   OR detail LIKE '%Churning limit hit%'
ORDER BY created_at DESC
LIMIT 20;

SELECT '=== 4. Last 60 actions overall, to see the shape of the run ===' AS report;

SELECT created_at, identity, action, success,
       LEFT(COALESCE(title, title_id, ''), 40) AS title,
       LEFT(COALESCE(detail, ''), 90)          AS detail
FROM overdrive_audit
ORDER BY created_at DESC
LIMIT 60;

SELECT '=== 5. Scheduler state — a stored firing in the past runs within 5 min of boot ===' AS report;

SELECT task_type, enabled, cron_expression, jitter_seconds, next_run_at,
       next_run_at < NOW() AS overdue_so_will_run_on_next_boot
FROM task_cron_configuration
WHERE task_type = 'OVERDRIVE_AUTO_SYNC';

SELECT '=== 6. What the bookbag currently believes ===' AS report;

SELECT id, position, title_id, LEFT(COALESCE(title,''), 40) AS title,
       hold_card_id, allow_reborrow, last_tried_at, LEFT(COALESCE(last_note,''), 100) AS last_note
FROM overdrive_bookbag
ORDER BY position, id;

SELECT '=== 7. Loan cache: what the account is holding, per card ===' AS report;

SELECT identity, state, COUNT(*) AS loans, SUM(fulfilled) AS fulfilled,
       MIN(created_at) AS oldest, MAX(created_at) AS newest
FROM overdrive_loan
GROUP BY identity, state
ORDER BY identity, state;

SELECT '=== 8. Configured ceilings (absent row = the code defaults apply) ===' AS report;

SELECT identity, max_per_minute, max_per_hour, max_per_day, max_per_week, max_per_month, updated_at
FROM overdrive_card_limit;
