package com.picsou.port;

/** Stable domain error codes shared by the sidecar, backend API, and frontend. */
public enum FortuneoErrorCode {
    /** The provider rejected the login identifier or password. */
    INVALID_CREDENTIALS,
    /** The provider rejected the submitted one-time password. */
    INVALID_OTP,
    /** The pending browser authentication no longer exists or expired. */
    AUTH_ATTEMPT_EXPIRED,
    /** The persisted provider session is no longer accepted. */
    SESSION_EXPIRED,
    /** Fortuneo requires the user to defer or complete the MiFID investor profile. */
    INVESTOR_PROFILE_REQUIRED,
    /** A fetched snapshot was partial or failed financial reconciliation. */
    PORTFOLIO_INCOMPLETE,
    /** Fortuneo changed an expected page or protocol structure. */
    UPSTREAM_FORMAT_CHANGED,
    /** Fortuneo or the isolated sidecar could not be reached. */
    UPSTREAM_UNAVAILABLE,
    /** Upstream data was present but malformed or internally inconsistent. */
    INVALID_DATA,
    /** An unexpected local failure prevented safe completion. */
    INTERNAL_ERROR
}
