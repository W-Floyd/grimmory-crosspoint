-- Enforce the invariant persistLoan() already assumes: at most one cached loan row per (user, loan).
-- Sharing multiplies sync traffic on a card (each sharee syncs it under their own user id), so this
-- also guards against concurrent syncs for the same user racing two inserts of the same loan.
ALTER TABLE overdrive_loan
    ADD CONSTRAINT uq_overdrive_loan_user_loan UNIQUE (user_id, overdrive_loan_id);
