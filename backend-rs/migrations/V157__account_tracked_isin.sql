-- The ISIN of the single fund an account holds, set by its member: the analysis pages count the
-- account's balance as that fund instead of as cash. Nullable: most accounts track no fund, and
-- the balance stays the source of truth (no account_holding row is written for it).
-- IF NOT EXISTS keeps the migration replayable if its version number has to change before merge.
ALTER TABLE account ADD COLUMN IF NOT EXISTS tracked_isin VARCHAR(12);
