-- BNP Paribas (and Hello bank!) keeps its session in sidecar_session (V142), like Natixis (V146):
-- one row per member, the encrypted cookie jar in session_state and no stored login or code. This
-- connector never shipped with a table of its own, so there is nothing to copy: the migration only
-- lets the shared table hold the bank and the codes its sidecar can report.
-- Both CHECKs are rebuilt from the V149 definitions (Banque Populaire and Trade Republic stay in
-- both) with BNP added; SidecarBankCodesTest keeps them equal to SidecarBank.

ALTER TABLE sidecar_session DROP CONSTRAINT ck_sidecar_session_bank;
ALTER TABLE sidecar_session ADD CONSTRAINT ck_sidecar_session_bank
    CHECK (bank IN ('AMEX', 'AMUNDI', 'BANQUE_POPULAIRE', 'BNP', 'BOURSE_DIRECT', 'BOURSO',
                    'CAISSE_EPARGNE', 'CIC', 'CMES', 'CORUM', 'FORTUNEO', 'NATIXIS', 'SOFIDY',
                    'TRADE_REPUBLIC'));

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
        OR (bank = 'BNP' AND last_sync_error IN (
            'SESSION_EXPIRED', 'INVALID_SESSION_STATE', 'INVALID_CREDENTIALS',
            'KEYPAD_EXPIRED', 'INVALID_POSITIONS', 'KEYPAD_CHANGED',
            'MFA_TYPE_UNSUPPORTED', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT',
            'AUTH_ATTEMPT_EXPIRED', 'RATE_LIMITED', 'ACTION_REQUIRED',
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
        OR (bank = 'TRADE_REPUBLIC' AND last_sync_error IN (
            'INVALID_CREDENTIALS', 'INVALID_OTP', 'APP_VALIDATION_TIMEOUT',
            'APP_VALIDATION_CANCELLED', 'AUTH_ATTEMPT_EXPIRED', 'SESSION_EXPIRED',
            'INVALID_SESSION_STATE', 'RATE_LIMITED', 'UPSTREAM_FORMAT_CHANGED',
            'INVALID_DATA', 'UPSTREAM_UNAVAILABLE', 'INTERNAL_ERROR'))
    );
