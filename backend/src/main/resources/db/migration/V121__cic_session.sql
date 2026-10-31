-- CIC online-banking sidecar session. Shaped like cmes_session: one row per
-- member, the encrypted session cookies, the encrypted credentials a sync logs
-- in again with once the site's short session has expired, and the persisted
-- job state machine an interrupted sync is recovered from.
CREATE TABLE cic_session (
    id                     BIGSERIAL PRIMARY KEY,
    member_id              BIGINT NOT NULL UNIQUE,
    session_state          TEXT NOT NULL,
    encrypted_credentials  TEXT,
    last_validated_at      TIMESTAMPTZ,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status            VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_sync_started_at   TIMESTAMPTZ,
    last_sync_completed_at TIMESTAMPTZ,
    last_sync_error        VARCHAR(40),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_cic_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_cic_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with CicErrorCode: a code missing here blows up the
    -- write that records a failed sync, turning a diagnosable error into a 500.
    CONSTRAINT ck_cic_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'ACTION_REQUIRED',
                'MFA_TYPE_UNSUPPORTED',
                'INVALID_OTP',
                'APP_VALIDATION_TIMEOUT',
                'APP_VALIDATION_CANCELLED',
                'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED',
                'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED',
                'UPSTREAM_UNAVAILABLE',
                'INVALID_DATA',
                'INTERNAL_ERROR'
            )
        ),
    CONSTRAINT ck_cic_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
