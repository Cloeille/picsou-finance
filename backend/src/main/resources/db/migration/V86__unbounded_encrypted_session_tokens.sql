-- V86: encrypted third-party session material is stored as TEXT, not as a guessed VARCHAR.
--
-- Trade Republic now issues a session token longer than 1472 bytes. Once it goes through
-- CryptoEncryption (12-byte IV + 16-byte GCM tag, then Base64: 4 * ceil((n + 28) / 3)
-- characters for n plaintext bytes) the ciphertext no longer fits VARCHAR(2000), and
-- completeAuth dies on "value too long for type character varying(2000)" AFTER the 2FA has
-- succeeded. The session is never stored, so the next sync reports "session expired, please
-- reconnect" and the user loops forever (#115).
--
-- The same latent ceiling sits on every other encrypted value whose length a third party
-- controls, not us: the Trade Republic refresh token (VARCHAR(4000)) and the DEGIRO session
-- blob (VARCHAR(4000)). Amundi, Bourse Direct and BoursoBank already keep their session
-- state as TEXT, and persistent_session.token_hash is TEXT too; this brings the three
-- stragglers in line. PostgreSQL rewrites nothing for varchar -> text, NOT NULL is kept.
--
-- Left bounded on purpose, because a known format bounds them with margin:
-- finary_session.email / password (RFC-bounded e-mail, a user passphrase),
-- ibkr_connection.token / query_id (a ~40-char Flex token, a numeric query id),
-- crypto_exchange_session.api_key / api_secret (exchange key formats; the largest known,
-- a Coinbase EC private key, is ~230 chars, ~350 once encrypted, under 500).
--
-- Numbering: V80 to V85 are claimed by open PRs; outOfOrder=true tolerates the gap.

ALTER TABLE trade_republic_session ALTER COLUMN session_token TYPE TEXT;
ALTER TABLE trade_republic_session ALTER COLUMN refresh_token TYPE TEXT;
ALTER TABLE degiro_session ALTER COLUMN session_blob TYPE TEXT;
