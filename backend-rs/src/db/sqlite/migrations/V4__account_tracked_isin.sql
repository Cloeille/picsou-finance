-- The ISIN of the single fund an account holds (PostgreSQL V157__account_tracked_isin).
ALTER TABLE account ADD COLUMN tracked_isin TEXT;
