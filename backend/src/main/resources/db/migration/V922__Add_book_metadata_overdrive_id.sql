-- Record which OverDrive edition a book was imported from.
--
-- A work can have several OverDrive editions — different covers, descriptions and ids — that share a
-- title, author and publication year. A naming pattern built from those three resolves every one of
-- them to the same filename, so the second import fails on a name already taken.
--
-- Keeping the id in metadata (rather than stamping it into the filename at import time) means the
-- naming pattern can carry it via {overdriveId}, so the import and any later Move & Organize resolve
-- the same path from the same data instead of two mechanisms having to agree.
--
-- Null for every book not imported from OverDrive; the token then contributes nothing.
ALTER TABLE book_metadata
    ADD COLUMN overdrive_id VARCHAR(64) NULL;
