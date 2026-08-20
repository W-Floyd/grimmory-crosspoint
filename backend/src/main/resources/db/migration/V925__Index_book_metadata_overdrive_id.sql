-- Index the OverDrive id so "do we already have this title?" is a lookup, not a scan.
--
-- Every auto-sync pass now asks this question once per ready hold and once per unimported loan, and
-- the loan/hold list asks it once per row on every render. Without an index each of those is a full
-- scan of book_metadata.
--
-- Deliberately not unique: importing the same edition twice is something we now avoid, not something
-- the schema should make impossible — a user replacing a damaged file, or two libraries' copies of
-- one edition landing in different Grimmory libraries, are both legitimate.
CREATE INDEX idx_book_metadata_overdrive_id ON book_metadata (overdrive_id);
