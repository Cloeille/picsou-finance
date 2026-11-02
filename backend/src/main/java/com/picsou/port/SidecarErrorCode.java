package com.picsou.port;

/**
 * Stable failure codes the connector sidecars return in their {@code detail} field, persisted on
 * each {@code *_session.last_sync_error} column and translated by the frontend. Constant names are
 * the persisted and wire values: never rename one.
 *
 * <p>The enum is the union over every bank. Which codes a given bank may emit is declared by
 * {@code SidecarBank}, because each session table carries a CHECK constraint listing only that
 * bank's codes: a code the bank never declared must not reach the column. Adding a constant to a
 * bank's set needs a migration.
 */
public enum SidecarErrorCode {
    /** The bank refused the login identifier or the password. */
    INVALID_CREDENTIALS,
    /** The one-time code (SMS, e-mail) was wrong; the same attempt can take another try. */
    INVALID_OTP,
    /**
     * The verification code by e-mail was wrong, expired, or already used. The
     * caller can retry with a new one: the portal itself offers to re-send.
     */
    MFA_INVALID,
    /** The pending login is unknown, used, expired or belongs to someone else. */
    AUTH_ATTEMPT_EXPIRED,
    /** The persisted provider session is no longer accepted. */
    SESSION_EXPIRED,
    /** Nobody approved the app push or mobile confirmation in time. */
    APP_VALIDATION_TIMEOUT,
    /** The saver refused the confirmation request in the app. */
    APP_VALIDATION_CANCELLED,
    CAPTCHA_BLOCKED,
    /**
     * BoursoBank parked the login on its fraud-education notice
     * ({@code /infos-profil/pedagogie-fraude/...}). The credentials were
     * accepted; the holder must tick the notice on the bank's website, then
     * retry. Never auto-acknowledged: it is a legal notice.
     */
    FRAUD_ACK_REQUIRED,
    /**
     * The access holds several identities ({@code /connexion/lister-identites})
     * and the sidecar could not single out the personal one, or found only
     * business identities. Business identities are not synced.
     */
    IDENTITY_SELECTION_UNSUPPORTED,
    /** The bank asked for a second factor this connector does not drive (SMS, e-mail, Digipass, Safetrans). */
    MFA_TYPE_UNSUPPORTED,
    /**
     * The site parked the login on a notice only the saver can act on (terms to
     * accept, contact details to fill in). The credentials were accepted.
     */
    ACTION_REQUIRED,
    /** Fortuneo requires the user to defer or complete the MiFID investor profile. */
    INVESTOR_PROFILE_REQUIRED,
    /**
     * The first visit to the client space is not finished on the portal -- a
     * choice of communication channels or a signature to place. Only the account
     * holder can do it.
     */
    FIRST_VISIT_PENDING,
    /**
     * No e-mail address is attached to the account, so no code can be sent.
     * Sofidy's own advice is to contact the Service Associés.
     */
    EMAIL_UNREACHABLE,
    /** The account is dormant and the portal refuses to authenticate it. */
    ACCOUNT_INACTIVE,
    /**
     * Sofidy's brute-force counter is armed. A wrong password is answered with a
     * JSON body saying so, and this must never read as INVALID_CREDENTIALS: the
     * fix is to wait, not to retype the password.
     */
    RATE_LIMITED,
    /** The account holds more than one real-estate contract, so one is not unambiguously the account to sync. */
    MULTIPLE_CONTRACTS,
    /** The login keypad is not the one the sidecar knows; nothing was clicked or sent. */
    KEYPAD_CHANGED,
    /** The user took longer than the keypad's lifetime to click the digits. Nothing was clicked. */
    KEYPAD_EXPIRED,
    /** The clicked positions are not a valid choice on a ten-key pad (length or range). */
    INVALID_POSITIONS,
    INVALID_SESSION_STATE,
    /** A fetched snapshot was partial or failed financial reconciliation. */
    PORTFOLIO_INCOMPLETE,
    /** The bank changed an expected page or protocol structure. */
    UPSTREAM_FORMAT_CHANGED,
    /** The bank or the isolated sidecar could not be reached. */
    UPSTREAM_UNAVAILABLE,
    /** Upstream data was present but malformed or internally inconsistent. */
    INVALID_DATA,
    /** An unexpected local failure prevented safe completion. */
    INTERNAL_ERROR
}
