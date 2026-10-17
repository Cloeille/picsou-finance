package com.picsou.port;

/**
 * Stable failure codes the {@code caisse-epargne-auth} sidecar returns in its RFC 7807
 * {@code detail} field, persisted on {@code caisse_epargne_session.last_sync_error}.
 *
 * <p>The column carries a CHECK constraint enumerating these values (V109): adding a
 * constant here needs a migration.
 *
 * <p>The last four belong to the browser login ({@code /initiate}, {@code /complete}). They can
 * never deactivate a stored session: a failed login says nothing about the session already held.
 */
public enum CaisseEpargneErrorCode {
    SESSION_EXPIRED,
    UPSTREAM_UNAVAILABLE,
    UPSTREAM_FORMAT_CHANGED,
    INVALID_SESSION_STATE,
    INTERNAL_ERROR,
    /** The bank refused the identifier or the password. One attempt was spent: never retry. */
    INVALID_CREDENTIALS,
    /** The login keypad is not the one the sidecar knows; nothing was clicked or sent. */
    KEYPAD_CHANGED,
    /** Nobody approved the Sécur'Pass push in time. */
    APP_VALIDATION_TIMEOUT,
    /** The pending login is unknown, used, expired or belongs to someone else. */
    AUTH_ATTEMPT_EXPIRED
}
