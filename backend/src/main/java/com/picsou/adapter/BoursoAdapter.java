package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.BoursoErrorCode;
import com.picsou.port.BoursoPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BoursoAdapter implements BoursoPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * An app push waits on a human unlocking their phone. The sidecar caps that
     * wait at 120 s, so this has to sit above it or the adapter would time out
     * on a validation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    /** One home fetch, one dashboard fetch, and one trading call per securities account. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(90);

    private final SidecarErrorTranslator<BoursoErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public BoursoAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.bourso-auth.url:http://bourso-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("BoursoBank", url),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_ACCOUNTS_TIMEOUT
        );
    }

    BoursoAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    BoursoAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration accountsTimeout
    ) {
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            BoursoErrorCode.class,
            "BoursoBank",
            BoursoErrorCode.UPSTREAM_UNAVAILABLE,
            BoursoErrorCode.AUTH_ATTEMPT_EXPIRED,
            BoursoAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String customerId, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("customerId", customerId, "password", password),
            InitiateResult.class,
            authTimeout,
            "Could not initiate BoursoBank authentication",
            BoursoErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId) {
        // The sidecar forbids unknown fields but declares `code`, so the key has
        // to carry an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", null);
        SessionResponse response = sidecar.post(
            "/complete",
            body,
            SessionResponse.class,
            validationTimeout,
            "Could not complete BoursoBank authentication",
            BoursoErrorCode.APP_VALIDATION_TIMEOUT
        );
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        return sidecar.postForList(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountData[].class,
            accountsTimeout,
            "Could not fetch BoursoBank accounts",
            BoursoErrorCode.PORTFOLIO_INCOMPLETE,
            "BoursoBank returned no account"
        );
    }

    private static String friendlyMessage(BoursoErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "BoursoBank rejected the customer number or password";
            case FRAUD_ACK_REQUIRED ->
                "BoursoBank needs you to log in on their website and validate the fraud-prevention notice once, then retry";
            case MFA_TYPE_UNSUPPORTED ->
                "BoursoBank asked for an SMS or e-mail code, which Picsou cannot handle. "
                    + "Switch your BoursoBank security settings to app validation.";
            case APP_VALIDATION_TIMEOUT -> "The BoursoBank app validation was not confirmed in time";
            case AUTH_ATTEMPT_EXPIRED -> "The BoursoBank authentication attempt expired";
            case SESSION_EXPIRED -> "The BoursoBank session expired";
            case PORTFOLIO_INCOMPLETE -> "BoursoBank returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The BoursoBank website format changed";
            case INVALID_DATA -> "BoursoBank returned invalid account data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "BoursoBank is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}
