-- Per-card default destination library for OverDrive borrow & import.
--
-- Each linked card (per user) can remember a default library + path; the catalog pre-selects it and
-- persists changes back. Nullable: when unset and no destination is chosen at import time, the book
-- drops into the Bookdrop folder instead.

ALTER TABLE overdrive_token ADD COLUMN default_library_id BIGINT NULL;
ALTER TABLE overdrive_token ADD COLUMN default_path_id BIGINT NULL;
