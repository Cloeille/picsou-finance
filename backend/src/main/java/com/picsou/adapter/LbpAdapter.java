package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * La Banque Postale sidecar client. The three login calls are deliberately plain single-shot
 * requests: there is no retry filter on the {@link WebClient}, none is added here, and a failure
 * is translated, never replayed, because a wrong password consumes a bank attempt and can lock
 * the account.
 *
 * <p>Sign-in is identifier → virtual keypad → approval in the bank's mobile app. No password
 * reaches Picsou, only clicked key positions; there is no one-time code to type.
 *
 * <p>Concrete class rather than a port: this connector has one implementation and one caller, as
 * the ports-only-for-swappable-providers ADR requires.
 */
@Component
public class LbpAdapter {
    private static final Logger log = LoggerFactory.getLogger(LbpAdapter.class);
    private static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofSeconds(45);
    /** One session replay, then the account and transaction reads. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(120);

    static final Duration INITIATE_TIMEOUT = Duration.ofSeconds(120);
    /** The sidecar gives /keypad 60 s (clicks, Valider, the mobile-approval page): stay above it. */
    static final Duration KEYPAD_TIMEOUT = Duration.ofSeconds(70);
    /** The sidecar waits up to 150 s for the human to approve on the phone: stay above it. */
    static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(170);

    private final WebClient client;
    private final SidecarClient sidecar;
    /** Same mapping, except a 410 means the pending login is gone, not that a stored session died. */
    private final SidecarClient authSidecar;
    /** Kept for a future wired token-check (the sidecar's /token-check exists); nothing calls it yet. */
    private final Duration checkTimeout;
    private final Duration accountsTimeout;
    private final Duration initiateTimeout;
    private final Duration keypadTimeout;
    private final Duration completeTimeout;

    @Autowired
    public LbpAdapter(SidecarWebClientFactory clients, ObjectMapper objectMapper) {
        this(
            // A real account with a long history may exceed Spring's 256 KB default buffer.
            clients.create(SidecarBank.LBP.label(), SidecarBank.LBP.serviceName(),
                builder -> builder
                    .clientConnector(new ReactorClientHttpConnector(httpClient()))
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))),
            objectMapper,
            DEFAULT_CHECK_TIMEOUT,
            DEFAULT_ACCOUNTS_TIMEOUT
        );
    }

    /**
     * reactor-netty silently replays a request once when a pooled connection is reset before the
     * request went out. For {@code /initiate} that would be a second password attempt the user
     * never made, so the replay is switched off: one call, one attempt.
     */
    static HttpClient httpClient() {
        return HttpClient.create().disableRetry(true);
    }

    LbpAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_CHECK_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    LbpAdapter(WebClient client, ObjectMapper objectMapper, Duration checkTimeout, Duration accountsTimeout) {
        this(client, objectMapper, checkTimeout, accountsTimeout, INITIATE_TIMEOUT, KEYPAD_TIMEOUT, COMPLETE_TIMEOUT);
    }

    LbpAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration keypadTimeout,
        Duration completeTimeout
    ) {
        this.client = client;
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.LBP, SidecarErrorCode.SESSION_EXPIRED);
        this.authSidecar = new SidecarClient(client, objectMapper, SidecarBank.LBP, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.checkTimeout = checkTimeout;
        this.accountsTimeout = accountsTimeout;
        this.initiateTimeout = initiateTimeout;
        this.keypadTimeout = keypadTimeout;
        this.completeTimeout = completeTimeout;
    }

    public InitiateResult initiateAuth(String customerId) {
        return authPost(
            "/initiate",
            new InitiateBody(customerId),
            InitiateResult.class,
            initiateTimeout,
            "Could not start the La Banque Postale login",
            SidecarErrorCode.INVALID_CREDENTIALS,
            SidecarErrorCode.APP_VALIDATION_TIMEOUT,
            null
        );
    }

    public void submitKeypad(String processId, List<Integer> positions) {
        KeypadAck ack = authPost(
            "/keypad",
            new KeypadBody(processId, positions),
            KeypadAck.class,
            keypadTimeout,
            "Could not send the La Banque Postale keypad choice",
            SidecarErrorCode.INVALID_CREDENTIALS,
            SidecarErrorCode.KEYPAD_EXPIRED,
            SidecarErrorCode.INVALID_POSITIONS
        );
        if (!"APPROVAL_PENDING".equals(ack.status())) {
            throw authSidecar.coded(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                SidecarBank.LBP.message(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED), null);
        }
    }

    public String completeAuth(String processId) {
        SessionBody body = authPost(
            "/complete",
            new CompleteBody(processId),
            SessionBody.class,
            completeTimeout,
            "Could not finish the La Banque Postale login",
            SidecarErrorCode.INVALID_CREDENTIALS,
            SidecarErrorCode.APP_VALIDATION_TIMEOUT,
            null
        );
        return body.sessionState();
    }

    /**
     * One request, no retry. Statuses that only the login contract defines (408, 409, 422, 429)
     * are resolved here; everything else goes through the shared client. No request body, header
     * or response body is ever logged or put in a message.
     *
     * @param timeoutCode       what a 408 means on this call (keypad expired, approval timed out)
     * @param unprocessableCode what a 422 means on this call, or null when it carries no such meaning
     */
    private <T> T authPost(
        String path,
        Object body,
        Class<T> type,
        Duration timeout,
        String message,
        SidecarErrorCode authenticationFailure,
        SidecarErrorCode timeoutCode,
        SidecarErrorCode unprocessableCode
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw authSidecar.coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            if (ex instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status == 408) {
                    throw authSidecar.coded(timeoutCode, SidecarBank.LBP.message(timeoutCode), null);
                }
                if (status == 422 && unprocessableCode != null) {
                    throw authSidecar.coded(unprocessableCode, SidecarBank.LBP.message(unprocessableCode), null);
                }
                if (status == 409) {
                    throw authSidecar.coded(SidecarErrorCode.KEYPAD_CHANGED,
                        SidecarBank.LBP.message(SidecarErrorCode.KEYPAD_CHANGED), null);
                }
                if (status == 429) {
                    throw authSidecar.coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE,
                        "Too many La Banque Postale logins are pending. Please wait a few minutes.", null);
                }
            }
            if (isDecodingFailure(ex)) {
                // An undecodable (or empty) sidecar response reads as the bank
                // being unavailable — the shared sidecar contract pins this code.
                // Never hand the raw exception to the logger — Jackson can quote
                // cookie tokens in its parse error text — so the cause stays null.
                log.warn("{}: sidecar returned an undecodable response", message);
                throw authSidecar.coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            throw authSidecar.mapError(message, ex, authenticationFailure);
        }
    }

    private static boolean isDecodingFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof org.springframework.core.codec.DecodingException
                || current instanceof com.fasterxml.jackson.core.JacksonException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** The identifier never leaves {@code toString}, so a logged DTO cannot leak it. */
    private record InitiateBody(String customerId) {
        @Override
        public String toString() {
            return "InitiateBody[customerId=***]";
        }
    }

    /** The positions are the user's password in disguise: redacted from {@code toString}. */
    private record KeypadBody(String processId, List<Integer> positions) {
        @Override
        public String toString() {
            return "KeypadBody[processId=" + processId + ", positions=***]";
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record KeypadAck(String processId, String status) {}

    private record CompleteBody(String processId) {}

    /** The state is a cookie jar: redacted from {@code toString}. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record SessionBody(String sessionState) {
        @Override
        public String toString() {
            return "SessionBody[sessionState=***]";
        }
    }

    public AccountsSnapshot fetchAccounts(String sessionState) {
        return sidecar.post(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountsSnapshot.class,
            accountsTimeout,
            "Could not fetch La Banque Postale accounts",
            SidecarErrorCode.SESSION_EXPIRED
        );
    }

    // --- normalized contract -----------------------------------------------------

    /** The pending login: the bank's keypad and how long the keypad step stays valid. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record InitiateResult(String processId, Keypad keypad, int expiresInSeconds) {}

    /**
     * Ten digit labels in DOM order, laid out in {@code columns} columns. The labels are the
     * visual digits of a per-load shuffled keypad; the UI sends back the clicked <b>positions</b>,
     * which is what the bank's form expects in its {@code password} field.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Keypad(List<String> keys, int columns) {}

    /** Products the sidecar saw but does not import (family code not supported). */
    public record Unsupported(String externalId, String familyCode) {}

    /**
     * Money is carried as {@link BigDecimal} parsed from the sidecar's decimal strings; a float
     * never appears. {@code kind} is {@code CURRENT_ACCOUNT}, {@code LIVRET_A}, {@code LDDS},
     * {@code LEP}, {@code CSL}, {@code LIVRET_JEUNE}, {@code PEL}, {@code CEL} or {@code CARD};
     * an unknown kind is rejected by the sync service, not guessed here. A card's {@code balance}
     * is its outstanding (zero or negative), like the Amex card. The IBAN is carried for
     * diagnosis only and is never persisted.
     */
    public record AccountData(
        String externalId,
        String kind,
        String name,
        BigDecimal balance,
        String currency,
        String iban,
        BigDecimal authorizedOverdraft,
        BigDecimal ceiling,
        BigDecimal remainingDepositCapacity,
        BigDecimal fillingRatio,
        /** {@code CREDIT}, {@code DEFERRED_DEBIT}, {@code IMMEDIATE_DEBIT} or null; cards only. */
        String cardNature,
        String parentExternalId,
        /** Cards only: next due date among the card's operations, or null. */
        LocalDate nextDueDate,
        List<Transaction> transactions,
        boolean snapshotComplete
    ) {
        public AccountData {
            transactions = transactions == null ? List.of() : List.copyOf(transactions);
        }
    }

    /** {@code amount} is signed: negative is an outflow. */
    public record Transaction(
        String externalId,
        LocalDate date,
        LocalDate dueDate,
        BigDecimal amount,
        String currency,
        String label,
        /** The bank's type code as text; null when the bank sent none. */
        String typeCode
    ) {}

    public record AccountsSnapshot(List<AccountData> accounts, List<Unsupported> unsupported) {
        public AccountsSnapshot {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }
    }
}
