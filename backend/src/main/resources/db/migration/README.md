# Database migrations in this fork

Grimmory Crosspoint tracks `upstream/develop`, so migrations arrive from two directions and their
version numbers have to be kept out of each other's way.

## The rule

**Fork-local migrations use `V900` and up. Never add a fork migration below `V900`.**

Everything below `V900` belongs to upstream and is byte-identical to it. Upstream migrations keep
their own numbers when merged — do not renumber them. Upstream's sequence (currently at `V146`) will
not plausibly reach `V900`, so the two cannot collide.

Without this split, an upstream migration numbered the same as a fork one would be **silently
skipped** on a fork database — Flyway would see that version as already applied and never run it,
leaving the schema quietly wrong.

## Out-of-order is enabled

An upstream migration arriving as `V147` is *older* than the `V900+` migrations a fork database has
already applied. Flyway rejects that by default, so `spring.flyway.out-of-order` is `true` in
`application.yaml`. Checksums of already-applied migrations are still validated; only the ordering
constraint is relaxed.

## Adding a migration

1. Pick the next free `V9xx`.
2. Prefer `IF EXISTS` / `IF NOT EXISTS` — the database is MariaDB, which supports them on
   `ADD COLUMN` / `DROP COLUMN` / `CREATE INDEX`.
3. Never edit a migration that has shipped. Flyway stores its checksum, and changing the file breaks
   every existing deployment.

## History: the V146-V162 renumbering

The fork originally took `V146`-`V162`, which collided with upstream (`V146` was
`Drop_permission_demo_user` upstream and `Create_opds_variant_hash` here; upstream's was carried as
`V157`). Those migrations were renumbered into the `V900+` range and upstream's `V146` restored,
which was only cheap because the fork had never cut a release — the scripts themselves did not
change, so nothing needed re-applying.

Databases migrated by the fork **before** that renumbering carry the old version numbers and must be
realigned once, with `scripts/db/renumber-fork-migrations.sql`. It rewrites the recorded versions in
`flyway_schema_history` and applies no schema changes. Databases created after the renumbering, and
fresh ones, need nothing.
