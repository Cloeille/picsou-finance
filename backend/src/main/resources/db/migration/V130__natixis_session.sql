-- Natixis Interépargne sidecar session. Shaped like cmes_session without its
-- encrypted credentials: Natixis asks for a code on every login, so no stored
-- password could log in again unattended. session_state holds the encrypted
-- refresh token (with the device and employee ids), replaced after every read
-- because the sidecar may rotate it.
CREATE TABLE natixis_session (
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
    CONSTRAINT fk_natixis_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_natixis_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with SidecarBank.NATIXIS (SidecarBankCodesTest): a code
    -- missing here blows up the write that records a failed sync.
    CONSTRAINT ck_natixis_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'MFA_TYPE_UNSUPPORTED',
                'INVALID_OTP',
                'RATE_LIMITED',
                'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED',
                'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED',
                'INVALID_DATA',
                'UPSTREAM_UNAVAILABLE',
                'INTERNAL_ERROR'
            )
        ),
    CONSTRAINT ck_natixis_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
