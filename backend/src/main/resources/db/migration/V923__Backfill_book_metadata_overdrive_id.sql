-- Backfill overdrive_id for books imported before the column existed.
--
-- The source is overdrive_loan.book_id, written at the end of every successful import: "this import
-- produced that book". Loan rows are never deleted, so this covers every book Grimmory imported and
-- still holds.
--
-- Applied only where a book maps to exactly one OverDrive id. A book can legitimately be pointed at by
-- several loan rows — a shared card caches the same loan once per user — and those all carry the same
-- id, so they collapse harmlessly. Where the ids genuinely differ (formats from different editions
-- merged into one book), the book is left null rather than guessed at: a wrong id here would silently
-- misfile the book on the next Move & Organize.
--
-- Deleted books are skipped, as they should be. The book FK is ON DELETE SET NULL, so deleting a book
-- clears its loan's book_id; there is no metadata row left to fill and nothing to infer.
--
-- overdrive_audit also records book_id + title_id and is deliberately not used. It has no foreign key,
-- so it retains ids of books that no longer exist, and since loan rows survive deletion it would add
-- almost no coverage — while a reused book id would misattribute an edition to the wrong book.
--
-- In Libby a loan id *is* the title id, and title ids are global and stable per edition, so the value
-- taken here is the same identifier the import records today.

UPDATE book_metadata bm
JOIN (
    SELECT book_id, MIN(overdrive_loan_id) AS overdrive_id
    FROM overdrive_loan
    WHERE book_id IS NOT NULL
      AND overdrive_loan_id IS NOT NULL
    GROUP BY book_id
    HAVING COUNT(DISTINCT overdrive_loan_id) = 1
) src ON src.book_id = bm.book_id
SET bm.overdrive_id = src.overdrive_id
WHERE bm.overdrive_id IS NULL;
