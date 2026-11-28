-- The accounts the last BoursoBank sync listed but did not import or refresh (#632): a JSON list
-- of kinds and codes, never an amount.
ALTER TABLE sidecar_session ADD COLUMN skipped_accounts TEXT;
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_skipped_accounts
    CHECK (skipped_accounts IS NULL OR bank = 'BOURSO');
