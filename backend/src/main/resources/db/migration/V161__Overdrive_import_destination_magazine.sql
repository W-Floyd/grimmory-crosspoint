-- Add a per-user magazine import destination alongside ebook and audiobook. Magazines (PDF/EPUB)
-- route to their own library+path; an unset destination falls back to Bookdrop.
ALTER TABLE overdrive_import_destination
    ADD COLUMN magazine_library_id BIGINT,
    ADD COLUMN magazine_path_id    BIGINT;
