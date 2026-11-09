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
    /**
     * Money lent to a person — a receivable, not a debt. Opposite direction from
     * {@link #LOAN}: its value is the nominal capital still owed, derived from the peer
     * loan terms and its dated repayment ledger, and it stays positive everywhere. A
     * future {@code PEER_DEBT} (money borrowed from a person) would be the symmetric type.
     */
    PEER_LOAN,
    /**
     * Physical gold held as positions: bars priced per gram and coins priced per piece. The
     * tickers come from {@link PreciousMetal}, whose {@code :} keeps them away from Yahoo.
     */
    PRECIOUS_METALS,
    /**
     * An investment account at a broker, in any country and under any tax wrapper: a US Roth IRA,
     * HSA or 401(k), a taxable brokerage account. Not {@link #COMPTE_TITRES} or {@link #PEA},
     * which are French wrappers with their own rules. The wrapper stays visible in the account's
     * name, and it is not a {@link #isLockedWrapper() locked wrapper}: Picsou cannot tell which
     * rules apply, so it assumes none.
     */
    BROKERAGE,
    OTHER;

    /**
     * Whether this account holds <em>positions</em> rather than a balance — the types whose value
     * is recomputed from {@code account_holding} rows derived from BUY/SELL transactions.
     *
     * <p>Lives on the enum because the answer decides whether a write path must recompute holdings,
     * and three separate copies of the same set (manual entry, CSV import, ISIN repair) would be
     * three chances for them to disagree about what an account is.
     *
     * <p>{@code ASSURANCE_VIE} is in: most connectors cannot read one, so typing the unit-linked
     * lines by hand is often the only way to get instrument-level detail, and without it every euro
     * of life insurance would be invisible to the sector and country breakdowns (the Caisse
     * d'Epargne connector reads the insurer's lines itself). Its euro fund goes in
     * {@code cashBalance}, the slot the cash inside a PEA/CTO envelope already uses.
     * {@code PER} is in for the same reason: an individual plan holds the same unit-linked lines
     * plus a euro fund. {@code PRECIOUS_METALS} is in because gold is held as a quantity (grams
     * or coins) of a priced product, not as a balance.
     *
     * <p>{@code SCPI} is deliberately out: SCPI shares have no Yahoo ticker, so pricing them
     * per line would take the "held but unpriced" branch and value the account at zero. It
     * stays a balance, like {@code REAL_ESTATE}.
     */
    public boolean isInvestment() {
        return this == PEA || this == COMPTE_TITRES || this == CRYPTO || this == ASSURANCE_VIE || this == PER
            || this == PRECIOUS_METALS || this == BROKERAGE;
    }

    /**
     * Whether this account's balance is the sum of its own transactions: a cash ledger.
     *
     * <p>An investment account derives its balance from its positions. A SCPI, a property and an
     * employee-savings plan are valued instead (withdrawal price, estimate or statement), and a
     * peer loan derives its balance from its principal and repayment ledger, so a transaction
     * recorded on any of them is history and must never replace that value with a
     * sum. This is the one definition the manual-transaction recomputation and both ledger
     * importers share.
     */
    public boolean isLedger() {
        return !isInvestment() && this != EMPLOYEE_SAVINGS && this != REAL_ESTATE
            && this != SCPI && this != PEER_LOAN;
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
