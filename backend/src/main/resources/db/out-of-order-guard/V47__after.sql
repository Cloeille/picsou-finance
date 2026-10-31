-- Runs just after V47, in its transaction: OutOfOrderAccountMigrationGuard.
--
-- Gives the accounts V47__before.sql hid their external id back. V47's index on
-- (external_account_id, member_id) would reject them, so it takes V72's definition first; V72
-- then drops and recreates the same index.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM account_external_id_stash) THEN
        DROP INDEX account_external_unique_active;
        CREATE UNIQUE INDEX account_external_unique_active
          ON account(external_account_id, member_id, provider)
          WHERE deleted_at IS NULL AND external_account_id IS NOT NULL AND is_manual = false;
        UPDATE account a SET external_account_id = s.external_account_id
          FROM account_external_id_stash s WHERE a.id = s.id;
    END IF;
END $$;

DROP TABLE account_external_id_stash;
