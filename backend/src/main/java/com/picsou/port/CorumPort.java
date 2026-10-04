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
 * <p>One CORUM contract holds several funds, and Picsou models one account per
 * vehicle, so {@link #fetchSnapshot} returns one {@link Holding} per fund and
 * the caller maps each onto its own account.
 */
public interface CorumPort {

    String authenticate(String login, String password);

    Snapshot fetchSnapshot(String sessionState);

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
