package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.CicErrorCode;
import com.picsou.port.CicPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CicAdapter implements CicPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * A mobile confirmation waits on a human unlocking their phone. The sidecar
     * caps that wait at 120 s, so this has to sit above it or the adapter would
     * time out on a confirmation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    /** One page: the accounts and contracts list. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(60);

    private final SidecarErrorTranslator<CicErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public CicAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.cic-auth.url:http://cic-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("CIC", url),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_ACCOUNTS_TIMEOUT
        );
    }

    CicAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    CicAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration accountsTimeout
    ) {
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            CicErrorCode.class,
            "CIC",
            CicErrorCode.UPSTREAM_UNAVAILABLE,
            CicErrorCode.AUTH_ATTEMPT_EXPIRED,
            CicAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password, String previousSessionState) {
        Map<String, Object> body = new HashMap<>();
        body.put("login", login);
        body.put("password", password);
        if (previousSessionState != null && !previousSessionState.isBlank()) {
            body.put("sessionState", previousSessionState);
        }
        return sidecar.post("/initiate", body, InitiateResult.class, authTimeout,
            "Could not initiate CIC authentication", CicErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        // A null code is the mobile-confirmation case; the sidecar forbids
        // unknown fields, so the key carries an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SessionResponse response = sidecar.post("/complete", body, SessionResponse.class, validationTimeout,
            "Could not complete CIC authentication", CicErrorCode.APP_VALIDATION_TIMEOUT);
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        return sidecar.postForList(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountData[].class,
            accountsTimeout,
            "Could not fetch CIC accounts",
            CicErrorCode.PORTFOLIO_INCOMPLETE,
            "CIC returned no account"
        );
    }

    private static String friendlyMessage(CicErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "CIC rejected the customer number or password";
            case ACTION_REQUIRED ->
                "CIC needs you to log in on their website once and complete the pending step, then retry";
            case MFA_TYPE_UNSUPPORTED ->
                "CIC asked for a Digipass or Safetrans, which Picsou cannot handle. "
                    + "Switch your security settings to mobile confirmation or SMS.";
            case INVALID_OTP -> "CIC rejected the SMS code";
            case APP_VALIDATION_TIMEOUT -> "The mobile confirmation was not approved in time";
            case APP_VALIDATION_CANCELLED -> "The mobile confirmation was refused in the app";
            case AUTH_ATTEMPT_EXPIRED -> "The CIC authentication attempt expired";
            case SESSION_EXPIRED -> "The CIC session expired";
            case PORTFOLIO_INCOMPLETE -> "CIC returned an incomplete account list";
            case UPSTREAM_FORMAT_CHANGED -> "The CIC website format changed";
            case INVALID_DATA -> "CIC returned invalid account data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "CIC is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}
