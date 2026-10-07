-- V59: Interactive Brokers (IBKR) Flex Web Service connection.
-- (Originally V57 on main, chosen over V56__persistent_session_previous_token there.
-- Shifted to V59 when merged into 1.1.0, which already had V57/V58 published under
-- the `1.1.0` image tag — same-version collisions are a hard Flyway boot failure.)
--
-- Stores the per-member Flex Web Service credentials used to pull Open Positions
-- once a day (EOD data). Both the token and the query id are AES-256-GCM encrypted
-- at rest by CryptoEncryption, exactly like the other connector sessions
-- (finary_session, bourso_session), hence the wide VARCHAR(500) columns
-- (cf. V15__widen_encrypted_columns.sql: ciphertext is Base64 and longer than the
-- plaintext token/query id).

CREATE TABLE ibkr_connection (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    -- Flex Web Service token (encrypted). Valid 6h–1y, read-only, generated in Client Portal.
    token           VARCHAR(500)  NOT NULL,
    -- Flex Query id (encrypted). Identifies the pre-built "Open Positions" query.
    query_id        VARCHAR(500)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'CONNECTED',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- One connection per member (mirrors the single-session model of the other connectors).
CREATE UNIQUE INDEX idx_ibkr_connection_member ON ibkr_connection(member_id);
