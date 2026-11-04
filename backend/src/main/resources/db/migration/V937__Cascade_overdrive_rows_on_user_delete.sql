-- Deleting a user left their OverDrive rows behind: tokens with encrypted card numbers and PINs, and
-- auto-sync and bookbag rows the poller kept acting on. Clear what earlier deletes orphaned, then let
-- the database remove the rest with the user.

DELETE FROM overdrive_loan WHERE user_id IS NOT NULL AND user_id NOT IN (SELECT id FROM users);
DELETE FROM overdrive_token WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM overdrive_audit WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM overdrive_import_destination WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM overdrive_auto_sync WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM overdrive_bookbag WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM overdrive_tool_log WHERE user_id NOT IN (SELECT id FROM users);

ALTER TABLE overdrive_loan
    ADD CONSTRAINT fk_overdrive_loan_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE overdrive_token
    ADD CONSTRAINT fk_overdrive_token_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE overdrive_audit
    ADD CONSTRAINT fk_overdrive_audit_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE overdrive_import_destination
    ADD CONSTRAINT fk_overdrive_import_destination_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE overdrive_auto_sync
    ADD CONSTRAINT fk_overdrive_auto_sync_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE overdrive_bookbag
    ADD CONSTRAINT fk_overdrive_bookbag_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE overdrive_tool_log
    ADD CONSTRAINT fk_overdrive_tool_log_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
