package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarInitiate;
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
 * Read-only access to a Sofidy Espace Associé account.
 *
 * <p>Sofidy has no public API. Its client space is server-rendered PHP behind a
 * six-digit associate code, and after the password it always asks for a
 * verification code by e-mail, so authentication is always interactive:
 * {@link #initiateAuth} starts it and {@link #completeAuth} finishes it once
 * the user has typed the code. The state is opaque to the domain -- it is
 * whatever the sidecar needs to replay the account, and it is only ever stored
 * encrypted.
 *
 * <p>The portal publishes the withdrawal price itself: its "Valeur unitaire"
 * column is the redemption value for a capital-variable fund, which is the one
 * figure allowed to become a balance. Unlike CORUM there is no subscription
 * price and no displayed value to misread, so there is nothing else to carry.
 */
@Component
public class SofidyAdapter {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * The sidecar holds the pending login open for as long as its own TTL and
     * gives up first, so this has to sit above it or the adapter would time out on
     * a verification about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(90);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public SofidyAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.SOFIDY.label(), SidecarBank.SOFIDY.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    SofidyAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_POSITIONS_TIMEOUT);
    }

    SofidyAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration positionsTimeout
    ) {
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.SOFIDY, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    public SidecarInitiate initiateAuth(String associateCode, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("associateCode", associateCode, "password", password),
            SidecarInitiate.class,
            authTimeout,
            "Could not initiate Sofidy authentication",
            SidecarErrorCode.INVALID_CREDENTIALS
        );
    }

    public String completeAuth(String processId, String code) {
        return sidecar.post(
            "/complete",
            Map.of("processId", processId, "code", code),
            SidecarSession.class,
            validationTimeout,
            "Could not complete Sofidy authentication",
            SidecarErrorCode.MFA_INVALID
        ).sessionState();
    }

    public Snapshot fetchSnapshot(String sessionState) {
        Snapshot snapshot = sidecar.post(
            "/positions",
            Map.of("sessionState", sessionState),
            Snapshot.class,
            positionsTimeout,
            "Could not fetch the Sofidy portfolio",
            // A bare 401 on this endpoint can only mean the session died: the
            // credentials and the code were already checked at authentication.
            // Without this a 401 whose body carries no code would fall through to
            // UPSTREAM_UNAVAILABLE and read as an outage rather than a reconnect.
            SidecarErrorCode.SESSION_EXPIRED
        );
        if (!snapshot.snapshotComplete()) {
            // The sidecar refuses a portfolio whose lines do not add up to the
            // total Sofidy itself prints, so an incomplete flag means the flag is
            // not trustworthy either. Refuse rather than write a partial portfolio
            // over a real balance.
            throw sidecar.coded(
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "Sofidy returned an incomplete portfolio",
                null
            );
        }
        return snapshot;
    }

    /**
     * One fund line of the Sofidy portfolio. Everything is EUR: the funds are
     * French-domiciled and quoted in euros.
     *
     * <p>{@code withdrawalPrice} is the fund's redemption value and the only
     * price that may become a balance. A null value means Sofidy is not quoting
     * that fund today -- it is not a zero, and the caller must keep the previous
     * balance rather than clear it.
     *
     * <p>{@code fundCode} is Sofidy's own {@code Code_Produit} (DY for
     * SOFIDYNAMIC), the identifier an account is linked to. It is stable and
     * short where the fund's marketing name is neither.
     */
    public record Holding(String fundCode, String label, BigDecimal quantity,
                          BigDecimal withdrawalPrice, BigDecimal totalEur) {

        /** Null when the withdrawal price is absent; never falls back to another price. */
        public BigDecimal withdrawalValue() {
            if (quantity == null || withdrawalPrice == null) {
                return null;
            }
            return quantity.multiply(withdrawalPrice);
        }
    }

    /**
     * One read of the Sofidy portfolio. {@code snapshotComplete} is a contract,
     * not a courtesy: a portfolio whose lines do not add up to the total Sofidy
     * itself prints is refused upstream, so a true value means every line was
     * read.
     *
     * <p>There is no envelope here, unlike CORUM: Sofidy serves one portfolio per
     * login, so the account is never ambiguous.
     */
    public record Snapshot(String currency, BigDecimal totalEur, LocalDate valuationDate,
                           boolean snapshotComplete, List<Holding> holdings) {}
}
