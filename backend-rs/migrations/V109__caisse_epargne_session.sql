-- Caisse d'Epargne sidecar session: one row per member, the encrypted session state
-- (cookies + authorize params, encrypted by the backend), and the persisted job state
-- machine an interrupted sync is recovered from.
--
-- Shaped like bourso_session (V78 + V103 + V108) with ONE deliberate difference:
-- there is NO encrypted_credentials column. Decision D5 (2026-10-06): only session
-- cookies are retained, never the password nor the Secur'Pass. The session cannot be
-- silently re-opened, so an expired session means the user reconnects.
CREATE TABLE caisse_epargne_session (
    id                     BIGSERIAL PRIMARY KEY,
    member_id              BIGINT NOT NULL UNIQUE,
    session_state          TEXT NOT NULL,
    last_validated_at      TIMESTAMPTZ,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status            VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_sync_started_at   TIMESTAMPTZ,
    last_sync_completed_at TIMESTAMPTZ,
    last_sync_error        VARCHAR(40),
    -- JSON array of {externalId, familyCode} for contracts the sidecar reported but
    -- Picsou does not import. Counted in the status response, never turned into accounts.
    unsupported_contracts  TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_caisse_epargne_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_caisse_epargne_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with CaisseEpargneErrorCode: a code missing here blows up the
    -- write that records a failed sync, turning a diagnosable error into a 500.
    CONSTRAINT ck_caisse_epargne_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'SESSION_EXPIRED',
                'UPSTREAM_UNAVAILABLE',
                'UPSTREAM_FORMAT_CHANGED',
                'INVALID_SESSION_STATE',
                'INTERNAL_ERROR',
                'INVALID_CREDENTIALS',
                'KEYPAD_CHANGED',
                'APP_VALIDATION_TIMEOUT',
                'AUTH_ATTEMPT_EXPIRED'
            )
        ),
    CONSTRAINT ck_caisse_epargne_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
