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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to an Amundi Épargne Salariale account.
 *
 * <p>Amundi gates its login behind a captcha and a mandatory second factor, so
 * authentication is always interactive: {@link #initiateAuth} starts it and
 * {@link #completeAuth} finishes it once the user has validated on their phone.
 * The returned session state is opaque to the domain -- it is whatever the
 * sidecar needs to replay the account, and it is only ever stored encrypted.
 */
@Component
public class AmundiAdapter {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * An app push waits on a human unlocking their phone. The sidecar caps that
     * wait at 120 s, so this has to sit above it or the adapter would time out
     * on a validation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(90);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public AmundiAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.AMUNDI.label(), SidecarBank.AMUNDI.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    AmundiAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_POSITIONS_TIMEOUT);
    }

    AmundiAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration positionsTimeout
    ) {
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.AMUNDI, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    public SidecarInitiate initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Amundi authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    /** {@code code} is null for an app push: there is nothing for the user to type. */
    public String completeAuth(String processId, String code) {
        // A null code is the app-push case; the sidecar forbids unknown fields,
        // so the key has to carry an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SidecarSession response = sidecar.post("/complete", body, SidecarSession.class, validationTimeout,
            "Could not complete Amundi authentication", SidecarErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    public List<PlanData> fetchPlans(String sessionState) {
        return sidecar.postForList(
            "/positions",
            Map.of("sessionState", sessionState),
            PlanData[].class,
            positionsTimeout,
            "Could not fetch Amundi savings plans",
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "Amundi returned no complete savings plan"
        );
    }

    /**
     * One FCPE line inside a plan. Everything is EUR-denominated: épargne
     * salariale funds are French-domiciled and quoted in euros, and Amundi
     * reports the valuation itself rather than leaving it to be recomputed.
     */
    public record Position(String isin, String fundCode, String label, BigDecimal quantity,
                           BigDecimal unitValue, BigDecimal valueEur, BigDecimal pnlEur) {}

    /** One dispositif -- a PEG, PERCO, PER... -- and the lines held inside it. */
    public record PlanData(String externalId, String name, String planKind, String employer,
                           BigDecimal balanceEur, List<Position> positions, boolean snapshotComplete) {}
}
