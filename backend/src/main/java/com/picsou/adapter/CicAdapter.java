package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only access to a CIC online-banking customer space.
 *
 * <p>PSD2 only exposes CIC's current accounts; this reads the savings accounts
 * too. The login is a plain form; the site then asks for a mobile confirmation
 * or sends a code by SMS, unless it still remembers the device.
 * {@link #initiateAuth} takes the previous session so the sidecar can replay
 * that remembered-device cookie: a re-login with stored credentials then
 * completes without anyone at the phone. The session state is opaque to the
 * domain and only ever stored encrypted.
 */
@Component
public class CicAdapter {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * A mobile confirmation waits on a human unlocking their phone. The sidecar
     * caps that wait at 120 s, so this has to sit above it or the adapter would
     * time out on a confirmation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    /** One page: the accounts and contracts list. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(60);

    private final SidecarClient sidecar;
    private final ObjectMapper objectMapper;
    private final Duration authTimeout;
    private final Duration validationTimeout;
    private final Duration accountsTimeout;

    @Autowired
    public CicAdapter(
        SidecarWebClientFactory clients,
        ObjectMapper objectMapper
    ) {
        this(
            clients.create(SidecarBank.CIC.label(), SidecarBank.CIC.serviceName()),
            objectMapper,
            DEFAULT_AUTH_TIMEOUT,
            DEFAULT_VALIDATION_TIMEOUT,
            DEFAULT_ACCOUNTS_TIMEOUT
        );
    }

    CicAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_AUTH_TIMEOUT, DEFAULT_VALIDATION_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    CicAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration authTimeout,
        Duration validationTimeout,
        Duration accountsTimeout
    ) {
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.CIC, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    /** {@code previousSessionState} is null on a first login. {@code mfaType} is {@code APP_PUSH} or {@code SMS}. */
    public SidecarInitiate initiateAuth(String login, String password, String previousSessionState) {
        return initiate(login, password, previousSessionState, false);
    }

    /**
     * The same login for a scheduled re-login with stored credentials: nobody is
     * there to approve, so the sidecar withdraws a mobile confirmation at once and
     * reports the session as expired rather than leaving a request on the phone.
     */
    public SidecarInitiate reauthenticate(String login, String password, String previousSessionState) {
        return initiate(login, password, previousSessionState, true);
    }

    private SidecarInitiate initiate(String login, String password, String previousSessionState, boolean unattended) {
        Map<String, Object> body = new HashMap<>();
        if (unattended) body.put("unattended", true);
        body.put("login", login);
        body.put("password", password);
        if (previousSessionState != null && !previousSessionState.isBlank()) {
            body.put("sessionState", previousSessionState);
        }
        return sidecar.post("/initiate", body, SidecarInitiate.class, authTimeout,
            "Could not initiate CIC authentication", SidecarErrorCode.INVALID_CREDENTIALS);
    }

    /**
     * Finishes the second factor: {@code code} is the SMS code, or null for a
     * mobile confirmation, which waits for the customer to approve in the app.
     */
    public String completeAuth(String processId, String code) {
        // A null code is the mobile-confirmation case; the sidecar forbids
        // unknown fields, so the key carries an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SidecarSession response = sidecar.post("/complete", body, SidecarSession.class, validationTimeout,
            "Could not complete CIC authentication", SidecarErrorCode.APP_VALIDATION_TIMEOUT);
        return response.sessionState();
    }

    /** The current and savings accounts, balances only. Insurance and other contracts are left out by the sidecar. */
    public AccountListing fetchAccounts(String sessionState) {
        JsonNode response;
        try {
            response = sidecar.post(
                "/accounts/listing",
                Map.of("sessionState", sessionState),
                JsonNode.class,
                accountsTimeout,
                "Could not fetch CIC accounts",
                null
            );
        } catch (SyncException ex) {
            // The sidecar answers 404 for nothing but an unknown route: an older sidecar,
            // which only serves the bare list on /accounts and says nothing about the tables.
            if (!(ex.getCause() instanceof WebClientResponseException http) || http.getStatusCode().value() != 404) {
                throw ex;
            }
            return AccountListing.withoutCategories(sidecar.postForList(
                "/accounts",
                Map.of("sessionState", sessionState),
                AccountData[].class,
                accountsTimeout,
                "Could not fetch CIC accounts",
                SidecarErrorCode.PORTFOLIO_INCOMPLETE,
                "CIC returned no account"
            ));
        }
        // {accounts, categoriesRead}; a bare list carries no tables either.
        JsonNode accounts = response.isArray() ? response : response.path("accounts");
        Set<String> categories = null;
        if (!response.isArray() && response.path("categoriesRead").isArray()) {
            categories = new HashSet<>();
            for (JsonNode category : response.path("categoriesRead")) {
                categories.add(category.asText());
            }
        }
        if (!accounts.isArray() || accounts.isEmpty()) {
            // Nothing back means the read fell short, not an empty portfolio.
            throw sidecar.coded(SidecarErrorCode.PORTFOLIO_INCOMPLETE, "CIC returned no account", null);
        }
        AccountData[] parsed;
        try {
            parsed = objectMapper.treeToValue(accounts, AccountData[].class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            throw sidecar.coded(SidecarErrorCode.INVALID_DATA, "CIC returned unreadable accounts", ex);
        }
        return new AccountListing(List.of(parsed), categories);
    }

    /**
     * The accounts, and the in-scope tables the page carried ({@code CHECKING}, {@code SAVINGS}),
     * an empty one included. {@code categoriesRead} is null when the sidecar does not say
     * (an older sidecar answering with a bare list).
     */
    public record AccountListing(List<AccountData> accounts, Set<String> categoriesRead) {
        public AccountListing {
            accounts = List.copyOf(accounts);
            categoriesRead = categoriesRead == null ? null : Set.copyOf(categoriesRead);
        }

        /** A listing that does not say which tables were read. */
        public static AccountListing withoutCategories(List<AccountData> accounts) {
            return new AccountListing(accounts, null);
        }
    }

    public record AccountData(String externalId, String name, AccountType type, BigDecimal balanceEur, boolean snapshotComplete) {}
}
