-- Tokens are now stored encrypted when a credential key is configured: a prefix plus base64 of
-- IV, ciphertext and tag, about 1.4x the JWT, which itself grows with the cards on the chip.
ALTER TABLE overdrive_token MODIFY COLUMN token TEXT NOT NULL;
