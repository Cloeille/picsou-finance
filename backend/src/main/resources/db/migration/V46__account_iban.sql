-- Add IBAN column to account for stable matching across provider uid changes.
-- Enable Banking (and other Open Banking providers) may change their account
-- identification hashes (e.g. EB v0.16.4 for ASPSPs that omit per-account currency);
-- IBAN is stable and lets us match the same real-world account regardless of uid rotation.
-- NULL for accounts that have no IBAN (crypto wallets, pocket sub-accounts, etc.).
ALTER TABLE account ADD COLUMN iban VARCHAR(34) NULL;

CREATE INDEX idx_account_iban_member ON account(iban, member_id)
    WHERE iban IS NOT NULL;
