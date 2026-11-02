package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
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
 * <p>One CORUM contract holds several funds, and Picsou models one account per
 * vehicle, so {@link #fetchSnapshot} returns one {@link Holding} per fund and
 * the caller maps each onto its own account.
 */
@Component
public class CorumAdapter {
    /** CORUM asks for no second factor, so this is a single round-trip: the
     *  sidecar logs in, harvests the session cookie and hands it back. */
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(60);
    /** One contract read plus one call per fund, so this scales with the
     *  portfolio rather than being a single request. */
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(90);

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
            SidecarSession.class,
            authTimeout,
            "Could not authenticate with CORUM",
            SidecarErrorCode.INVALID_CREDENTIALS
        ).sessionState();
    }

    public Snapshot fetchSnapshot(String sessionState) {
        Snapshot snapshot = sidecar.post(
            "/positions",
            Map.of("sessionState", sessionState),
            Snapshot.class,
            positionsTimeout,
            "Could not fetch the CORUM portfolio",
            // A bare 401 on this endpoint can only mean the session died: the
            // credentials were already checked at authentication. Without this
            // a 401 whose body carries no code would fall through to
            // UPSTREAM_UNAVAILABLE and read as an outage rather than as a
            // reconnect.
            SidecarErrorCode.SESSION_EXPIRED
        );
        if (!snapshot.snapshotComplete()) {
            // The sidecar refuses a portfolio whose funds do not add up to the
            // contract, so an incomplete flag means the flag is not trustworthy
            // either. Refuse rather than write a partial portfolio over a real
            // balance.
            throw sidecar.coded(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CORUM returned an incomplete portfolio",
                null
            );
        }
        return snapshot;
    }

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
