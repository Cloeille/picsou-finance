package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BoursoPort;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public BoursoAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.BOURSO.label(), SidecarBank.BOURSO.serviceName()),
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
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.BOURSO, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public SidecarInitiate initiateAuth(String customerId, String password) {
        return sidecar.post(
            "/initiate",
            Map.of("customerId", customerId, "password", password),
            SidecarInitiate.class,
            authTimeout,
            "Could not initiate BoursoBank authentication",
            SidecarErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId) {
        // The sidecar forbids unknown fields but declares `code`, so the key has
        // to carry an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", null);
        SidecarSession response = sidecar.post(
            "/complete",
            body,
            SidecarSession.class,
            validationTimeout,
            "Could not complete BoursoBank authentication",
            SidecarErrorCode.APP_VALIDATION_TIMEOUT
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
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "BoursoBank returned no account"
        );
    }
}
