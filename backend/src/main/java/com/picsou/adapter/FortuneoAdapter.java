package com.picsou.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.FortuneoErrorCode;
import com.picsou.port.FortuneoPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class FortuneoAdapter implements FortuneoPort {
    private static final Logger log = LoggerFactory.getLogger(FortuneoAdapter.class);
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);
    private static final Set<String> TRUSTED_HTTP_HOSTS = Set.of(
        "fortuneo-auth", "localhost", "127.0.0.1", "::1", "[::1]"
    );

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public FortuneoAdapter(
        @Value("${app.fortuneo-auth.url:http://fortuneo-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            WebClient.builder().baseUrl(validateBaseUrl(url)).build(),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_PORTFOLIO_TIMEOUT
        );
    }

    private static String validateBaseUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid Fortuneo sidecar URL", ex);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme)) {
            return url;
        }
        if ("http".equalsIgnoreCase(scheme)
            && host != null
            && TRUSTED_HTTP_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return url;
        }
        throw new IllegalArgumentException(
            "Fortuneo sidecar URL must use HTTPS unless it targets the isolated Compose service or loopback"
        );
    }

    FortuneoAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_PORTFOLIO_TIMEOUT);
    }

    FortuneoAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration portfolioTimeout
    ) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password) {
        return post("/initiate", Map.of("login", login, "password", password), InitiateResult.class,
            "Could not initiate Fortuneo authentication", FortuneoErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        SessionResponse response = post("/complete", Map.of("processId", processId, "code", code),
            SessionResponse.class, "Could not complete Fortuneo authentication", FortuneoErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        try {
            AccountData[] response = client.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("sessionState", sessionState))
                .retrieve().bodyToMono(AccountData[].class)
                .timeout(portfolioTimeout).block();
            if (response == null || response.length == 0) {
                throw coded(
                    FortuneoErrorCode.PORTFOLIO_INCOMPLETE,
                    "Fortuneo returned no complete portfolio accounts",
                    null
                );
            }
            return List.of(response);
        } catch (RuntimeException ex) {
            throw mapError("Could not fetch Fortuneo portfolio", ex, null);
        }
    }

    private <T> T post(
        String path,
        Object body,
        Class<T> type,
        String message,
        FortuneoErrorCode authenticationFailure
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(authTimeout).block();
            if (response == null) {
                throw coded(FortuneoErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            throw mapError(message, ex, authenticationFailure);
        }
    }

    private SyncException mapError(
        String message,
        RuntimeException ex,
        FortuneoErrorCode authenticationFailure
    ) {
        if (ex instanceof SyncException sync) return sync;
        if (causedByTimeout(ex)) {
            log.warn("{}: sidecar request timed out", message);
            return coded(
                FortuneoErrorCode.UPSTREAM_UNAVAILABLE,
                "Fortuneo took too long to respond. Please try again.",
                ex
            );
        }
        if (ex instanceof WebClientResponseException response) {
            FortuneoErrorCode upstreamCode = responseCode(response);
            if (upstreamCode != null) {
                return coded(upstreamCode, friendlyMessage(upstreamCode), ex);
            }
            if (response.getStatusCode().value() == 401 && authenticationFailure != null) {
                return coded(authenticationFailure, friendlyMessage(authenticationFailure), ex);
            }
            if (response.getStatusCode().value() == 410) {
                return coded(
                    FortuneoErrorCode.AUTH_ATTEMPT_EXPIRED,
                    friendlyMessage(FortuneoErrorCode.AUTH_ATTEMPT_EXPIRED),
                    ex
                );
            }
            if (response.getStatusCode().is5xxServerError()) {
                log.warn("Fortuneo sidecar returned status {}", response.getStatusCode().value());
                return coded(
                    FortuneoErrorCode.UPSTREAM_UNAVAILABLE,
                    friendlyMessage(FortuneoErrorCode.UPSTREAM_UNAVAILABLE),
                    ex
                );
            }
        }
        log.error(message, ex);
        return coded(FortuneoErrorCode.UPSTREAM_UNAVAILABLE, message, ex);
    }

    private FortuneoErrorCode responseCode(WebClientResponseException response) {
        try {
            JsonNode body = objectMapper.readTree(response.getResponseBodyAsString());
            JsonNode detailNode = body.path("detail");
            if (!detailNode.isTextual() || detailNode.asText().isBlank()) {
                log.debug(
                    "Fortuneo error response has no textual detail code (status={})",
                    response.getStatusCode().value()
                );
                return null;
            }
            String detail = detailNode.asText();
            try {
                return FortuneoErrorCode.valueOf(detail);
            } catch (IllegalArgumentException ex) {
                log.warn("Fortuneo sidecar returned unknown error code '{}'", detail);
                return null;
            }
        } catch (JsonProcessingException ex) {
            log.warn(
                "Could not parse Fortuneo sidecar error response (status={})",
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

    private String friendlyMessage(FortuneoErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Fortuneo rejected the credentials";
            case INVALID_OTP -> "Fortuneo rejected the verification code";
            case AUTH_ATTEMPT_EXPIRED -> "The Fortuneo authentication attempt expired";
            case SESSION_EXPIRED -> "The Fortuneo session expired";
            case INVESTOR_PROFILE_REQUIRED -> "Fortuneo requires the investor profile to be handled";
            case PORTFOLIO_INCOMPLETE -> "Fortuneo returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The Fortuneo website format changed";
            case INVALID_DATA -> "Fortuneo returned invalid portfolio data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Fortuneo is temporarily unavailable";
        };
    }

    private SyncException coded(FortuneoErrorCode code, String message, Throwable cause) {
        return new SyncException(message, cause, code.name());
    }

    private record SessionResponse(String sessionState) {}
}
