-- Card+PIN linking produces a fulfillment-capable (primary) chip. Persist the library's websiteId
-- and ILS name, plus the AES-GCM encrypted card number / PIN, so an expired chip token can be
-- silently re-linked. Credentials are only stored when app.overdrive.credential-key is configured.
ALTER TABLE overdrive_token ADD COLUMN website_id VARCHAR(32);
ALTER TABLE overdrive_token ADD COLUMN ils_name VARCHAR(128);
ALTER TABLE overdrive_token ADD COLUMN cred_card VARCHAR(512);
ALTER TABLE overdrive_token ADD COLUMN cred_pin VARCHAR(512);
