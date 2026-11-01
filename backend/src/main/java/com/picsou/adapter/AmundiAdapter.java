package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.AmundiPort;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AmundiAdapter implements AmundiPort {
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

    @Override
    public SidecarInitiate initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Amundi authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    @Override
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

    @Override
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
}
