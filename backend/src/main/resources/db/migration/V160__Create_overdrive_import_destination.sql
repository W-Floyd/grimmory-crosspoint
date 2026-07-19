-- Per-user OverDrive borrow-and-import destinations, split by document type (ebook vs audiobook).
-- Each user routes their imports: ebooks (EPUB/PDF) to one library+path, audiobooks to another.
-- Any unset type (or a library that won't keep the format) falls back to Bookdrop.
CREATE TABLE overdrive_import_destination (
    user_id              BIGINT NOT NULL,
    ebook_library_id     BIGINT,
    ebook_path_id        BIGINT,
    audiobook_library_id BIGINT,
    audiobook_path_id    BIGINT,
    PRIMARY KEY (user_id)
);
