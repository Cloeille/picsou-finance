package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSessionState;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to a Natixis Interépargne saver's space (PEE, PERCO, PER...).
 *
 * <p>Natixis asks for a second factor on every login, so there is no unattended
 * re-login: the sidecar holds the password only for the length of an attempt,
 * and the session it returns carries a refresh token that {@code /positions}
 * may rotate. The session state is opaque to the domain and only ever stored
 * encrypted.
 */
@Component
public class NatixisAdapter {
    private static final Logger log = LoggerFactory.getLogger(NatixisAdapter.class);
    /** The sidecar's own upstream calls are capped below these. */
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_COMPLETE_TIMEOUT = Duration.ofSeconds(45);
    /** One savings list, then one product page per plan. */
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(120);
    /** Best effort on a path the user is waiting on (a disconnect): short, and never retried. */
    private static final Duration DEFAULT_CANCEL_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration completeTimeout;
    private final Duration positionsTimeout;
    private final Duration cancelTimeout;

    @Autowired
    public NatixisAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.NATIXIS.label(), SidecarBank.NATIXIS.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_COMPLETE_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    NatixisAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration completeTimeout,
        Duration positionsTimeout
    ) {
        this(client, objectMapper, authTimeout, completeTimeout, positionsTimeout, DEFAULT_CANCEL_TIMEOUT);
    }

    NatixisAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration completeTimeout,
        Duration positionsTimeout,
        Duration cancelTimeout
    ) {
        this.client = client;
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.NATIXIS, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.completeTimeout = completeTimeout;
        this.positionsTimeout = positionsTimeout;
        this.cancelTimeout = cancelTimeout;
    }

    /** Always answers with a second factor to complete: {@code mfaType} is {@code SMS}, {@code VOICE} or {@code EMAIL}. */
    public SidecarInitiate initiateAuth(String email, String password) {
        return sidecar.post("/initiate", Map.of("email", email, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Natixis Interépargne authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    /** A wrong code leaves the attempt open: the saver can type it again on the same {@code processId}. */
    public String completeAuth(String processId, String code) {
        SidecarSessionState response = sidecar.post("/complete", Map.of("processId", processId, "code", code),
            SidecarSessionState.class, completeTimeout, "Could not complete Natixis Interépargne authentication",
            SidecarErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    /**
     * Tells the sidecar to drop a parked login attempt so its pending slot frees at once.
     * Best effort: the sidecar forgets the attempt on its own after 600 s anyway, so a
     * failure is logged -- without the attempt id -- and never thrown.
     */
    public void cancelAuth(String processId) {
        try {
            client.post().uri("/cancel").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("processId", processId)).retrieve().toBodilessEntity()
                .timeout(cancelTimeout).block();
        } catch (RuntimeException ex) {
            String reason = ex instanceof WebClientResponseException response
                ? "status " + response.getStatusCode().value()
                : ex.getClass().getSimpleName();
            log.warn("Could not cancel a pending Natixis Interépargne login ({})", reason);
        }
    }

    /** The answer carries the session to store next, which may differ from the one sent. */
    public PositionsData fetchPlans(String sessionState) {
        return sidecar.post("/positions", Map.of("sessionState", sessionState), PositionsData.class,
            positionsTimeout, "Could not fetch Natixis Interépargne plans", SidecarErrorCode.SESSION_EXPIRED);
    }

    /** One FCPE line inside a plan. Everything is EUR-denominated; these funds carry no ISIN. */
    public record Position(String isin, String fundCode, String label, BigDecimal quantity,
                           BigDecimal unitValue, BigDecimal valueEur, BigDecimal pnlEur) {}

    /** One plan -- PEE, PERCO, PER... -- and the funds held inside it. */
    public record PlanData(String externalId, String name, String planKind, String employer,
                           BigDecimal balanceEur, List<Position> positions, boolean snapshotComplete) {}

    /**
     * One investment (BUY) or disinvestment (SELL) of a fund inside a plan, from the
     * five years of history Natixis keeps. {@code amountEur} and {@code feesEur} are
     * positive magnitudes; {@code date} is an ISO {@code yyyy-MM-dd} string.
     */
    public record Operation(String externalId, String planExternalId, String date, String type,
                            String label, String fundLabel, String fundCode, BigDecimal quantity,
                            BigDecimal unitValue, BigDecimal amountEur, BigDecimal feesEur) {}

    /**
     * {@code operationsComplete} is null from a sidecar that predates the history: read as
     * false, like an incomplete history, so stored operations are left alone.
     */
    /** @param operationsFrom the first listing date the history covers; null when it was not read */
    public record PositionsData(List<PlanData> plans, String sessionState,
                                Boolean operationsComplete, List<Operation> operations, String operationsFrom) {
        public boolean hasCompleteOperations() {
            return Boolean.TRUE.equals(operationsComplete) && operations != null;
        }
    }
}
