package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.AmexPort;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public AmexAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.AMEX.label(), SidecarBank.AMEX.serviceName()),
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
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.AMEX, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public SidecarInitiate initiateAuth(String userId, String password, String method) {
        return sidecar.post(
            "/initiate",
            Map.of("userId", userId, "password", password, "method", method),
            SidecarInitiate.class,
            authTimeout,
            "Could not initiate Amex authentication",
            SidecarErrorCode.INVALID_CREDENTIALS
        );
    }

    @Override
    public String completeAuth(String processId, String otp) {
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("otp", otp);
        SidecarSession response = sidecar.post(
            "/complete",
            body,
            SidecarSession.class,
            validationTimeout,
            "Could not complete Amex authentication",
            SidecarErrorCode.INVALID_OTP
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
            SidecarErrorCode.INVALID_DATA,
            "Amex returned no account"
        );
    }
}
