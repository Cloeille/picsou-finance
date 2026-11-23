-- V37: Scoped access-keys for the embedded MCP server.
-- Each key is hard-bound to ONE family member's data and authenticates ONLY /mcp/**
-- (never /api/**). The raw secret is shown once at creation; only its SHA-256 is stored.
-- See docs/features/mcp-server.md for the full design.

CREATE TABLE access_key (
    id              BIGSERIAL PRIMARY KEY,
    -- The member whose data this key may act on. CASCADE: deleting the member wipes its keys.
    member_id       BIGINT NOT NULL REFERENCES family_member(id) ON DELETE CASCADE,
    -- The login that created the key; also the principal resolved on the auth hot path.
    created_by      BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    -- "psk_" + first 8 random chars. Unique → O(1) lookup before the constant-time hash compare.
    key_prefix      VARCHAR(16) NOT NULL,
    -- SHA-256 hex of the full secret (64 chars). Never the raw key.
    key_hash        VARCHAR(64) NOT NULL,
    -- Space-delimited scope set, e.g. 'accounts:read goals:write'. '' = a key with no permissions.
    scopes          TEXT NOT NULL DEFAULT '',
    -- Throttled: bumped at most once per key per few minutes on use.
    last_used_at    TIMESTAMPTZ,
    -- Optional expiry; NULL = never expires.
    expires_at      TIMESTAMPTZ,
    -- Set on revoke; a non-NULL value rejects the key immediately on the next request.
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_access_key_prefix UNIQUE (key_prefix)
);

-- List a member's keys (Settings UI), newest first.
CREATE INDEX idx_access_key_member ON access_key(member_id);
