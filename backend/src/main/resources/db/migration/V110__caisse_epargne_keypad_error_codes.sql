-- Keep the V109 error-code CHECK immutable; extend it for keypad-login failures.
ALTER TABLE caisse_epargne_session
    DROP CONSTRAINT ck_caisse_epargne_session_last_sync_error;

ALTER TABLE caisse_epargne_session
    ADD CONSTRAINT ck_caisse_epargne_session_last_sync_error
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
                'APP_VALIDATION_TIMEOUT',
                'AUTH_ATTEMPT_EXPIRED',
                'KEYPAD_EXPIRED',
                'INVALID_POSITIONS'
            )
        );
