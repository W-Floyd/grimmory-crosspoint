-- What rate has this deployment actually borrowed at, and what tripped OverDrive's churning limit?
--
-- Read-only. Nothing here writes, so it is safe to run against a live database.
--
-- The point is to turn "too many titles borrowed and returned within a short period of time" into
-- numbers you can put in the per-card borrow limits. Query 1 is the one that matters: it says what
-- the account was doing in the minute, hour, day and week before each refusal. Set each ceiling
-- comfortably below the lowest number you see there.
--
-- A borrow is a successful BORROW or BORROW_AND_IMPORT. IMPORT is excluded throughout: it resumes a
-- loan already held and takes no new copy out, so it does not count against a borrow rate. Failures
-- are excluded too — a refused borrow is not a borrow, and counting our own retries would make a
-- limited card look busier the longer it stayed limited.

SELECT '=== 1. Each churning refusal, and the borrow rate leading up to it ===' AS report;

SELECT
    a.identity,
    a.created_at                                                         AS refused_at,
    (SELECT COUNT(*) FROM overdrive_audit b
      WHERE b.identity = a.identity AND b.success = 1
        AND b.action IN ('BORROW', 'BORROW_AND_IMPORT')
        AND b.created_at >  a.created_at - INTERVAL 1 MINUTE
        AND b.created_at <= a.created_at)                                AS prev_minute,
    (SELECT COUNT(*) FROM overdrive_audit b
      WHERE b.identity = a.identity AND b.success = 1
        AND b.action IN ('BORROW', 'BORROW_AND_IMPORT')
        AND b.created_at >  a.created_at - INTERVAL 1 HOUR
        AND b.created_at <= a.created_at)                                AS prev_hour,
    (SELECT COUNT(*) FROM overdrive_audit b
      WHERE b.identity = a.identity AND b.success = 1
        AND b.action IN ('BORROW', 'BORROW_AND_IMPORT')
        AND b.created_at >  a.created_at - INTERVAL 1 DAY
        AND b.created_at <= a.created_at)                                AS prev_day,
    (SELECT COUNT(*) FROM overdrive_audit b
      WHERE b.identity = a.identity AND b.success = 1
        AND b.action IN ('BORROW', 'BORROW_AND_IMPORT')
        AND b.created_at >  a.created_at - INTERVAL 7 DAY
        AND b.created_at <= a.created_at)                                AS prev_week,
    -- Returns count toward churning too, so the picture is incomplete without them.
    (SELECT COUNT(*) FROM overdrive_audit b
      WHERE b.identity = a.identity AND b.success = 1 AND b.action IN ('RETURN', 'AUTO_RETURN')
        AND b.created_at >  a.created_at - INTERVAL 1 DAY
        AND b.created_at <= a.created_at)                                AS returns_prev_day
FROM overdrive_audit a
WHERE a.detail LIKE '%PatronExceededChurningLimit%'
ORDER BY a.created_at DESC;

SELECT '=== 2. Peak rolling rate ever reached per card (the ceiling you have survived) ===' AS report;

-- Rolling, not calendar buckets: "14 borrows on Tuesday" hides 14 in one evening, which is the shape
-- that trips the limit. Each borrow is scored by how many happened in the window ending at it.
SELECT
    identity,
    MAX(in_minute) AS peak_per_minute,
    MAX(in_hour)   AS peak_per_hour,
    MAX(in_day)    AS peak_per_day,
    MAX(in_week)   AS peak_per_week
FROM (
    SELECT a.identity,
        (SELECT COUNT(*) FROM overdrive_audit b WHERE b.identity = a.identity AND b.success = 1
           AND b.action IN ('BORROW','BORROW_AND_IMPORT')
           AND b.created_at > a.created_at - INTERVAL 1 MINUTE AND b.created_at <= a.created_at) AS in_minute,
        (SELECT COUNT(*) FROM overdrive_audit b WHERE b.identity = a.identity AND b.success = 1
           AND b.action IN ('BORROW','BORROW_AND_IMPORT')
           AND b.created_at > a.created_at - INTERVAL 1 HOUR AND b.created_at <= a.created_at) AS in_hour,
        (SELECT COUNT(*) FROM overdrive_audit b WHERE b.identity = a.identity AND b.success = 1
           AND b.action IN ('BORROW','BORROW_AND_IMPORT')
           AND b.created_at > a.created_at - INTERVAL 1 DAY AND b.created_at <= a.created_at) AS in_day,
        (SELECT COUNT(*) FROM overdrive_audit b WHERE b.identity = a.identity AND b.success = 1
           AND b.action IN ('BORROW','BORROW_AND_IMPORT')
           AND b.created_at > a.created_at - INTERVAL 7 DAY AND b.created_at <= a.created_at) AS in_week
    FROM overdrive_audit a
    WHERE a.success = 1 AND a.action IN ('BORROW','BORROW_AND_IMPORT')
) rolling
GROUP BY identity
ORDER BY peak_per_week DESC;

SELECT '=== 3. Volume and span per card, for context ===' AS report;

SELECT
    identity,
    COUNT(*)                                                          AS borrows,
    MIN(created_at)                                                   AS first_borrow,
    MAX(created_at)                                                   AS last_borrow,
    ROUND(COUNT(*) / GREATEST(TIMESTAMPDIFF(DAY, MIN(created_at), MAX(created_at)), 1), 2)
                                                                      AS borrows_per_day_average
FROM overdrive_audit
WHERE success = 1 AND action IN ('BORROW', 'BORROW_AND_IMPORT')
GROUP BY identity
ORDER BY borrows DESC;

SELECT '=== 4. Gaps between consecutive borrows on a card (how bursty have we been?) ===' AS report;

-- The distribution the new 45s floor is meant to replace. A large "under_1_min" count is the burst
-- that the churning limit reacts to.
SELECT
    identity,
    SUM(gap_seconds <  60)                        AS under_1_min,
    SUM(gap_seconds >=  60 AND gap_seconds < 300) AS one_to_five_min,
    SUM(gap_seconds >= 300 AND gap_seconds < 3600) AS five_to_sixty_min,
    SUM(gap_seconds >= 3600)                      AS over_an_hour,
    MIN(gap_seconds)                              AS shortest_gap_seconds
FROM (
    SELECT identity,
           TIMESTAMPDIFF(SECOND,
               LAG(created_at) OVER (PARTITION BY identity ORDER BY created_at),
               created_at) AS gap_seconds
    FROM overdrive_audit
    WHERE success = 1 AND action IN ('BORROW', 'BORROW_AND_IMPORT')
) gaps
WHERE gap_seconds IS NOT NULL
GROUP BY identity;

SELECT '=== 5. Busiest single days per card, newest first ===' AS report;

SELECT identity, DATE(created_at) AS day, COUNT(*) AS borrows
FROM overdrive_audit
WHERE success = 1 AND action IN ('BORROW', 'BORROW_AND_IMPORT')
GROUP BY identity, DATE(created_at)
HAVING borrows > 1
ORDER BY borrows DESC, day DESC
LIMIT 25;
