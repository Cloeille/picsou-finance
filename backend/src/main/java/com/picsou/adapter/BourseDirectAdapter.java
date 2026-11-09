package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.model.AccountType;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class BourseDirectAdapter {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DEFAULT_PORTFOLIO_TIMEOUT = Duration.ofSeconds(120);

    private final SidecarClient sidecar;
    private final Duration authTimeout;
    private final Duration portfolioTimeout;

    @Autowired
    public BourseDirectAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.BOURSE_DIRECT.label(), SidecarBank.BOURSE_DIRECT.serviceName()),
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
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.BOURSE_DIRECT, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.authTimeout = authTimeout;
        this.portfolioTimeout = portfolioTimeout;
    }

    public SidecarInitiate initiateAuth(String login, String password) {
        return sidecar.post("/initiate", Map.of("login", login, "password", password), SidecarInitiate.class,
            authTimeout, "Could not initiate Bourse Direct authentication",
            SidecarErrorCode.INVALID_CREDENTIALS);
    }

    public String completeAuth(String processId, String code) {
        SidecarSession response = sidecar.post("/complete", Map.of("processId", processId, "code", code),
            SidecarSession.class, authTimeout, "Could not complete Bourse Direct authentication",
            SidecarErrorCode.INVALID_OTP);
        return response.sessionState();
    }

    public List<AccountData> fetchAccounts(String sessionState) {
        return sidecar.postForList(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountData[].class,
            portfolioTimeout,
            "Could not fetch Bourse Direct portfolio",
            SidecarErrorCode.PORTFOLIO_INCOMPLETE,
            "Bourse Direct returned no complete portfolio accounts"
        );
    }

    public record Position(String isin, String symbol, String label, BigDecimal quantity,
                           BigDecimal buyingPriceEur, BigDecimal currentPrice, String quoteCurrency,
                           BigDecimal currentValueEur, BigDecimal pnlEur) {}

    public record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                              BigDecimal cashBalance, List<Position> positions, boolean snapshotComplete) {}
}
