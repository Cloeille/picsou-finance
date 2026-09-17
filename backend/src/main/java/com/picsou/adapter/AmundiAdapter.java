package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.AmundiErrorCode;
import com.picsou.port.AmundiPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private final SidecarErrorTranslator<AmundiErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration positionsTimeout;

    @Autowired
    public AmundiAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.amundi-auth.url:http://amundi-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("Amundi", url),
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
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            AmundiErrorCode.class,
            "Amundi",
            AmundiErrorCode.UPSTREAM_UNAVAILABLE,
            AmundiErrorCode.AUTH_ATTEMPT_EXPIRED,
            AmundiAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.positionsTimeout = positionsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), InitiateResult.class,
            authTimeout, "Could not initiate Amundi authentication", AmundiErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        // A null code is the app-push case; the sidecar forbids unknown fields,
        // so the key has to carry an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SessionResponse response = sidecar.post("/complete", body, SessionResponse.class, validationTimeout,
            "Could not complete Amundi authentication", AmundiErrorCode.INVALID_OTP);
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
            AmundiErrorCode.PORTFOLIO_INCOMPLETE,
            "Amundi returned no complete savings plan"
        );
    }

    private static String friendlyMessage(AmundiErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Amundi rejected the credentials";
            case CAPTCHA_BLOCKED -> "Amundi asked for a captcha Picsou cannot solve";
            case INVALID_OTP -> "Amundi rejected the verification code";
            case APP_VALIDATION_TIMEOUT -> "The Amundi app validation was not confirmed in time";
            case AUTH_ATTEMPT_EXPIRED -> "The Amundi authentication attempt expired";
            case SESSION_EXPIRED -> "The Amundi session expired";
            case PORTFOLIO_INCOMPLETE -> "Amundi returned incomplete savings plans";
            case UPSTREAM_FORMAT_CHANGED -> "The Amundi website format changed";
            case INVALID_DATA -> "Amundi returned invalid savings plan data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Amundi is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}
