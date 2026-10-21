package com.picsou.model;

/**
 * What happened the last time we asked Yahoo for a ticker's dividends.
 *
 * <p>The same four values as {@link SecurityProfileStatus}, and for the same reason: an empty
 * history that we looked up is not the same as a lookup that never ran, and neither is a
 * transport failure. Collapsing them used to make a transient 429 look like "this stock pays
 * nothing", which the forecast would then believe for a week.
 */
public enum DividendSourceStatus {

    /** No row has been resolved yet. Due immediately. */
    NEVER_FETCHED,

    /** Resolved, with at least one ex-date. */
    OK,

    /** Resolved, and Yahoo publishes no dividends for it. */
    NO_DATA,

    /** The lookup failed. Retried sooner than a success, and never overwrites what we had. */
    FAILED
}
