package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.CmesErrorCode;
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

    private final SidecarErrorTranslator<CmesErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public CmesAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("Crédit Mutuel Épargne Salariale", "cmes-auth"),
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
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            CmesErrorCode.class,
            "Crédit Mutuel Épargne Salariale",
            CmesErrorCode.UPSTREAM_UNAVAILABLE,
            CmesErrorCode.AUTH_ATTEMPT_EXPIRED,
            CmesAdapter::friendlyMessage
        );
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
            "Could not initiate Crédit Mutuel Épargne Salariale authentication", CmesErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        // A null code is the mobile-confirmation case; the sidecar forbids
        // unknown fields, so the key carries an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SidecarSession response = sidecar.post("/complete", body, SidecarSession.class, validationTimeout,
            "Could not complete Crédit Mutuel Épargne Salariale authentication", CmesErrorCode.APP_VALIDATION_TIMEOUT);
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
            CmesErrorCode.PORTFOLIO_INCOMPLETE,
            "Crédit Mutuel Épargne Salariale returned no complete plan"
        );
    }

    private static String friendlyMessage(CmesErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Crédit Mutuel Épargne Salariale rejected the login or password";
            case ACTION_REQUIRED ->
                "Crédit Mutuel Épargne Salariale needs you to log in on their website once and complete the pending step, then retry";
            case MFA_TYPE_UNSUPPORTED ->
                "Crédit Mutuel Épargne Salariale asked for a Digipass or Safetrans, which Picsou cannot handle. "
                    + "Switch your security settings to mobile confirmation or SMS.";
            case INVALID_OTP -> "Crédit Mutuel Épargne Salariale rejected the SMS code";
            case APP_VALIDATION_TIMEOUT -> "The mobile confirmation was not approved in time";
            case APP_VALIDATION_CANCELLED -> "The mobile confirmation was refused in the app";
            case AUTH_ATTEMPT_EXPIRED -> "The Crédit Mutuel Épargne Salariale authentication attempt expired";
            case SESSION_EXPIRED -> "The Crédit Mutuel Épargne Salariale session expired";
            case PORTFOLIO_INCOMPLETE -> "Crédit Mutuel Épargne Salariale returned incomplete plans";
            case UPSTREAM_FORMAT_CHANGED -> "The Crédit Mutuel Épargne Salariale website format changed";
            case INVALID_DATA -> "Crédit Mutuel Épargne Salariale returned invalid plan data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Crédit Mutuel Épargne Salariale is temporarily unavailable";
        };
    }
}
