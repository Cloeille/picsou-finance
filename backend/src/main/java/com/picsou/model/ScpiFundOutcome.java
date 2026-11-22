package com.picsou.model;

/**
 * What the latest sync did with one provider fund.
 *
 * <p>{@code CREATED} and {@code LINKED} name the account event and win over a
 * missing price; {@code PRICE_INCOMPLETE} is only reported for a fund that was
 * already linked. {@code AMBIGUOUS}, {@code IGNORED} and
 * {@code MISSING_AT_PROVIDER} and {@code UNSUPPORTED_OWNERSHIP} wrote nothing.
 */
public enum ScpiFundOutcome {
    CREATED,
    LINKED,
    UPDATED,
    PRICE_INCOMPLETE,
    AMBIGUOUS,
    IGNORED,
    MISSING_AT_PROVIDER,
    /**
     * The provider holds the line in dismembered ownership (nue-propriété, usufruit): its share
     * count and prices describe a right worth less than full ownership, so nothing is written.
     * Louve Invest only, see {@code docs/features/louve-invest.md}.
     */
    UNSUPPORTED_OWNERSHIP;

    /** True when the sync wrote a share count onto an account. */
    public boolean applied() {
        return this == CREATED || this == LINKED || this == UPDATED || this == PRICE_INCOMPLETE;
    }
}
