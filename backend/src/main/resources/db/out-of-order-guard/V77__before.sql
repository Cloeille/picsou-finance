-- Runs just before V77, in its transaction: OutOfOrderAccountMigrationGuard.
--
-- V77 groups synced accounts by member, external id and provider, then merges every account of
-- that key into the group, a manual one included. A 1.0.0-1.0.15 database runs it out of order
-- (#265), with its accounts in place. Manual accounts are hidden from it by clearing their
-- external id; V77__after.sql restores it.
CREATE TEMP TABLE manual_external_id_stash ON COMMIT DROP AS
SELECT id, external_account_id
  FROM account
 WHERE is_manual = true AND external_account_id IS NOT NULL;

UPDATE account a SET external_account_id = NULL
  FROM manual_external_id_stash s WHERE a.id = s.id;
