-- Whether this entry was queued knowing the library already has the title.
--
-- The bag drops an entry whose title turns up in the library, which is right for the ordinary case:
-- a hold came in, it was imported, the entry's work is done. But it made a deliberate re-borrow
-- impossible — queueing a book you already own looked like it worked and then silently vanished on
-- the next pass, because the bag could not tell "this became redundant" from "I meant it".
--
-- Set when a title is queued that already matches a library book, so intent is recorded at the moment
-- it is expressed rather than guessed at later. Wanting a second copy is a real thing: a damaged
-- file, a different edition, a format the first import could not produce.
--
-- Guarded like the rest: MariaDB does not roll DDL back, so a migration that fails after this leaves
-- the column behind and the retry has to be harmless.
ALTER TABLE overdrive_bookbag
    ADD COLUMN IF NOT EXISTS allow_reborrow BOOLEAN NOT NULL DEFAULT FALSE;
