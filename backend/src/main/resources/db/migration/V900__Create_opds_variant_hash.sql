CREATE TABLE opds_variant_hash
(
    id           BIGINT AUTO_INCREMENT NOT NULL,
    book_id      BIGINT                NOT NULL,
    book_file_id BIGINT                NOT NULL,
    preset       VARCHAR(64)           NOT NULL,
    variant_hash VARCHAR(128)          NOT NULL,
    source_hash  VARCHAR(128),
    updated_at   datetime              NOT NULL,
    CONSTRAINT pk_opds_variant_hash PRIMARY KEY (id),
    CONSTRAINT uk_opds_variant_hash_file_preset UNIQUE (book_file_id, preset)
);

CREATE INDEX idx_opds_variant_hash_variant ON opds_variant_hash (variant_hash);

ALTER TABLE opds_variant_hash
    ADD CONSTRAINT fk_opds_variant_hash_book
        FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE;

ALTER TABLE opds_variant_hash
    ADD CONSTRAINT fk_opds_variant_hash_book_file
        FOREIGN KEY (book_file_id) REFERENCES book_file (id) ON DELETE CASCADE;
