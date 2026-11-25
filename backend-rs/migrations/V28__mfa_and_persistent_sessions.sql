-- V28: TOTP-based 2FA and persistent ("Remember Me") sessions.
-- See docs/features/mfa-and-remember-me.md for the full design.

-- Per-user MFA state. One row per AppUser only when enrollment has been initiated.
CREATE TABLE user_mfa (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    -- AES-GCM ciphertext of the base32 TOTP secret (CryptoEncryption-encoded).
    totp_secret_enc     TEXT NOT NULL,
    -- Anti-replay: most recent TOTP time-step accepted; reject any code whose step <= this value.
    last_used_step      BIGINT,
    enrolled_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-shot recovery codes. Generated only when MFA is enabled.
-- Plaintext is shown to the user once at generation; only the bcrypt hash is stored.
CREATE TABLE user_mfa_recovery_code (
    id                  BIGSERIAL PRIMARY KEY,
    user_mfa_id         BIGINT NOT NULL REFERENCES user_mfa(id) ON DELETE CASCADE,
    code_hash           TEXT NOT NULL,
    used_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lookup unused codes for a given MFA row.
CREATE INDEX idx_recovery_code_active
    ON user_mfa_recovery_code(user_mfa_id)
    WHERE used_at IS NULL;

-- Persistent ("Remember Me") sessions with rotating-token theft detection.
-- Cookie value is "<series_id>:<random_64_bytes_b64url>". Only the SHA-256 of the
-- random part is stored; rotation on each use detects a replayed (stolen) token.
CREATE TABLE persistent_session (
    id                  BIGSERIAL PRIMARY KEY,
    series_id           UUID NOT NULL UNIQUE,
    token_hash          TEXT NOT NULL,
    user_id             BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    user_agent          VARCHAR(255),
    ip_prefix           VARCHAR(45),
    trusted_for_2fa     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ
);

-- Active sessions per user (e.g. for "list my sessions" UI).
CREATE INDEX idx_persistent_session_user_active
    ON persistent_session(user_id)
    WHERE revoked_at IS NULL;

-- series_id has a UNIQUE constraint already; explicit index documents intent.
CREATE INDEX idx_persistent_session_series
    ON persistent_session(series_id);
