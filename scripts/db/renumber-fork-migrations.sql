-- One-time remediation for databases created before fork migrations moved to the V900+ range.
--
-- The fork originally numbered its migrations V146-V162, colliding with upstream's sequence. They
-- have since been renumbered to V900+, and upstream's own V146 was restored. The migration *scripts*
-- are unchanged — only their version numbers moved — so this rewrites the recorded versions in
-- Flyway's history table to match. Nothing is re-applied and no schema changes.
--
-- Run this ONCE, against a stopped Grimmory instance, on any database that was migrated by this fork
-- before the renumbering. A database created after it needs nothing. Fresh databases need nothing.
--
--   mariadb -u <user> -p <database> < renumber-fork-migrations.sql
--
-- To check whether it applies to you:
--   SELECT version, description FROM flyway_schema_history WHERE version BETWEEN 146 AND 162;
-- If that returns OverDrive/OPDS rows, run this. If it returns nothing, you are already migrated.

START TRANSACTION;

-- Upstream's Drop_permission_demo_user shipped in this fork as V157; it is V146 again.
UPDATE flyway_schema_history SET version = '146'
 WHERE version = '157' AND description = 'Drop permission demo user';

-- Fork migrations move to the reserved V900+ range, in their original order.
UPDATE flyway_schema_history SET version = '900' WHERE version = '146' AND description = 'Create opds variant hash';
UPDATE flyway_schema_history SET version = '901' WHERE version = '147' AND description = 'Add default preset to opds user v2';
UPDATE flyway_schema_history SET version = '902' WHERE version = '148' AND description = 'Widen opds variant hash preset';
UPDATE flyway_schema_history SET version = '903' WHERE version = '149' AND description = 'Create overdrive loan table';
UPDATE flyway_schema_history SET version = '904' WHERE version = '150' AND description = 'Create overdrive token table';
UPDATE flyway_schema_history SET version = '905' WHERE version = '151' AND description = 'Overdrive token multi card';
UPDATE flyway_schema_history SET version = '906' WHERE version = '152' AND description = 'Overdrive token library key';
UPDATE flyway_schema_history SET version = '907' WHERE version = '153' AND description = 'Overdrive token card credentials';
UPDATE flyway_schema_history SET version = '908' WHERE version = '154' AND description = 'Overdrive loan book fk on delete set null';
UPDATE flyway_schema_history SET version = '909' WHERE version = '155' AND description = 'Overdrive token default library';
UPDATE flyway_schema_history SET version = '910' WHERE version = '156' AND description = 'Create overdrive card share';
UPDATE flyway_schema_history SET version = '911' WHERE version = '158' AND description = 'Overdrive loan unique user loan';
UPDATE flyway_schema_history SET version = '912' WHERE version = '159' AND description = 'Create overdrive audit';
UPDATE flyway_schema_history SET version = '913' WHERE version = '160' AND description = 'Create overdrive import destination';
UPDATE flyway_schema_history SET version = '914' WHERE version = '161' AND description = 'Overdrive import destination magazine';
UPDATE flyway_schema_history SET version = '915' WHERE version = '162' AND description = 'Add overdrive permissions';

COMMIT;

-- Expected afterwards: nothing between 147 and 162, one row at 146 (Drop permission demo user),
-- and the fork's migrations at 900-915.
--   SELECT version, description FROM flyway_schema_history WHERE version >= 146 ORDER BY version + 0;
