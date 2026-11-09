-- Banque Populaire remembers the identifier that logged in (encrypted with CryptoEncryption),
-- so a reconnect does not ask for it again. The password is still never stored: the user clicks
-- it on the bank's keypad.
ALTER TABLE banque_populaire_session
    ADD COLUMN encrypted_credentials TEXT;
