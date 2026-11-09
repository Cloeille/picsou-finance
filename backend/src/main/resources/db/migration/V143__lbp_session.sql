-- La Banque Postale sidecar session: one row per member, the encrypted session state
-- (cookies, encrypted by the backend), and the persisted job state machine an interrupted
-- sync is recovered from.
--
-- Shaped like banque_populaire_session (V134), and like it kept out of sidecar_session (V142),
-- with the same rule: there is NO credentials column. The password, the keypad positions and the mobile-app approval are never stored. The
-- session cannot be silently re-opened, so an expired session means the user reconnects.
CREATE TABLE lbp_session (
    id                     BIGSERIAL PRIMARY KEY,
    member_id              BIGINT NOT NULL UNIQUE,
    session_state          TEXT NOT NULL,
    last_validated_at      TIMESTAMPTZ,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    sync_status            VARCHAR(16) NOT NULL DEFAULT 'IDLE',
    last_sync_started_at   TIMESTAMPTZ,
    last_sync_completed_at TIMESTAMPTZ,
    last_sync_error        VARCHAR(40),
    -- JSON array of {externalId, familyCode} for products the sidecar reported but Picsou
    -- does not import. Counted in the status response, never turned into accounts.
    unsupported_products   TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_lbp_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_lbp_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Kept in lockstep with SidecarBank.LBP's declared codes: a code missing here blows up
    -- the write that records a failed sync, turning a diagnosable error into a 500.
    CONSTRAINT ck_lbp_session_last_sync_error
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
                'KEYPAD_EXPIRED',
                'INVALID_POSITIONS',
                'APP_VALIDATION_TIMEOUT',
                'AUTH_ATTEMPT_EXPIRED'
            )
        ),
    CONSTRAINT ck_lbp_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        )
);
