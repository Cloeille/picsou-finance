-- V107: Recognise the BoursoBank identity selector refusal (GH issue #153).
--
-- An access holding a personal and a business identity is sent to
-- /connexion/lister-identites before the dashboard. The sidecar switches to
-- the personal identity, and reports IDENTITY_SELECTION_UNSUPPORTED when it
-- cannot single one out, so the code joins the CHECK enumerating
-- bourso_session.last_sync_error.

ALTER TABLE bourso_session DROP CONSTRAINT ck_bourso_session_last_sync_error;
ALTER TABLE bourso_session ADD CONSTRAINT ck_bourso_session_last_sync_error
    CHECK (
        last_sync_error IS NULL
        OR last_sync_error IN (
            'INVALID_CREDENTIALS',
            'FRAUD_ACK_REQUIRED',
            'IDENTITY_SELECTION_UNSUPPORTED',
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
    );
