-- The CORUM credentials a sync logs in again with once the stored session has
-- expired, encrypted via CryptoEncryption exactly as cmes_session (V117) stores
-- them. Opt-in: NULL unless the member ticked "remember my credentials" at the
-- last login, and cleared when CORUM rejects the password or the member
-- disconnects. Every existing row stays NULL, so nothing changes for them.
ALTER TABLE corum_session
    ADD COLUMN encrypted_credentials TEXT;
