-- The Caisse d'Epargne identifier, encrypted with CryptoEncryption, so a reconnect does not ask
-- for it again. The password is still never stored: the user clicks it on the bank's keypad.
ALTER TABLE caisse_epargne_session ADD COLUMN encrypted_credentials TEXT;
