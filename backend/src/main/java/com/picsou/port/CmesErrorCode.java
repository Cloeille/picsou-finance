package com.picsou.port;

/**
 * Stable failure codes the {@code cmes-auth} sidecar returns in its
 * {@code detail} field, persisted on {@code cmes_session.last_sync_error} and
 * translated by the frontend. The column carries a CHECK constraint
 * enumerating these values: adding one needs a migration.
 *
 * <p>There is no {@code INVALID_OTP}: the mobile confirmation is the only
 * second factor driven, so no code is ever submitted.
 */
public enum CmesErrorCode {
    INVALID_CREDENTIALS,
    /**
     * The site parked the login on a notice only the saver can act on (terms to
     * accept, contact details to fill in). The credentials were accepted.
     */
    ACTION_REQUIRED,
    /** The site asked for an SMS code, a Digipass or Safetrans, which this connector does not drive. */
    MFA_TYPE_UNSUPPORTED,
    APP_VALIDATION_TIMEOUT,
    /** The saver refused the confirmation request in the app. */
    APP_VALIDATION_CANCELLED,
    AUTH_ATTEMPT_EXPIRED,
    SESSION_EXPIRED,
    PORTFOLIO_INCOMPLETE,
    UPSTREAM_FORMAT_CHANGED,
    UPSTREAM_UNAVAILABLE,
    INVALID_DATA,
    INTERNAL_ERROR
}
