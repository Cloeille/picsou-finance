-- Upgrade the V47 unique index to include provider and exclude manual accounts.
DROP INDEX account_external_unique_active;

CREATE UNIQUE INDEX account_external_unique_active
  ON account(external_account_id, member_id, provider)
  WHERE deleted_at IS NULL AND external_account_id IS NOT NULL AND is_manual = false;
