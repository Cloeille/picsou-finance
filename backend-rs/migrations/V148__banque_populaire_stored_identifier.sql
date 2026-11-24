-- Banque Populaire remembers the identifier that logged in (encrypted with CryptoEncryption), so a
-- reconnect does not ask for it again. The password is still never stored: the user clicks it on
-- the bank's keypad. sidecar_session already has the column; the CHECK only listed the banks that
-- keep a stored login.
ALTER TABLE sidecar_session DROP CONSTRAINT ck_sidecar_session_credentials;
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_credentials
    CHECK (encrypted_credentials IS NULL
        OR bank IN ('BANQUE_POPULAIRE', 'BOURSO', 'CAISSE_EPARGNE', 'CIC', 'CMES', 'CORUM'));
