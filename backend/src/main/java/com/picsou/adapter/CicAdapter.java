package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarSession;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.exception.SyncException;
import com.picsou.port.CicErrorCode;
import com.picsou.port.CicPort;
import com.picsou.port.SidecarInitiate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CicAdapter implements CicPort {
    private static final Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(45);
    /**
     * A mobile confirmation waits on a human unlocking their phone. The sidecar
     * caps that wait at 120 s, so this has to sit above it or the adapter would
     * time out on a confirmation that was about to succeed.
     */
    private static final Duration DEFAULT_VALIDATION_TIMEOUT = Duration.ofSeconds(150);
    /** One page: the accounts and contracts list. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(60);

    private final SidecarErrorTranslator<CicErrorCode> sidecar;
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
            clients.create("CIC", "cic-auth"),
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
        this.sidecar = new SidecarErrorTranslator<>(
            client,
            objectMapper,
            CicErrorCode.class,
            "CIC",
            CicErrorCode.UPSTREAM_UNAVAILABLE,
            CicErrorCode.AUTH_ATTEMPT_EXPIRED,
            CicAdapter::friendlyMessage
        );
        this.objectMapper = objectMapper;
        this.authTimeout = authTimeout;
        this.validationTimeout = validationTimeout;
        this.accountsTimeout = accountsTimeout;
    }

    @Override
    public SidecarInitiate initiateAuth(String login, String password, String previousSessionState) {
        return initiate(login, password, previousSessionState, false);
    }

    @Override
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
            "Could not initiate CIC authentication", CicErrorCode.INVALID_CREDENTIALS);
    }

    @Override
    public String completeAuth(String processId, String code) {
        // A null code is the mobile-confirmation case; the sidecar forbids
        // unknown fields, so the key carries an explicit null rather than be omitted.
        Map<String, Object> body = new HashMap<>();
        body.put("processId", processId);
        body.put("code", code);
        SidecarSession response = sidecar.post("/complete", body, SidecarSession.class, validationTimeout,
            "Could not complete CIC authentication", CicErrorCode.APP_VALIDATION_TIMEOUT);
        return response.sessionState();
    }

    @Override
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
                CicErrorCode.PORTFOLIO_INCOMPLETE,
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
            throw sidecar.coded(CicErrorCode.PORTFOLIO_INCOMPLETE, "CIC returned no account", null);
        }
        AccountData[] parsed;
        try {
            parsed = objectMapper.treeToValue(accounts, AccountData[].class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            throw sidecar.coded(CicErrorCode.INVALID_DATA, "CIC returned unreadable accounts", ex);
        }
        return new AccountListing(List.of(parsed), categories);
    }

    private static String friendlyMessage(CicErrorCode code) {
        return switch (code) {
            case INVALID_CREDENTIALS -> "CIC rejected the customer number or password";
            case ACTION_REQUIRED ->
                "CIC needs you to log in on their website once and complete the pending step, then retry";
            case MFA_TYPE_UNSUPPORTED ->
                "CIC asked for a Digipass or Safetrans, which Picsou cannot handle. "
                    + "Switch your security settings to mobile confirmation or SMS.";
            case INVALID_OTP -> "CIC rejected the SMS code";
            case APP_VALIDATION_TIMEOUT -> "The mobile confirmation was not approved in time";
            case APP_VALIDATION_CANCELLED -> "The mobile confirmation was refused in the app";
            case AUTH_ATTEMPT_EXPIRED -> "The CIC authentication attempt expired";
            case SESSION_EXPIRED -> "The CIC session expired";
            case PORTFOLIO_INCOMPLETE -> "CIC returned an incomplete account list";
            case UPSTREAM_FORMAT_CHANGED -> "The CIC website format changed";
            case INVALID_DATA -> "CIC returned invalid account data";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "CIC is temporarily unavailable";
        };
    }
}
