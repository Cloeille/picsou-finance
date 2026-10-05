-- V73: Meria authenticates with a single read-only API key (header API-KEY, no secret and no
-- HMAC signing), so api_secret becomes optional.
--
-- Existing BINANCE/KRAKEN rows are unaffected: dropping NOT NULL never rejects data that is
-- already present, and CryptoExchangeSyncService still refuses to save a session without a
-- secret when the adapter reports CryptoExchangePort.requiresApiSecret() == true.
--
-- No CHECK constraint enforcing "secret required unless MERIA": the set of exchanges is open,
-- so a constraint would demand a migration per new exchange and would encode adapter knowledge
-- in the schema. The service is the enforcement point, and it is covered by tests.

ALTER TABLE crypto_exchange_session
    ALTER COLUMN api_secret DROP NOT NULL;
