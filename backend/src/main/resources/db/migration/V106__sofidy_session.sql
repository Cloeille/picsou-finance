-- A Sofidy client-space session, stored encrypted. Neither the password nor the
-- e-mailed verification code is persisted: only the sidecar's opaque session
-- blob, exactly as the Amundi and CORUM sidecars do. One row per member,
-- replaced on reconnect.
CREATE TABLE sofidy_session (
    id                        BIGSERIAL PRIMARY KEY,
    member_id                 BIGINT NOT NULL UNIQUE REFERENCES family_member(id) ON DELETE CASCADE,
    session_state             TEXT NOT NULL,
    last_validated_at         TIMESTAMPTZ,
    is_active                 BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status               VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_sync_started_at      TIMESTAMPTZ,
    last_sync_completed_at    TIMESTAMPTZ,
    last_sync_error           VARCHAR(40),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_sofidy_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_sofidy_session_error
        CHECK (last_sync_error IS NULL OR last_sync_error IN (
            'INVALID_CREDENTIALS', 'MFA_INVALID', 'FIRST_VISIT_PENDING',
            'EMAIL_UNREACHABLE', 'ACCOUNT_INACTIVE', 'RATE_LIMITED',
            'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE',
            'UPSTREAM_FORMAT_CHANGED', 'UPSTREAM_UNAVAILABLE', 'INVALID_DATA',
            'INTERNAL_ERROR'
        ))
);

-- A Sofidy login serves one portfolio of many funds while Picsou models one
-- account per vehicle, so a fund is matched to its account by Sofidy's own
-- Code_Produit (DY for SOFIDYNAMIC). Null means the account was entered by hand
-- and no sync will touch it. The uniqueness is per member: two funds must not be
-- written to one account, which would silently halve one and double the other.
ALTER TABLE scpi_position
    ADD COLUMN sofidy_fund_code VARCHAR(40);

CREATE UNIQUE INDEX uq_scpi_position_sofidy_fund
    ON scpi_position (member_id, sofidy_fund_code)
    WHERE sofidy_fund_code IS NOT NULL;
