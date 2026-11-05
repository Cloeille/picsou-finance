package com.picsou.port;

/**
 * Stable failure codes of the {@code banque-populaire-auth} sidecar, persisted on
 * {@code banque_populaire_session.last_sync_error}. Same names as {@link BpceErrorCode}, which the
 * shared engine converts from by name.
 *
 * <p>The column carries a CHECK constraint enumerating these values (V122): adding a
 * constant here needs a migration.
 */
public enum BanquePopulaireErrorCode {
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
    AUTH_ATTEMPT_EXPIRED,
    /** The user took longer than the keypad's lifetime to click the digits. Nothing was clicked. */
    KEYPAD_EXPIRED,
    /** The clicked positions are not a valid choice on a ten-key pad (length or range). */
    INVALID_POSITIONS
}
