-- BoursoBank sidecar session, brought up to the bourse_direct_session /
-- amundi_session shape: one row per member, the encrypted session blob, and the
-- persisted job state machine an interrupted sync is recovered from.
--
-- V23 created bourso_session as a bare (session_cookies, expires_at) pair for a
-- connector that never shipped enabled: the container was commented out of
-- docker-compose, the sync tab was hidden and the admin toggle was hidden, all
-- the way through 1.0.0. Any row here is therefore a cookie blob that no code
-- path could ever have written, for a sidecar whose scraping never matched
-- BoursoBank's actual markup -- and its column does not exist in the new shape.
-- Dropping it is safe; the user reconnects once and gets a working session.
DROP TABLE IF EXISTS bourso_session;

CREATE TABLE bourso_session (
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
    CONSTRAINT fk_bourso_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_bourso_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with BoursoErrorCode: a code missing here blows up the
    -- write that records a failed sync, turning a diagnosable error into a 500.
    -- There is no INVALID_OTP -- the app push is the only supported factor.
    CONSTRAINT ck_bourso_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'MFA_TYPE_UNSUPPORTED',
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
    CONSTRAINT ck_bourso_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
