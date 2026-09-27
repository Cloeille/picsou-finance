package com.picsou.port;

public enum SofidyErrorCode {
    INVALID_CREDENTIALS,
    /**
     * The verification code by e-mail was wrong, expired, or already used. The
     * caller can retry with a new one: the portal itself offers to re-send.
     */
    MFA_INVALID,
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
    /** The pending login expired before the code was entered. */
    AUTH_ATTEMPT_EXPIRED,
    SESSION_EXPIRED,
    PORTFOLIO_INCOMPLETE,
    UPSTREAM_FORMAT_CHANGED,
    UPSTREAM_UNAVAILABLE,
    INVALID_DATA,
    INTERNAL_ERROR
}
