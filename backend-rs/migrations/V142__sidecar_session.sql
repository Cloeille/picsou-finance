-- One session table for the ten sidecar banks that shared the same shape (#337).
-- Each row keeps its bank, member, encrypted session, stored login and sync state byte for
-- byte, so nobody has to reconnect. CORUM keeps the two columns V124 and V127 added to
-- corum_session: the CORUM Life ambiguous count and the opt-in stored login. The one
-- exception is a CORUM or Sofidy row breaking the FAILED/error rule, repaired below
-- before the copy. Copy and drop run in this one
-- migration, so in one transaction.

CREATE TABLE sidecar_session (
    id                     BIGSERIAL PRIMARY KEY,
    bank                   VARCHAR(20)  NOT NULL,
    member_id              BIGINT       NOT NULL,
    session_state          TEXT         NOT NULL,
    encrypted_credentials  TEXT,
    unsupported_contracts  TEXT,
    last_validated_at      TIMESTAMPTZ,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    sync_status            VARCHAR(16)  NOT NULL DEFAULT 'IDLE',
    last_sync_started_at   TIMESTAMPTZ,
    last_sync_completed_at TIMESTAMPTZ,
    last_sync_error        VARCHAR(40),
    last_sync_life_ambiguous INTEGER    NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sidecar_session_bank_member UNIQUE (bank, member_id),
    CONSTRAINT fk_sidecar_session_member
        FOREIGN KEY (member_id) REFERENCES family_member(id) ON DELETE CASCADE,
    CONSTRAINT ck_sidecar_session_bank
        CHECK (bank IN ('AMEX', 'AMUNDI', 'BOURSE_DIRECT', 'BOURSO', 'CAISSE_EPARGNE', 'CIC', 'CMES', 'CORUM', 'FORTUNEO', 'SOFIDY')),
    CONSTRAINT ck_sidecar_session_sync_status
        CHECK (sync_status IN ('IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED')),
    -- Eight of the ten per-bank tables enforced this rule; CORUM and Sofidy code kept it without a CHECK.
    CONSTRAINT ck_sidecar_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        ),
    CONSTRAINT ck_sidecar_session_credentials
        CHECK (encrypted_credentials IS NULL OR bank IN ('BOURSO', 'CIC', 'CMES', 'CORUM')),
    -- V124's ck_corum_session_life_ambiguous; the count is CORUM's alone.
    CONSTRAINT ck_sidecar_session_life_ambiguous
        CHECK (last_sync_life_ambiguous >= 0 AND (bank = 'CORUM' OR last_sync_life_ambiguous = 0)),
    CONSTRAINT ck_sidecar_session_unsupported_contracts
        CHECK (unsupported_contracts IS NULL OR bank = 'CAISSE_EPARGNE'),
    -- The codes each bank may store, generated from SidecarBank; SidecarBankCodesTest keeps them equal.
    CONSTRAINT ck_sidecar_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR (bank = 'AMEX' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'INVALID_OTP', 'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA',
                'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
            OR (bank = 'AMUNDI' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'CAPTCHA_BLOCKED', 'INVALID_OTP',
                'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED',
                'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA',
                'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
            OR (bank = 'BOURSE_DIRECT' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'INVALID_OTP', 'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED',
                'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
            OR (bank = 'BOURSO' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'FRAUD_ACK_REQUIRED', 'IDENTITY_SELECTION_UNSUPPORTED',
                'MFA_TYPE_UNSUPPORTED', 'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED',
                'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
            OR (bank = 'CAISSE_EPARGNE' AND last_sync_error IN (
                'SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'INVALID_CREDENTIALS',
                'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'KEYPAD_CHANGED',
                'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'UPSTREAM_FORMAT_CHANGED',
                'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
            OR (bank = 'CIC' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'ACTION_REQUIRED', 'MFA_TYPE_UNSUPPORTED',
                'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'APP_VALIDATION_CANCELLED',
                'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE',
                'INTERNAL_ERROR'))
            OR (bank = 'CMES' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'ACTION_REQUIRED', 'MFA_TYPE_UNSUPPORTED',
                'INVALID_OTP', 'APP_VALIDATION_TIMEOUT', 'APP_VALIDATION_CANCELLED',
                'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE',
                'INTERNAL_ERROR'))
            OR (bank = 'CORUM' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'SESSION_EXPIRED', 'MULTIPLE_CONTRACTS',
                'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA',
                'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
            OR (bank = 'FORTUNEO' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'INVALID_OTP', 'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED', 'INVESTOR_PROFILE_REQUIRED', 'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE',
                'INTERNAL_ERROR'))
            OR (bank = 'SOFIDY' AND last_sync_error IN (
                'INVALID_CREDENTIALS', 'MFA_INVALID', 'FIRST_VISIT_PENDING',
                'EMAIL_UNREACHABLE', 'ACCOUNT_INACTIVE', 'RATE_LIMITED',
                'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE',
                'INTERNAL_ERROR'))
        )
);

CREATE INDEX ix_sidecar_session_member ON sidecar_session (member_id);

-- corum_session and sofidy_session never had the failed_error CHECK. A hand-edited row that
-- breaks it would fail the copy and stop the boot, so make it obey the rule first: an error
-- outside FAILED is stale and goes, a FAILED row with no reason says INTERNAL_ERROR.
UPDATE corum_session SET last_sync_error = NULL WHERE sync_status <> 'FAILED' AND last_sync_error IS NOT NULL;
UPDATE corum_session SET last_sync_error = 'INTERNAL_ERROR' WHERE sync_status = 'FAILED' AND last_sync_error IS NULL;
UPDATE sofidy_session SET last_sync_error = NULL WHERE sync_status <> 'FAILED' AND last_sync_error IS NOT NULL;
UPDATE sofidy_session SET last_sync_error = 'INTERNAL_ERROR' WHERE sync_status = 'FAILED' AND last_sync_error IS NULL;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'AMEX', member_id, session_state, NULL, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM amex_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'AMUNDI', member_id, session_state, NULL, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM amundi_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'BOURSE_DIRECT', member_id, session_state, NULL, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM bourse_direct_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'BOURSO', member_id, session_state, encrypted_credentials, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM bourso_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'CAISSE_EPARGNE', member_id, session_state, NULL, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM caisse_epargne_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'CIC', member_id, session_state, encrypted_credentials, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM cic_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'CMES', member_id, session_state, encrypted_credentials, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM cmes_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    last_sync_life_ambiguous, created_at, updated_at)
SELECT 'CORUM', member_id, session_state, encrypted_credentials, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    last_sync_life_ambiguous, created_at, updated_at
FROM corum_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'FORTUNEO', member_id, session_state, NULL, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM fortuneo_session;

INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at)
SELECT 'SOFIDY', member_id, session_state, NULL, NULL,
    last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at, last_sync_error,
    created_at, updated_at
FROM sofidy_session;

DROP TABLE amex_session, amundi_session, bourse_direct_session, bourso_session, caisse_epargne_session, cic_session, cmes_session, corum_session, fortuneo_session, sofidy_session;
