-- The accounts the last BoursoBank sync listed but did not import or refresh (PostgreSQL
-- V159__bourso_skipped_accounts).
ALTER TABLE sidecar_session ADD COLUMN skipped_accounts TEXT
    CONSTRAINT ck_sidecar_session_skipped_accounts CHECK (skipped_accounts IS NULL OR bank = 'BOURSO');
