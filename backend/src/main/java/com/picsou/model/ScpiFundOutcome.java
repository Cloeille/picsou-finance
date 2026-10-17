package com.picsou.model;

/**
 * What the latest sync did with one provider fund.
 *
 * <p>{@code CREATED} and {@code LINKED} name the account event and win over a
 * missing price; {@code PRICE_INCOMPLETE} is only reported for a fund that was
 * already linked. {@code AMBIGUOUS}, {@code IGNORED} and
 * {@code MISSING_AT_PROVIDER} wrote nothing.
 */
public enum ScpiFundOutcome {
    CREATED,
    LINKED,
    UPDATED,
    PRICE_INCOMPLETE,
    AMBIGUOUS,
    IGNORED,
    MISSING_AT_PROVIDER;

    /** True when the sync wrote a share count onto an account. */
    public boolean applied() {
        return this == CREATED || this == LINKED || this == UPDATED || this == PRICE_INCOMPLETE;
    }
}
