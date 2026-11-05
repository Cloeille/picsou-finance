package com.picsou.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only access to a CORUM client-space account.
 *
 * <p>CORUM gates nothing behind a captcha and, on the account this was
 * verified against, asks for no second factor, so authentication is a single
 * round-trip: {@link #authenticate} returns the session state directly. The
 * state is opaque to the domain -- it is whatever the sidecar needs to replay
 * the account, and it is only ever stored encrypted.
 *
 * <p>One login reads the whole portfolio at once: {@link #fetchPortfolio}
 * returns the real-estate contract, if any, and every CORUM Life
 * (assurance-vie) contract. One real-estate contract holds several funds, and
 * Picsou models one account per SCPI, so its {@link Snapshot} carries one
 * {@link Holding} per fund and the caller maps each onto its own account. A
 * life contract is one account, whose units are its holdings.
 */
public interface CorumPort {

    String authenticate(String login, String password);

    Portfolio fetchPortfolio(String sessionState);

    /**
     * Everything one CORUM login holds. {@code realEstate} is null when the
     * login has no real-estate contract; {@code life} is empty when it has no
     * life contract. Both absent is not a valid read and is refused.
     */
    record Portfolio(Snapshot realEstate, List<LifeContract> life) {}

    /**
     * One CORUM Life contract, keyed by {@code contractCode}. Every line is a
     * holding, the euro fund included: it is invested money, not cash.
     * {@code valuationLineCount} is the number of raw product rows the sidecar
     * summed, each rounded to the cent on its own -- the reconciliation
     * allowance is counted on it.
     *
     * <p>{@code snapshotComplete} has the same meaning as on {@link Snapshot}.
     *
     * <p>{@code netInvestedEur} is the contract's own net invested amount: the lines' costs
     * leave out money switched between funds, so only this one gives the contract's gain.
     * {@code paidInEur} is the gross amount paid in, {@code openedAt} the contract's opening
     * date and {@code scheduledPayment} the programmed savings plan. All four are null from a
     * sidecar that predates them, and the last three may be null on any contract.
     *
     * <p>{@code operations} is the contract's operation list as CORUM shows it. Null means it
     * was not read (a sidecar that predates it, or a failed read of that list): the stored rows
     * are then kept as they are, exactly as for an empty list.
     */
    record LifeContract(String contractCode, String currency, BigDecimal totalValuationEur,
                        LocalDate valuationDate, Integer valuationLineCount,
                        BigDecimal netInvestedEur, BigDecimal paidInEur, LocalDate openedAt,
                        ScheduledPayment scheduledPayment,
                        boolean snapshotComplete, List<LifeHolding> holdings,
                        List<LifeOperation> operations) {}

    /**
     * One operation of a life contract. {@code type} and {@code category} are CORUM's own words,
     * raw ({@code scheduledPayment}, {@code couponDetachment}...); {@code amountEur} is signed as
     * CORUM shows it (a fee is negative, a switch between funds has one leg each way).
     * {@code operationId} is CORUM's id, unique per operation except for the two legs of a switch
     * on some contracts, which the sign of the amount tells apart. A list in which two operations
     * still share an id and a sign is not written: the contract's stored rows are kept, as for a
     * null list. {@code fundCodes} names the funds a coupon came from, or is null.
     */
    record LifeOperation(String operationId, String type, String category, LocalDate date,
                         BigDecimal amountEur, List<String> fundCodes) {}

    /** A programmed savings plan: an amount and CORUM's own frequency word ({@code monthly}...). */
    record ScheduledPayment(BigDecimal amountEur, String frequency) {}

    /**
     * One fund of a life contract, already summed across the
     * management pockets that hold it. {@code valueEur} is CORUM's own
     * valuation of the line; {@code netInvestedEur} is null when CORUM does not
     * report what was paid in.
     *
     * <p>A {@code euroFund} line is counted in 1 EUR units: its quantity is its
     * value and its unit price is 1, so it is worth exactly {@code valueEur}
     * whichever way it is valued.
     *
     * <p>{@code assetClass} is CORUM's own section name for the fund ({@code realEstate},
     * {@code bondFund}, {@code euroLife}), raw, or null.
     */
    record LifeHolding(String productCode, String label, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal valueEur, BigDecimal netInvestedEur, LocalDate valuationDate,
                       boolean euroFund, String assetClass) {}

    /**
     * One fund inside a CORUM contract. Everything is EUR: a real-estate line
     * is French-domiciled and quoted in euros.
     *
     * <p>{@code withdrawalPrice} is what the fund would pay to buy the shares
     * back, and it is the only price that may become a balance.
     * {@code subscriptionPrice} is what a new share costs. CORUM's own
     * displayed value is {@code quantity * subscriptionPrice}, so it is
     * carried as {@code displayedValueEur} for display only and must never be
     * substituted for the withdrawal value -- entry fees sit between the two.
     *
     * <p>A null {@code withdrawalPrice} means CORUM is not quoting that fund
     * today. It is not a zero, and the caller must keep the previous balance.
     */
    record Holding(String fundCode, String label, BigDecimal quantity,
                   BigDecimal withdrawalPrice, BigDecimal subscriptionPrice,
                   BigDecimal displayedValueEur, LocalDate valuationDate) {

        /** Null when the withdrawal price is absent; never falls back to the subscription price. */
        public BigDecimal withdrawalValue() {
            if (quantity == null || withdrawalPrice == null) {
                return null;
            }
            return quantity.multiply(withdrawalPrice);
        }
    }

    /**
     * One read of a real-estate contract. {@code snapshotComplete} is a
     * contract, not a courtesy: a portfolio whose funds do not add up to
     * {@code totalValuationEur} is refused upstream, so a true value means the
     * envelope and every fund were read.
     */
    record Snapshot(String contractCode, String propertyRightType, String currency,
                    BigDecimal totalValuationEur, LocalDate valuationDate,
                    boolean snapshotComplete, List<Holding> holdings) {}
}
