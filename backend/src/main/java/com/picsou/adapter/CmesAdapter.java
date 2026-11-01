package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.CmesPort;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CmesAdapter implements CmesPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * A mobile confirmation waits on a human unlocking their phone. The sidecar
     * caps that wait at 120 s, so this has to sit above it or the adapter would
     * time out on a confirmation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    /** One situation page, then three page loads per fund. */
    private static final Duration DEFAULT_POSITIONS_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public CmesAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.CMES.label(), SidecarBank.CMES.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_POSITIONS_TIMEOUT
        );
    }

    CmesAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_POSITIONS_TIMEOUT);
    }

    CmesAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration positionsTimeout
    ) {
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.CMES, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    @Override
    public SidecarInitiate initiateAuth(String login, String password, String previousSessionState) {
        return initiate(login, password, previousSessionState, false);
    }

    @Override
    public SidecarInitiate reauthenticate(String login, String password, String previousSessionState) {
        return initiate(login, password, previousSessionState, true);
    }

    private SidecarInitiate initiate(String login, String password, String previousSessionState, boolean unattended) {
        Map<String, Object> body = new HashMap<>();
        if (unattended) body.put("unattended", true);
        body.put("login", login);
        body.put("password", password);
        if (previousSessionState != null && !previousSessionState.isBlank()) {
            body.put("sessionState", previousSessionState);
        }
        return sidecar.post("/initiate", body, SidecarInitiate.class, authTimeout,
            "Could not initiate Crédit Mutuel Épargne Salariale authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        // A null code is the mobile-confirmation case; the sidecar forbids
        // unknown fields, so the key carries an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SidecarSession response = sidecar.post("/complete", body, SidecarSession.class, validationTimeout,
            "Could not complete Crédit Mutuel Épargne Salariale authentication", SidecarErrorCode.APP_VALIDATION_TIMEOUT);
        return response.sessionState();
    }

    @Override
    public List<PlanData> fetchPlans(String sessionState) {
        return sidecar.postForList(
            "/positions",
            Map.of("sessionState", sessionState),
            PlanData[].class,
            positionsTimeout,
            "Could not fetch Crédit Mutuel Épargne Salariale plans",
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "Crédit Mutuel Épargne Salariale returned no complete plan"
        );
    }
}
