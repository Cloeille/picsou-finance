-- V87: Recognise the BoursoBank fraud-education interstitial (GH issue #114).
--
-- A successful login can land on /infos-profil/pedagogie-fraude/... until the
-- holder ticks the notice on the bank's website. The sidecar now reports that
-- state as FRAUD_ACK_REQUIRED instead of INVALID_CREDENTIALS, so the code
-- joins the CHECK enumerating bourso_session.last_sync_error.
--
-- Numbered V87, not V80: the Sep-2026 review stack already reserves V80-V86
-- on its branches (V86 carries the #115 token widening). Renumber on rebase
-- if that stack lands first; Flyway runs with out-of-order=true.

ALTER TABLE bourso_session DROP CONSTRAINT ck_bourso_session_last_sync_error;
ALTER TABLE bourso_session ADD CONSTRAINT ck_bourso_session_last_sync_error
    CHECK (
        last_sync_error IS NULL
        OR last_sync_error IN (
            'INVALID_CREDENTIALS',
            'FRAUD_ACK_REQUIRED',
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
