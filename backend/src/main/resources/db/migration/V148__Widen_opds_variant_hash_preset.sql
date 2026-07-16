-- Widen the variant key so it can encode the cover dimension (preset id + cover token),
-- letting cover / non-cover (and cover-version) variants of the same file coexist as rows.
ALTER TABLE opds_variant_hash MODIFY COLUMN preset VARCHAR(128) NOT NULL;
