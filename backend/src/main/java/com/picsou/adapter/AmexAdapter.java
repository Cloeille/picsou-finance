package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.AmexErrorCode;
import com.picsou.port.AmexPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AmexAdapter implements AmexPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /** A single OTP submission round-trip, not a human approving a push. */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(90);

    private final SidecarErrorTranslator<AmexErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public AmexAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.amex-auth.url:http://amex-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("Amex", url),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_ACCOUNTS_TIMEOUT
        );
    }

    AmexAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    AmexAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration accountsTimeout
    ) {
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            AmexErrorCode.class,
            "Amex",
            AmexErrorCode.UPSTREAM_UNAVAILABLE,
            AmexErrorCode.AUTH_ATTEMPT_EXPIRED,
            AmexAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String userId, String password, String method) {
        return sidecar.post(
            "/initiate",
            Map.of("userId", userId, "password", password, "method", method),
            InitiateResult.class,
            authTimeout,
            "Could not initiate Amex authentication",
            AmexErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId, String otp) {
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("otp", otp);
        SessionResponse response = sidecar.post(
            "/complete",
            body,
            SessionResponse.class,
            validationTimeout,
            "Could not complete Amex authentication",
            AmexErrorCode.INVALID_OTP
        );
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        return fetchAccounts(sessionState, false);
    }

    @Override
    public List<AccountData> fetchTransactionHistory(String sessionState) {
        return fetchAccounts(sessionState, true);
    }

    private List<AccountData> fetchAccounts(String sessionState, boolean history) {
        Map<String, Object> body = new HashMap<>();
        body.put("sessionState", sessionState);
        if (history) body.put("history", true);
        return sidecar.postForList(
            "/accounts",
            body,
            AccountData[].class,
            history ? accountsTimeout.multipliedBy(4) : accountsTimeout,
            history ? "Could not recover Amex transaction history" : "Could not fetch Amex accounts",
            AmexErrorCode.INVALID_DATA,
            "Amex returned no account"
        );
    }

    private static String friendlyMessage(AmexErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Amex rejected the username or password";
            case INVALID_OTP -> "Amex rejected the verification code";
            case AUTH_ATTEMPT_EXPIRED -> "The Amex authentication attempt expired";
            case SESSION_EXPIRED -> "The Amex session expired";
            case UPSTREAM_FORMAT_CHANGED -> "The Amex website format changed";
            case INVALID_DATA -> "Amex returned invalid account data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Amex is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}
