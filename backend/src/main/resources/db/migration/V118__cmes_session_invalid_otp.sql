-- V118: the Crédit Mutuel Épargne Salariale site can send the second factor
-- as an SMS code instead of a mobile confirmation, depending on the saver's
-- access, so INVALID_OTP joins the CHECK enumerating cmes_session.last_sync_error.
ALTER TABLE cmes_session DROP CONSTRAINT ck_cmes_session_last_sync_error;
ALTER TABLE cmes_session ADD CONSTRAINT ck_cmes_session_last_sync_error
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
    );
