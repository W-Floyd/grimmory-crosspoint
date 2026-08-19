-- Distinguish activity performed by the scheduled poller from activity the user clicked.
--
-- Automated borrows, imports and returns deliberately reuse the interactive code paths, so they
-- record the same actions (BORROW_AND_IMPORT, IMPORT, RETURN) as a manual one and were previously
-- indistinguishable in the history. For unattended actions that is exactly backwards: waking up to a
-- returned book is when you most want to know whether you were the one who returned it.
--
-- Existing rows are correctly FALSE: everything recorded before the poller existed was manual.
ALTER TABLE overdrive_audit
    ADD COLUMN automated BOOLEAN NOT NULL DEFAULT FALSE;
