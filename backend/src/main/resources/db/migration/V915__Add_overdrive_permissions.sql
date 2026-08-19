ALTER TABLE user_permissions
    ADD COLUMN IF NOT EXISTS permission_access_overdrive             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS permission_manage_all_overdrive_shares  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS permission_manage_all_overdrive_cards   BOOLEAN NOT NULL DEFAULT FALSE;

-- Grant all three to admins: OverDrive access was open to them, and cross-user share management was
-- previously implicit in the admin flag. Full card administration is new, but admins are the users
-- who would have had it, so granting it keeps the admin role a superset of what it was.
UPDATE user_permissions up
SET up.permission_access_overdrive = TRUE,
    up.permission_manage_all_overdrive_shares = TRUE,
    up.permission_manage_all_overdrive_cards = TRUE
WHERE up.permission_admin = TRUE;

-- Preserve access for users who could already reach the OverDrive catalog, which was previously
-- gated on the upload permission. Card management stays admin-only.
UPDATE user_permissions up
SET up.permission_access_overdrive = TRUE
WHERE up.permission_upload = TRUE;
