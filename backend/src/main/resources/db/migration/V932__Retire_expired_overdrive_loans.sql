-- Retire loan rows that cannot possibly still be live.
--
-- A hundred rows here belong to a user who holds no cards at all. Reconciliation walks the loans of
-- whoever is syncing, so rows owned by a user who never syncs are unreachable by it — and unlinking a
-- card removed the card while leaving that user's loans behind. They have sat as BORROWED since July,
-- months past expiry.
--
-- Inert today, because every path that acts on a loan is scoped to the user syncing. The reason to
-- clear them is what happens if that user ever gets a card: the import sweep would find a hundred
-- unfulfilled loans at once and try to fetch them all, which is the burst this deployment has already
-- been rate-limited for once.
--
-- An OverDrive loan has a hard expiry, so a row past its own expire_date is not a judgement call.
-- EXPIRED rather than RETURNED for the same reason the reconciler makes that distinction: nobody gave
-- these back, they ran out.
--
-- last_sync is deliberately left alone. It records when a sync last saw the loan, and no sync has.
UPDATE overdrive_loan
   SET state = 'EXPIRED'
 WHERE state NOT IN ('RETURNED', 'EXPIRED')
   AND expire_date IS NOT NULL
   AND expire_date < NOW();
