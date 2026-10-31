-- Runs just after V77, in its transaction: OutOfOrderAccountMigrationGuard.
UPDATE account a SET external_account_id = s.external_account_id
  FROM manual_external_id_stash s WHERE a.id = s.id;

DROP TABLE manual_external_id_stash;
