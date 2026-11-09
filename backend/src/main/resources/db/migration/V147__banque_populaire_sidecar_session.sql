-- Banque Populaire joins sidecar_session (V142). V142 could not take it: on a fresh database
-- V134 creates banque_populaire_session after V142 has run. Every row is copied as stored,
-- region and encrypted state included, and the old table is dropped in the same transaction,
-- so no member has to reconnect.

ALTER TABLE sidecar_session ADD COLUMN region VARCHAR(32);

ALTER TABLE sidecar_session DROP CONSTRAINT ck_sidecar_session_bank;
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_bank
    CHECK (bank IN ('AMEX', 'AMUNDI', 'BANQUE_POPULAIRE', 'BOURSE_DIRECT', 'BOURSO', 'CAISSE_EPARGNE',
                    'CIC', 'CMES', 'CORUM', 'FORTUNEO', 'NATIXIS', 'SOFIDY'));

-- Set, and one of BanquePopulaireRegion, on a Banque Populaire row; null on every other bank.
-- IS NOT NULL is needed: NULL IN (...) is NULL, which a CHECK lets through.
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_region
    CHECK (
        (bank = 'BANQUE_POPULAIRE' AND region IS NOT NULL AND region IN (
            'ALSACE_LORRAINE_CHAMPAGNE',
            'AQUITAINE_CENTRE_ATLANTIQUE',
            'AUVERGNE_RHONE_ALPES',
            'BOURGOGNE_FRANCHE_COMTE',
            'BRED',
            'GRAND_OUEST',
            'MEDITERRANEE',
            'NORD',
            'OCCITANE',
            'RIVES_DE_PARIS',
            'SUD',
            'VAL_DE_FRANCE'))
        OR (bank <> 'BANQUE_POPULAIRE' AND region IS NULL)
    );

ALTER TABLE sidecar_session DROP CONSTRAINT ck_sidecar_session_unsupported_contracts;
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_unsupported_contracts
    CHECK (unsupported_contracts IS NULL OR bank IN ('BANQUE_POPULAIRE', 'CAISSE_EPARGNE'));

-- The codes each bank may store, generated from SidecarBank; SidecarBankCodesTest keeps them equal.
ALTER TABLE sidecar_session DROP CONSTRAINT ck_sidecar_session_last_sync_error;
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_last_sync_error
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
        OR (bank = 'BANQUE_POPULAIRE' AND last_sync_error IN (
            'SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'INVALID_CREDENTIALS',
            'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'KEYPAD_CHANGED',
            'APP_VALIDATION_TIMEOUT', 'AUTH_ATTEMPT_EXPIRED', 'UPSTREAM_FORMAT_CHANGED',
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
        OR (bank = 'NATIXIS' AND last_sync_error IN (
            'INVALID_CREDENTIALS', 'MFA_TYPE_UNSUPPORTED', 'INVALID_OTP',
            'RATE_LIMITED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED',
            'PORTFOLIO_INCOMPLETE', 'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA',
            'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
        OR (bank = 'SOFIDY' AND last_sync_error IN (
            'INVALID_CREDENTIALS', 'MFA_INVALID', 'FIRST_VISIT_PENDING',
            'EMAIL_UNREACHABLE', 'ACCOUNT_INACTIVE', 'RATE_LIMITED',
            'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED', 'PORTFOLIO_INCOMPLETE',
            'UPSTREAM_FORMAT_CHANGED', 'INVALID_DATA', 'UPSTREAM_UNAVAILABLE',
            'INTERNAL_ERROR'))
    );

-- banque_populaire_session already enforced the failed_error and last_sync_error rules, so
-- every row fits sidecar_session as it is.
INSERT INTO sidecar_session (bank, member_id, session_state, encrypted_credentials, unsupported_contracts,
    region, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at,
    last_sync_error, created_at, updated_at)
SELECT 'BANQUE_POPULAIRE', member_id, session_state, NULL, unsupported_contracts,
    region, last_validated_at, is_active, sync_status, last_sync_started_at, last_sync_completed_at,
    last_sync_error, created_at, updated_at
FROM banque_populaire_session;

DROP TABLE banque_populaire_session;
