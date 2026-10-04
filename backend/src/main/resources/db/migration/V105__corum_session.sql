-- A CORUM client-space session, stored encrypted. The password is never
-- persisted: only the sidecar's opaque session blob, exactly as the Amundi
-- sidecar does. One row per member, replaced on reconnect.
CREATE TABLE corum_session (
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
    CONSTRAINT ck_corum_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_corum_session_error
        CHECK (last_sync_error IS NULL OR last_sync_error IN (
            'INVALID_CREDENTIALS', 'SESSION_EXPIRED', 'MULTIPLE_CONTRACTS',
            'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED',
            'UPSTREAM_UNAVAILABLE', 'INVALID_DATA', 'INTERNAL_ERROR'
        ))
);

-- A CORUM contract holds several funds while Picsou models one account per
-- vehicle, so a fund is matched to its account by this code. Null means the
-- account was entered by hand and no sync will touch it. The uniqueness is per
-- member: two funds of the same contract must not be written to one account,
-- which would silently halve one and double the other.
ALTER TABLE scpi_position
    ADD COLUMN corum_fund_code VARCHAR(40);

CREATE UNIQUE INDEX uq_scpi_position_corum_fund
    ON scpi_position (member_id, corum_fund_code)
    WHERE corum_fund_code IS NOT NULL;
