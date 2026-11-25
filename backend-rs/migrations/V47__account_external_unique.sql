-- V47: Prevent duplicate active accounts with the same external_account_id + member_id.
-- Without this, concurrent TR sync calls (background + manual) could both find no existing account
-- and both INSERT a new row for the same externalId, producing x2 on the dashboard.

-- Step 1: Remove duplicates, keeping the row with the highest id per (external_account_id, member_id).
DELETE FROM account
WHERE deleted_at IS NULL
  AND external_account_id IS NOT NULL
  AND id NOT IN (
    SELECT DISTINCT ON (external_account_id, member_id) id
    FROM account
    WHERE deleted_at IS NULL AND external_account_id IS NOT NULL
    ORDER BY external_account_id, member_id, id DESC
  );

-- Step 2: Partial unique index — only constrains active (non-deleted) accounts.
-- Soft-deleted tombstones (deleted_at IS NOT NULL) are intentionally excluded so the same
-- externalId can be reused when the user reconnects after an explicit deletion.
CREATE UNIQUE INDEX account_external_unique_active
  ON account(external_account_id, member_id)
  WHERE deleted_at IS NULL AND external_account_id IS NOT NULL;
