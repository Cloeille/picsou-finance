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
    ASSURANCE_VIE,
    /**
     * An individual retirement plan (PER individuel). Not {@link #EMPLOYEE_SAVINGS}, which is the
     * employer-sponsored family, and not {@link #ASSURANCE_VIE}: the money is locked until retirement.
     */
    PER,
    CREDIT_CARD,
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
     * {@code PER} is in for the same reason: an individual plan holds the same unit-linked lines
     * plus a euro fund.
     *
     * <p>{@code SCPI} is deliberately out: SCPI shares have no Yahoo ticker, so pricing them
     * per line would take the "held but unpriced" branch and value the account at zero. It
     * stays a balance, like {@code REAL_ESTATE}.
     */
    public boolean isInvestment() {
        return this == PEA || this == COMPTE_TITRES || this == CRYPTO || this == ASSURANCE_VIE || this == PER;
    }

    /**
     * Whether this account is money owed rather than wealth: the same set the accounts page
     * groups under Debts, so the dashboard and the pyramid never count one as a negative asset.
     */
    public boolean isLiability() {
        return this == LOAN || this == CREDIT_CARD;
    }

    /**
     * Whether a dividend paid into this account stays inside a wrapper the member cannot
     * freely withdraw from.
     *
     * <p>A PEA, an assurance-vie, a PER and employee savings all hold the cash inside the
     * envelope: the dividend is real and worth forecasting, but it is not money the budget
     * can spend. A brokerage account and a SCPI distribution paid in cash are. Reinvestment
     * is a separate flag — it is a choice about the payment, not a property of the account type.
     */
    public boolean isLockedWrapper() {
        return this == PEA || this == ASSURANCE_VIE || this == PER || this == EMPLOYEE_SAVINGS;
    }
}
