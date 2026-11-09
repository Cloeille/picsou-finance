-- Optional remembered sign-in identifier for the La Banque Postale connector.
-- The identifier is not a password, but it is still encrypted at rest with the same key as
-- the session cookies and is never logged. The password and the keypad positions are still
-- never stored: only the identifier is replayed into the login form.
ALTER TABLE lbp_session ADD COLUMN encrypted_identifier TEXT;

COMMENT ON COLUMN lbp_session.encrypted_identifier IS
    'Customer identifier, encrypted with CryptoEncryption. NULL when the user did not ask to remember it.';
