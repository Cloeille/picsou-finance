package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.BourseDirectErrorCode;
import com.picsou.port.BourseDirectPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class BourseDirectAdapter implements BourseDirectPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarErrorTranslator<BourseDirectErrorCode> sidecar;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public BourseDirectAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.bourse-direct-auth.url:http://bourse-direct-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create("Bourse Direct", url),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_PORTFOLIO_TIMEOUT
        );
    }

    BourseDirectAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_PORTFOLIO_TIMEOUT);
    }

    BourseDirectAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration portfolioTimeout
    ) {
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            BourseDirectErrorCode.class,
            "Bourse Direct",
            BourseDirectErrorCode.UPSTREAM_UNAVAILABLE,
            BourseDirectErrorCode.AUTH_ATTEMPT_EXPIRED,
            BourseDirectAdapter::friendlyMessage
        );
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    @Override
    public InitiateResult initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), InitiateResult.class,
            authTimeout, "Could not initiate Bourse Direct authentication",
            BourseDirectErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        SessionResponse response = sidecar.post("/complete", Map.of("processId", processId, "code", code),
            SessionResponse.class, authTimeout, "Could not complete Bourse Direct authentication",
            BourseDirectErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        return sidecar.postForList(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountData[].class,
            portfolioTimeout,
            "Could not fetch Bourse Direct portfolio",
            BourseDirectErrorCode.PORTFOLIO_INCOMPLETE,
            "Bourse Direct returned no complete portfolio accounts"
        );
    }

    private static String friendlyMessage(BourseDirectErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "Bourse Direct rejected the credentials";
            case INVALID_OTP -> "Bourse Direct rejected the verification code";
            case AUTH_ATTEMPT_EXPIRED -> "The Bourse Direct authentication attempt expired";
            case SESSION_EXPIRED -> "The Bourse Direct session expired";
            case PORTFOLIO_INCOMPLETE -> "Bourse Direct returned an incomplete portfolio";
            case UPSTREAM_FORMAT_CHANGED -> "The Bourse Direct website format changed";
            case INVALID_DATA -> "Bourse Direct returned invalid portfolio data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Bourse Direct is temporarily unavailable";
        };
    }

    private record SessionResponse(String sessionState) {}
}
