-- Why OverDrive operations have been failing lately.
--
-- Read-only, and safe to run at any time. Start at the top: query 2 groups the failures so you can
-- see whether you are looking at one recurring cause or several unrelated ones, which decides whether
-- the rest of the report matters at all.
--
--   docker exec -i grimmory-mariadb-1 mariadb -uroot grimmory < overdrive-failure-diagnosis.sql
--
-- On identity joins, note the COLLATE. overdrive_audit is utf8mb4_general_ci and overdrive_token is
-- utf8mb4_unicode_ci, so comparing their identity columns without one fails outright with "Illegal mix
-- of collations" — the same mismatch that once crash-looped a migration.
--
-- What each query answers:
--   1  the raw list, most recent first
--   2  is it one cause or several, and is it still happening
--   3  is a card resting or at a ceiling — an expected refusal rather than a bug
--   4  copies checked out and never fetched: the ones actually costing you something
--   5  loans that keep failing to import, with the retry counter that should have stopped them
--   6  what the bookbag last decided, and why
--   7  reborrow entries: they skip the "we already have this" check by design
--   8  disagreements between a loan, its book, and the library

SELECT '=== 1. Failures, last 3 days ===' AS report;

SELECT DATE_FORMAT(a.created_at, '%m-%d %H:%i') AS at,
       a.action,
       IF(a.automated, 'auto', 'you')           AS by_who,
       a.card_name,
       LEFT(COALESCE(a.title, a.title_id, '-'), 34) AS title,
       LEFT(a.detail, 150)                      AS detail
FROM overdrive_audit a
WHERE a.success = 0
  AND a.created_at > NOW() - INTERVAL 3 DAY
ORDER BY a.created_at DESC
LIMIT 60;

SELECT '=== 2. Failures grouped by cause, last 14 days ===' AS report;

-- Truncated to 70 characters so the same error on different titles groups together: most of these
-- messages end in a title or an id, which would otherwise make every row unique.
SELECT a.action,
       LEFT(a.detail, 70)                              AS error,
       COUNT(*)                                        AS n,
       COUNT(DISTINCT a.title_id)                      AS titles,
       COUNT(DISTINCT a.identity)                      AS cards,
       DATE_FORMAT(MIN(a.created_at), '%m-%d %H:%i')   AS first_seen,
       DATE_FORMAT(MAX(a.created_at), '%m-%d %H:%i')   AS last_seen
FROM overdrive_audit a
WHERE a.success = 0
  AND a.created_at > NOW() - INTERVAL 14 DAY
GROUP BY a.action, LEFT(a.detail, 70)
ORDER BY n DESC
LIMIT 25;

SELECT '=== 3. Card state: resting, and how close to a ceiling ===' AS report;

-- A card at its ceiling or inside a cooldown is supposed to refuse. Check this before treating
-- anything above as a bug: borrows_30d at or near a configured limit explains a stalled bookbag on
-- its own. Limits live in overdrive_card_limit (absent row = the deployment default).
SELECT t.identity,
       MAX(t.card_name)                AS card,
       MAX(t.churn_cooldown_until)     AS resting_until,
       MAX(t.churn_cooldown_until) > NOW() AS still_resting,
       (SELECT COUNT(*) FROM overdrive_audit a
         WHERE a.identity = t.identity COLLATE utf8mb4_general_ci
           AND a.action IN ('BORROW', 'BORROW_AND_IMPORT')
           AND a.success = 1
           AND a.created_at > NOW() - INTERVAL 30 DAY)  AS borrows_30d,
       (SELECT COUNT(*) FROM overdrive_audit a
         WHERE a.identity = t.identity COLLATE utf8mb4_general_ci
           AND a.action IN ('RETURN', 'AUTO_RETURN')
           AND a.success = 1
           AND a.created_at > NOW() - INTERVAL 30 DAY)  AS returns_30d,
       (SELECT COUNT(*) FROM overdrive_audit a
         WHERE a.identity = t.identity COLLATE utf8mb4_general_ci
           AND a.success = 0
           AND a.created_at > NOW() - INTERVAL 3 DAY)   AS fails_3d
FROM overdrive_token t
GROUP BY t.identity
ORDER BY fails_3d DESC, borrows_30d DESC;

SELECT '=== 4. Borrowed but never fetched (a copy is out and unread) ===' AS report;

-- The failures that cost something: the checkout is spent and the file never arrived. Empty is the
-- healthy answer. A row here that is days old is a copy sitting idle until it expires.
SELECT l.user_id,
       l.identity,
       l.overdrive_loan_id,
       LEFT(l.title, 40)                            AS title,
       l.state,
       l.fulfilled,
       l.auto_import_failures,
       DATE_FORMAT(l.created_at, '%m-%d %H:%i')     AS borrowed,
       DATE_FORMAT(l.expire_date, '%m-%d')          AS expires
FROM overdrive_loan l
WHERE l.fulfilled = 0
  AND l.state NOT IN ('RETURNED', 'EXPIRED')
  AND l.created_at > NOW() - INTERVAL 21 DAY
ORDER BY l.created_at DESC
LIMIT 40;

SELECT '=== 5. Loans the importer keeps giving up on ===' AS report;

-- auto_import_failures is capped (MAX_AUTO_IMPORT_FAILURES), and a loan at the cap has stopped being
-- retried. A loan failing repeatedly while the counter stays low means something is resetting it, or
-- the retries are coming from a path that does not consult it.
SELECT l.user_id,
       l.identity,
       l.overdrive_loan_id,
       LEFT(l.title, 40)                        AS title,
       l.state,
       l.fulfilled,
       l.auto_import_failures,
       l.book_id,
       (SELECT COUNT(*) FROM overdrive_audit a
         WHERE a.title_id = l.overdrive_loan_id COLLATE utf8mb4_general_ci
           AND a.success = 0
           AND a.created_at > NOW() - INTERVAL 14 DAY) AS failed_attempts_14d
FROM overdrive_loan l
WHERE l.auto_import_failures > 0
   OR EXISTS (SELECT 1 FROM overdrive_audit a
               WHERE a.title_id = l.overdrive_loan_id COLLATE utf8mb4_general_ci
                 AND a.success = 0
                 AND a.created_at > NOW() - INTERVAL 14 DAY)
ORDER BY failed_attempts_14d DESC, l.auto_import_failures DESC
LIMIT 30;

SELECT '=== 6. What the bookbag last decided ===' AS report;

-- last_note is written every pass, so this is the bag explaining itself. "A copy is on the shelf at X,
-- but ..." is the system working; anything mentioning a failure is not.
SELECT b.id,
       LEFT(b.title, 34)                            AS title,
       b.position,
       b.hold_card_id,
       b.allow_reborrow,
       DATE_FORMAT(b.last_tried_at, '%m-%d %H:%i')  AS last_tried,
       LEFT(b.last_note, 110)                       AS note
FROM overdrive_bookbag b
WHERE b.last_note IS NOT NULL
ORDER BY b.last_tried_at DESC, b.position
LIMIT 40;

SELECT '=== 7. Reborrow entries for titles already in the library ===' AS report;

-- allow_reborrow skips the "we already have this" check on purpose, so these are the entries that
-- will borrow and import over an existing copy. That is intended — but it is also where an import
-- that fails to replace the old file ends up looping, so check these first when a title keeps
-- colliding on its own filename.
SELECT b.id,
       LEFT(b.title, 40) AS title,
       b.title_id,
       DATE_FORMAT(b.last_tried_at, '%m-%d %H:%i') AS last_tried,
       LEFT(b.last_note, 80) AS note
FROM overdrive_bookbag b
WHERE b.allow_reborrow = 1
ORDER BY b.last_tried_at DESC
LIMIT 30;

SELECT '=== 8. Loan / book / library disagreements ===' AS report;

-- Three ways the record can contradict itself. Each has a different cause, so the flag matters:
--   fulfilled with no book   an import that reported success without producing one
--   linked to a missing book the book was deleted; its file may still hold the import path
--   no id on the book        the link can never be made again, so it will re-import forever
SELECT l.overdrive_loan_id,
       LEFT(l.title, 34) AS title,
       l.user_id,
       l.fulfilled,
       l.book_id,
       CASE
         WHEN l.fulfilled = 1 AND l.book_id IS NULL              THEN 'fulfilled, no book recorded'
         WHEN l.book_id IS NOT NULL AND bk.id IS NULL            THEN 'book row is gone'
         WHEN l.book_id IS NOT NULL AND bk.deleted = 1           THEN 'book deleted; file may remain'
         WHEN l.book_id IS NOT NULL AND m.overdrive_id IS NULL   THEN 'book has no overdrive id'
       END AS problem
FROM overdrive_loan l
LEFT JOIN book bk ON bk.id = l.book_id
LEFT JOIN book_metadata m ON m.book_id = bk.id
WHERE (l.fulfilled = 1 AND l.book_id IS NULL)
   OR (l.book_id IS NOT NULL AND bk.id IS NULL)
   OR (l.book_id IS NOT NULL AND bk.deleted = 1)
   OR (l.book_id IS NOT NULL AND m.overdrive_id IS NULL)
ORDER BY l.created_at DESC
LIMIT 40;
