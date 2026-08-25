-- Output from the external download handlers, kept so an unattended run can be diagnosed after it has
-- finished. It used to exist only as a websocket stream: fine when a person clicked Download and was
-- watching, useless for a cron pass at 4am, and actively annoying in between — every line the
-- automation produced yanked the console open over whatever the user was doing.
--
-- Guarded because MariaDB does not roll DDL back. A migration that fails after a statement leaves the
-- change behind but unrecorded, and FlywayConfig repairs and retries on the next boot — which re-runs
-- it and crash-loops the container. Idempotent DDL makes the retry the harmless thing it should be.
CREATE TABLE IF NOT EXISTS overdrive_tool_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    -- The title, so the History row for a download can find the log that belongs to it.
    title_id    VARCHAR(255) NULL,
    identity    VARCHAR(255) NULL,
    -- Whether the automation produced this, which is exactly the run nobody was watching.
    automated   BOOLEAN      NOT NULL DEFAULT FALSE,
    succeeded   BOOLEAN      NOT NULL DEFAULT FALSE,
    started_at  TIMESTAMP    NULL,
    finished_at TIMESTAMP    NULL,
    -- One blob per run rather than a row per line: handler output is written once, read rarely, and
    -- only ever read whole. MEDIUMTEXT caps a pathological run at 16MB; the service truncates far
    -- below that.
    output      MEDIUMTEXT   NULL,
    PRIMARY KEY (id)
);

-- The only query this table serves: the most recent run for a title, for one user.
CREATE INDEX IF NOT EXISTS idx_overdrive_tool_log_user_title
    ON overdrive_tool_log (user_id, title_id, started_at);
