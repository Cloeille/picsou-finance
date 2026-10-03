-- American Express sidecar session, following the bourso_session /
-- amundi_session shape: one row per member, the encrypted session blob, and
-- the persisted job state machine an interrupted sync is recovered from.
CREATE TABLE amex_session (
    id                     BIGSERIAL PRIMARY KEY,
    member_id              BIGINT NOT NULL UNIQUE,
    session_state          TEXT NOT NULL,
    last_validated_at      TIMESTAMPTZ,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status            VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_sync_started_at   TIMESTAMPTZ,
    last_sync_completed_at TIMESTAMPTZ,
    last_sync_error        VARCHAR(40),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_amex_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_amex_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with AmexErrorCode: a code missing here blows up the
    -- write that records a failed sync, turning a diagnosable error into a 500.
    CONSTRAINT ck_amex_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'SAFEKEY_TIMEOUT',
                'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED',
                'UPSTREAM_FORMAT_CHANGED',
                'UPSTREAM_UNAVAILABLE',
                'INVALID_DATA',
                'INTERNAL_ERROR'
            )
        ),
    CONSTRAINT ck_amex_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
