package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.FortuneoPort;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class FortuneoAdapter implements FortuneoPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public FortuneoAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.FORTUNEO.label(), SidecarBank.FORTUNEO.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_PORTFOLIO_TIMEOUT
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
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.FORTUNEO, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    @Override
    public SidecarInitiate initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Fortuneo authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        SidecarSession response = sidecar.post("/complete", Map.of("processId", processId, "code", code),
            SidecarSession.class, authTimeout, "Could not complete Fortuneo authentication",
            SidecarErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    @Override
    public List<AccountData> fetchAccounts(String sessionState) {
        return sidecar.postForList(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountData[].class,
            portfolioTimeout,
            "Could not fetch Fortuneo portfolio",
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "Fortuneo returned no complete portfolio accounts"
        );
    }
}
