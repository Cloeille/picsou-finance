package com.picsou.port;

public enum CorumErrorCode {
    INVALID_CREDENTIALS,
    SESSION_EXPIRED,
    /** The account holds more than one real-estate contract, so one is not
     *  unambiguously the account to sync. */
    MULTIPLE_CONTRACTS,
    PORTFOLIO_INCOMPLETE,
    UPSTREAM_FORMAT_CHANGED,
    UPSTREAM_UNAVAILABLE,
    INVALID_DATA,
    INTERNAL_ERROR
}
