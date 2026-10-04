package com.picsou.port;

/**
 * Stable failure codes the {@code amex-auth} sidecar returns in its RFC 7807
 * {@code detail} field, persisted on {@code amex_session.last_sync_error} and
 * translated by the frontend.
 *
 * <p>{@code INVALID_OTP} is a rejected one-time code (SMS or e-mail);
 * {@code AUTH_ATTEMPT_EXPIRED} covers both an unknown/expired processId and
 * a code that timed out on the verify page.
 */
public enum AmexErrorCode {
    INVALID_CREDENTIALS,
    INVALID_OTP,
    AUTH_ATTEMPT_EXPIRED,
    SESSION_EXPIRED,
    UPSTREAM_FORMAT_CHANGED,
    UPSTREAM_UNAVAILABLE,
    INVALID_DATA,
    INTERNAL_ERROR
}
