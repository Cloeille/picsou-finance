ALTER TABLE bourse_direct_session
    ADD CONSTRAINT ck_bourse_direct_session_last_sync_error
        CHECK (
            last_sync_error IS NULL
            OR last_sync_error IN (
                'INVALID_CREDENTIALS',
                'INVALID_OTP',
                'AUTH_ATTEMPT_EXPIRED',
                'SESSION_EXPIRED',
                'PORTFOLIO_INCOMPLETE',
                'UPSTREAM_FORMAT_CHANGED',
                'UPSTREAM_UNAVAILABLE',
                'INVALID_DATA',
                'INTERNAL_ERROR'
            )
        ),
    ADD CONSTRAINT ck_bourse_direct_session_failed_error
        CHECK (
            (sync_status = 'FAILED' AND last_sync_error IS NOT NULL)
            OR (sync_status <> 'FAILED' AND last_sync_error IS NULL)
        );
