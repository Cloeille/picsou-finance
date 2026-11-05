package com.picsou.port;

/**
 * Stable failure codes the {@code lbp-auth} sidecar returns in its RFC 7807 {@code detail}
 * field, persisted on {@code lbp_session.last_sync_error}.
 *
 * <p>The column carries a CHECK constraint enumerating these values (V122): adding a constant
 * here needs a migration.
 *
 * <p>The login codes can never deactivate a stored session: a failed login says nothing about
 * the session already held.
 */
public enum LbpErrorCode {
    SESSION_EXPIRED,
    UPSTREAM_UNAVAILABLE,
    UPSTREAM_FORMAT_CHANGED,
    INVALID_SESSION_STATE,
    INTERNAL_ERROR,
    /** The bank refused the identifier or the password. One attempt was spent: never retry. */
    INVALID_CREDENTIALS,
    /** The login keypad is not the one the sidecar knows; nothing was clicked or sent. */
    KEYPAD_CHANGED,
    /** The user took longer than the keypad's lifetime to click the digits. Nothing was clicked. */
    KEYPAD_EXPIRED,
    /** The clicked positions are not a valid choice on a ten-key pad (length or range). */
    INVALID_POSITIONS,
    /** The human did not approve the sign-in in the bank's mobile app in time. */
    APP_VALIDATION_TIMEOUT,
    /** The pending login is unknown, used, expired or belongs to someone else. */
    AUTH_ATTEMPT_EXPIRED
}
