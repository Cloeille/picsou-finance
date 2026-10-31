-- Revolut pocket-guess data cleanup — run manually against picsou_prod after Chloé's review.
-- Soft-deletes every Revolut pocket sub-account: these were all created by the now-removed
-- PSD2 transfer-pattern heuristic (the sidecar connector has never completed a live sync
-- against this database yet, per docs/features/revolut-sidecar.md).
--
-- Step 1 — PREVIEW (run first, read the output, confirm row count/names look right):
SELECT id, name, provider, type, parent_account_id, current_balance, deleted_at
FROM account
WHERE provider = 'Revolut' AND parent_account_id IS NOT NULL AND deleted_at IS NULL
ORDER BY id;

-- Step 2 — APPLY (only after the preview above has been reviewed and approved):
-- UPDATE account
-- SET deleted_at = now()
-- WHERE provider = 'Revolut' AND parent_account_id IS NOT NULL AND deleted_at IS NULL;
