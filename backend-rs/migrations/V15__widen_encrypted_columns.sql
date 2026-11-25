-- V15: Widen columns to accommodate AES-256-GCM encrypted values (base64-encoded)
-- Existing plaintext sessions are cleared — users must re-enter credentials after this migration.

ALTER TABLE crypto_exchange_session ALTER COLUMN api_key TYPE VARCHAR(500);

ALTER TABLE trade_republic_session ALTER COLUMN session_token TYPE VARCHAR(2000);
ALTER TABLE trade_republic_session ALTER COLUMN refresh_token TYPE VARCHAR(4000);

-- Clear existing plaintext sessions: encrypted code can no longer read them
TRUNCATE TABLE crypto_exchange_session;
TRUNCATE TABLE trade_republic_session;
