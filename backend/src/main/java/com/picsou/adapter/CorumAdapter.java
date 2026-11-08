package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSessionState;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
@Component
public class CorumAdapter {
    /** CORUM asks for no second factor, so this is a single round-trip: the
     *  sidecar logs in, harvests the session cookie and hands it back. */
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(60);
    /** Every contract is read, plus one call per real-estate fund, so this
     *  scales with the portfolio rather than being a single request. */
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public CorumAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.CORUM.label(), SidecarBank.CORUM.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    CorumAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration positionsTimeout
    ) {
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.CORUM, SidecarErrorCode.SESSION_EXPIRED);
        this.authTimeout = authTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    public String authenticate(String login, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("login", login, "password", password),
            SidecarSessionState.class,
            authTimeout,
            "Could not authenticate with CORUM",
            SidecarErrorCode.INVALID_CREDENTIALS
        ).sessionState();
    }

    /**
     * Reads the whole portfolio, real estate and life, in one sidecar call. A bare 401 is read as an expired
     * session, and a contract flagged incomplete fails the read as PORTFOLIO_INCOMPLETE instead of being
     * written partially.
     */
    public Portfolio fetchPortfolio(String sessionState) {
        Portfolio portfolio = sidecar.post(
            "/portfolio",
            Map.of("sessionState", sessionState),
            Portfolio.class,
            positionsTimeout,
            "Could not fetch the CORUM portfolio",
            // A bare 401 on this endpoint can only mean the session died: the
            // credentials were already checked at authentication. Without this
            // a 401 whose body carries no code would fall through to
            // UPSTREAM_UNAVAILABLE and read as an outage rather than as a
            // reconnect.
            SidecarErrorCode.SESSION_EXPIRED
        );
        // The sidecar refuses a contract whose lines do not add up, so an
        // incomplete flag means the flag is not trustworthy either. Refuse the
        // whole read rather than write a partial portfolio over a real balance:
        // one bad contract fails the sync, it never drops out of it silently.
        if (portfolio.realEstate() != null && !portfolio.realEstate().snapshotComplete()) {
            throw incomplete();
        }
        if (portfolio.life() != null) {
            for (LifeContract contract : portfolio.life()) {
                if (contract != null && !contract.snapshotComplete()) {
                    throw incomplete();
                }
            }
        }
        return portfolio;
    }

    private SyncException incomplete() {
        return sidecar.coded(
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "CORUM returned an incomplete portfolio",
            null
        );
    }

    /**
     * Everything one CORUM login holds. {@code realEstate} is null when the
     * login has no real-estate contract; {@code life} is empty when it has no
     * life contract. Both absent is not a valid read and is refused.
     */
    public record Portfolio(Snapshot realEstate, List<LifeContract> life) {}

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
    public record LifeContract(String contractCode, String currency, BigDecimal totalValuationEur,
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
    public record LifeOperation(String operationId, String type, String category, LocalDate date,
                         BigDecimal amountEur, List<String> fundCodes) {}

    /** A programmed savings plan: an amount and CORUM's own frequency word ({@code monthly}...). */
    public record ScheduledPayment(BigDecimal amountEur, String frequency) {}

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
    public record LifeHolding(String productCode, String label, BigDecimal quantity, BigDecimal unitPrice,
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
    public record Holding(String fundCode, String label, BigDecimal quantity,
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
    public record Snapshot(String contractCode, String propertyRightType, String currency,
                           BigDecimal totalValuationEur, LocalDate valuationDate,
                           boolean snapshotComplete, List<Holding> holdings) {}
}
