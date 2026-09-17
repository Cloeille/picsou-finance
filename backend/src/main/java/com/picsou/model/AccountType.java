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
    LOAN,
    EMPLOYEE_SAVINGS,
    ASSURANCE_VIE,
    SCPI,
    OTHER;

    /**
     * Whether this account holds <em>positions</em> rather than a balance — the types whose value
     * is recomputed from {@code account_holding} rows derived from BUY/SELL transactions.
     *
     * <p>Lives on the enum because the answer decides whether a write path must recompute holdings,
     * and three separate copies of the same set (manual entry, CSV import, ISIN repair) would be
     * three chances for them to disagree about what an account is.
     *
     * <p>{@code ASSURANCE_VIE} is in: no connector syncs one, so typing the unit-linked lines
     * by hand is the only way to get instrument-level detail, and without it every euro of
     * life insurance would be invisible to the sector and country breakdowns. Its euro fund
     * goes in {@code cashBalance}, the slot the cash inside a PEA/CTO envelope already uses.
     *
     * <p>{@code SCPI} is deliberately out: SCPI shares have no Yahoo ticker, so pricing them
     * per line would take the "held but unpriced" branch and value the account at zero. It
     * stays a balance, like {@code REAL_ESTATE}.
     */
    public boolean isInvestment() {
        return this == PEA || this == COMPTE_TITRES || this == CRYPTO || this == ASSURANCE_VIE;
    }
}
