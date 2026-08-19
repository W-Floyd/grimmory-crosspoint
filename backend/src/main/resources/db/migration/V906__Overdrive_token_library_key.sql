-- Record each linked card's OverDrive library "advantage key" (e.g. "lapl") so catalog search can
-- cover the libraries a user actually has cards for, in addition to the admin-configured library.

ALTER TABLE overdrive_token ADD COLUMN library_key VARCHAR(255);
