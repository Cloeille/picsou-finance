package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.model.AccountType;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import com.picsou.port.SidecarTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed client for the BoursoBank sidecar. Everything that knows about
 * BoursoBank's HTML, its virtual keyboard or its app-push choreography lives in
 * the sidecar behind this adapter; this class does not change when
 * BoursoBank redesigns.
 */
@Component
public class BoursoAdapter {
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

    /**
     * Signs in with the customer number and the numeric password.
     *
     * <p>Either completes outright — {@code mfaRequired == false} and
     * {@code sessionState} populated — or reports that BoursoBank pushed a
     * validation to the customer's phone.
     */
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

    /**
     * Waits for the customer to approve the push, then returns the session.
     *
     * <p>Takes no code: the app push is the only second factor supported, and
     * an SMS/e-mail prompt is reported as {@link SidecarErrorCode#MFA_TYPE_UNSUPPORTED}
     * during {@link #initiateAuth} rather than half-driven here.
     */
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

    /**
     * Reads every in-scope account: current accounts, livrets, the securities
     * accounts with their positions and the assurance-vie contracts with their
     * unit-linked supports (euro fund in {@code cashBalance}). Aggregated
     * third-party accounts, loans and children's accounts are filtered out by
     * the sidecar.
     */
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

    /**
     * One open position. {@code currentPrice} is expressed in
     * {@code quoteCurrency}; {@code buyingPriceEur}, {@code currentValueEur} and
     * {@code pnlEur} are always EUR.
     */
    public record Position(
        String isin,
        String symbol,
        String label,
        BigDecimal quantity,
        BigDecimal buyingPriceEur,
        BigDecimal currentPrice,
        String quoteCurrency,
        BigDecimal currentValueEur,
        BigDecimal pnlEur
    ) {}

    public record AccountData(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        /** Non-null for securities accounts only; a livret has no cash leg. */
        BigDecimal cashBalance,
        List<Position> positions,
        boolean snapshotComplete,
        String iban,
        List<SidecarTransaction> transactions,
        /**
         * Assurance vie only: the rows the sidecar reconciled against the valuation,
         * euro-fund rows included. Those are summed into {@code cashBalance}, so
         * {@code positions} alone cannot tell how many rounded amounts there were.
         */
        Integer valuationLineCount
    ) {
        public AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur,
                           BigDecimal cashBalance, List<Position> positions, boolean snapshotComplete) {
            this(externalId, name, type, balanceEur, cashBalance, positions, snapshotComplete, null, List.of(), null);
        }
    }
}
