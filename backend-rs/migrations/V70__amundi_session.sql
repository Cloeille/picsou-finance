-- Amundi Épargne Salariale sidecar session. Mirrors bourse_direct_session:
-- one row per member, the encrypted sidecar session blob, and the persisted
-- job state machine an interrupted browser sync is recovered from.
CREATE TABLE amundi_session (
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
    CONSTRAINT fk_amundi_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_amundi_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_amundi_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'CAPTCHA_BLOCKED',
                'INVALID_OTP',
                'APP_VALIDATION_TIMEOUT',
                'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED',
                'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED',
                'UPSTREAM_UNAVAILABLE',
                'INVALID_DATA',
                'INTERNAL_ERROR'
            )
        ),
    CONSTRAINT ck_amundi_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
