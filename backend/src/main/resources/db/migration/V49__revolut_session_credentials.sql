-- V49: Revolut on-demand model -- drop the stored storageState blob, add optional remembered
-- credentials (phone+passcode, encrypted) and a last-synced-at marker.

ALTER TABLE revolut_session
    DROP COLUMN storage_state,
    ADD COLUMN credentials_enc VARCHAR(2000),
    ADD COLUMN remember_credentials BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN last_synced_at TIMESTAMPTZ;
