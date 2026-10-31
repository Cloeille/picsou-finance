package com.picsou.model;

/**
 * What happened the last time we asked about a security.
 *
 * <p>Exists to separate "we looked and there is nothing to find" from "the lookup broke". Both
 * used to leave an empty profile, so the breakdown told the user to classify a fund by hand when
 * the truth was a transient scrape failure — advice that is not merely unhelpful but wrong, since
 * a hand-made override then permanently masks whatever the provider later learns.
 */
public enum SecurityProfileStatus {

    /** Seeded from an ISIN by a sync and never resolved. Due immediately. */
    NEVER_FETCHED,

    /** Resolved, with data. */
    OK,

    /** Resolved, and the source genuinely publishes nothing for it — an unlisted fund, say. */
    NO_DATA,

    /** The lookup failed. Retried sooner than a success, and never overwrites what we had. */
    FAILED
}
