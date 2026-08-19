-- Per-user opt-in for automated OverDrive activity. Both flags default to FALSE: the operator's cron
-- schedule decides *whether and how often* Grimmory talks to Libby at all, and each user decides
-- independently whether their own cards may be acted on unattended. A user with no row here is
-- opted out, so the poller only ever touches users who explicitly turned this on.
CREATE TABLE overdrive_auto_sync (
    user_id           BIGINT  NOT NULL,
    -- Import loans found on sync (including ones borrowed in the Libby app) into the user's library.
    auto_import_loans BOOLEAN NOT NULL DEFAULT FALSE,
    -- Borrow holds that have become available, then import them (implies auto_import_loans).
    auto_borrow_holds BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id)
);

-- Consecutive failed automatic import attempts for a loan. A title the poller can never import (an
-- audiobook with no external handler configured, say) would otherwise be retried on every tick
-- forever; past a small threshold the poller leaves it alone and the user imports it by hand.
-- Reset to 0 on any successful import.
ALTER TABLE overdrive_loan
    ADD COLUMN auto_import_failures INT NOT NULL DEFAULT 0;
