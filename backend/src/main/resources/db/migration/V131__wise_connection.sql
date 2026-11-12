-- V131: Wise personal API token, one row per family member.
--
-- The token reads the member's Wise jars. It is stored as AES-256-GCM ciphertext,
-- hence TEXT rather than a short varchar.

CREATE TABLE wise_connection (
    id              BIGSERIAL PRIMARY KEY,
    member_id       BIGINT        NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    api_token       TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'CONNECTED',
    last_synced_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_wise_connection_member ON wise_connection(member_id);
