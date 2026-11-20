-- Nullable: only an exchange whose adapter requires a passphrase
-- (CryptoExchangePort.requiresApiPassphrase()) stores one, AES-GCM encrypted like api_secret.
-- IF NOT EXISTS keeps the migration replayable if its version number has to change before merge.
ALTER TABLE crypto_exchange_session ADD COLUMN IF NOT EXISTS api_passphrase VARCHAR(500);
