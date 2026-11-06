package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
    /** The sidecar's own upstream calls are capped below these. */
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_COMPLETE_TIMEOUT = Duration.ofSeconds(45);
    /** One savings list, then one product page per plan. */
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration completeTimeout;
    private final Duration positionsTimeout;

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
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.NATIXIS, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.completeTimeout = completeTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    /** Always answers with a second factor to complete: {@code mfaType} is {@code SMS}, {@code VOICE} or {@code EMAIL}. */
    public SidecarInitiate initiateAuth(String email, String password) {
        return sidecar.post("/initiate", Map.of("email", email, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Natixis Interépargne authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    /** A wrong code leaves the attempt open: the saver can type it again on the same {@code processId}. */
    public String completeAuth(String processId, String code) {
        SidecarSession response = sidecar.post("/complete", Map.of("processId", processId, "code", code),
            SidecarSession.class, completeTimeout, "Could not complete Natixis Interépargne authentication",
            SidecarErrorCode.INVALID_OTP);
        return response.sessionState();
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

    public record PositionsData(List<PlanData> plans, String sessionState) {}
}
