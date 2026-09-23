package com.picsou.model;

public enum AccountType {
    LEP,
    LIVRET_A,
    LDDS,
    LIVRET_JEUNE,
    PEL,
    CEL,
    PEA,
    COMPTE_TITRES,
    CRYPTO,
    CHECKING,
    SAVINGS,
    REAL_ESTATE,
    /**
     * A share in a French property fund (SCPI). Not a physical property: there is no address
     * and the open-data estimator cannot price it. The balance is the withdrawal price times
     * the share count, written by {@code ScpiPositionService}, not recomputed from trades.
     */
    SCPI,
    LOAN,
    EMPLOYEE_SAVINGS,
    OTHER;

    /**
     * Whether this account holds <em>positions</em> rather than a balance — the types whose value
     * is recomputed from {@code account_holding} rows derived from BUY/SELL transactions.
     *
     * <p>Lives on the enum because the answer decides whether a write path must recompute holdings,
     * and three separate copies of the same set (manual entry, CSV import, ISIN repair) would be
     * three chances for them to disagree about what an account is.
     */
    public boolean isInvestment() {
        return this == PEA || this == COMPTE_TITRES || this == CRYPTO;
    }
}
