package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.BoursoErrorCode;
import com.picsou.port.BoursoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
public class BoursoAdapter implements BoursoPort {
    private static final Logger log = LoggerFactory.getLogger(BoursoAdapter.class);
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * An app push waits on a human unlocking their phone. The sidecar caps that
     * wait at 120 s, so this has to sit above it or the adapter would time out
     * on a validation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    /** One home fetch, one dashboard fetch, and one trading call per securities account. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(90);

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public BoursoAdapter(
        @Value("${app.bourso-auth.url:http://bourso-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            WebClient.builder().baseUrl(url).build(),
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
        this.client = client;
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String customerId, String password) {
        return post(
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
        SessionResponse response = post(
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
        try {
            AccountData[] response = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionState", sessionState))
                .retrieve().bodyToMono(AccountData[].class)
                .timeout(accountsTimeout).block();
            if (response == null || response.length == 0) {
                throw coded(
                    BoursoErrorCode.PORTFOLIO_INCOMPLETE,
                    "BoursoBank returned no account",
                    null
                );
            }
            return List.of(response);
        } catch (RuntimeException ex) {
            throw mapError("Could not fetch BoursoBank accounts", ex, null);
        }
    }

    private <T> T post(
        String path,
        Object body,
        Class<T> type,
        Duration timeout,
        String message,
        BoursoErrorCode authenticationFailure
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw coded(BoursoErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    private SyncException mapError(
        String message,
        RuntimeException ex,
        BoursoErrorCode authenticationFailure
    ) {
        if (ex instanceof SyncException sync) return sync;
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(
                BoursoErrorCode.UPSTREAM_UNAVAILABLE,
                "BoursoBank took too long to respond. Please try again.",
                ex
            );
        }
        if (ex instanceof WebClientResponseException response) {
            BoursoErrorCode upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, friendlyMessage(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, friendlyMessage(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(
                    BoursoErrorCode.AUTH_ATTEMPT_EXPIRED,
                    friendlyMessage(BoursoErrorCode.AUTH_ATTEMPT_EXPIRED),
                    ex
                );
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("BoursoBank sidecar returned status {}", response.getStatusCode().value());
                return coded(
                    BoursoErrorCode.UPSTREAM_UNAVAILABLE,
                    friendlyMessage(BoursoErrorCode.UPSTREAM_UNAVAILABLE),
                    ex
                );
            }
        }
        log.error(message, ex);
        return coded(BoursoErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    private BoursoErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detailNode = body.path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                log.debug(
                    "BoursoBank error response has no textual detail code (status={})",
                    response.getStatusCode().value()
                );
                return null;
            }
            String detail = detailNode.asText();
            try {
                return BoursoErrorCode.valueOf(detail);
            } catch (IllegalArgumentException ex) {
                log.warn("BoursoBank sidecar returned unknown error code '{}'", detail);
                return null;
            }
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse BoursoBank sidecar error response (status={})",
                response.getStatusCode().value(),
                ex
            );
            return null;
        }
    }

    private boolean causedByTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String friendlyMessage(BoursoErrorCode code) {
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

    private SyncException coded(BoursoErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private record SessionResponse(String sessionState) {}
}
