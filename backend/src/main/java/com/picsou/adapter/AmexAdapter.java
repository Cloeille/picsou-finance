package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonAlias;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed client for the American Express France sidecar. Everything that
 * knows about Amex's HTML or its one-time-code verification choreography
 * lives in the sidecar behind this adapter; this class does not
 * change when Amex redesigns.
 */
@Component
public class AmexAdapter {
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

    /**
     * Signs in with the card member's username and password, asking Amex to
     * send its one-time code over the given channel.
     *
     * <p>Either completes outright — {@code mfaRequired == false} and
     * {@code sessionState} populated — or reports that Amex is waiting for
     * the code it just sent.
     *
     * @param method "sms" or "email"
     */
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

    /** Submits the one-time code Amex sent, then returns the session. */
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

    /** Reads every in-scope card account with its balance, statement and recent transactions. */
    public List<AccountData> fetchAccounts(String sessionState) {
        return fetchAccounts(sessionState, false);
    }

    /** Explicit, provider-maximum transaction retrieval using the same authenticated session. */
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

    public record DirectDebit(
        boolean enabled,
        String bankName,
        String iban
    ) {}

    /**
     * The sidecar's {@code TransactionPayload} carries no stable id and spells the label/amount
     * fields {@code description}/{@code amount} (see services/amex-auth/main.py); the aliases
     * below map onto Picsou's naming without requiring a sidecar change. {@code status} is the
     * feed the row came from, {@code "posted"} or {@code "pending"}.
     */
    public record Transaction(
        String externalId,
        String date,
        @JsonAlias("description") String label,
        @JsonAlias("amount") BigDecimal amountEur,
        String status
    ) {
        public boolean pending() {
            return "pending".equalsIgnoreCase(status);
        }
    }

    public record AccountData(
        String externalId,
        String name,
        AccountType type,
        BigDecimal balanceEur,
        BigDecimal statementBalance,
        @JsonAlias("amountDue") BigDecimal paymentDueAmount,
        String dueDate,
        BigDecimal minimumPayment,
        @JsonAlias("rewardPoints") Long rewardPoints,
        List<Transaction> transactions,
        /** False when the sidecar's pending feed failed: the pending rows are then unknown. */
        boolean pendingComplete,
        boolean snapshotComplete
    ) {}
}
