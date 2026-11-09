package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.adapter.sidecar.SidecarClient;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.SidecarTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client of the {@code bnp-auth} sidecar (BNP Paribas and Hello bank!).
 *
 * <p>The login is three calls: {@code /initiate} (customer number, answers with the shuffled
 * keypad tiles), {@code /keypad} (the tiles the member clicked) and {@code /complete} (the strong
 * authentication, with the SMS code when the bank asked for one). The sidecar never receives a
 * password and never returns one; only the encrypted cookie jar ({@code sessionState}) is kept.
 */
@Component
public class BnpAdapter {

    private static final Logger log = LoggerFactory.getLogger(BnpAdapter.class);

    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(180);
    static final Duration INITIATE_TIMEOUT = Duration.ofSeconds(120);
    static final Duration KEYPAD_TIMEOUT = Duration.ofSeconds(90);
    static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(200);

    private final WebClient client;
    private final SidecarClient sidecar;
    private final SidecarClient authSidecar;
    private final Duration accountsTimeout;
    private final Duration initiateTimeout;
    private final Duration keypadTimeout;
    private final Duration completeTimeout;

    @Autowired
    public BnpAdapter(SidecarWebClientFactory clients, ObjectMapper objectMapper) {
        this(
            clients.create(SidecarBank.BNP.label(), SidecarBank.BNP.serviceName(),
                builder -> builder
                    .clientConnector(new ReactorClientHttpConnector(httpClient()))
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))),
            objectMapper,
            DEFAULT_ACCOUNTS_TIMEOUT,
            INITIATE_TIMEOUT,
            KEYPAD_TIMEOUT,
            COMPLETE_TIMEOUT
        );
    }

    /** A login step is never replayed: a retried keypad would burn a second attempt at the bank. */
    static HttpClient httpClient() {
        return HttpClient.create().disableRetry(true);
    }

    BnpAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_ACCOUNTS_TIMEOUT, INITIATE_TIMEOUT, KEYPAD_TIMEOUT, COMPLETE_TIMEOUT);
    }

    BnpAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration keypadTimeout,
        Duration completeTimeout
    ) {
        this.client = client;
        this.sidecar = new SidecarClient(client, objectMapper, SidecarBank.BNP, SidecarErrorCode.SESSION_EXPIRED);
        this.authSidecar = new SidecarClient(client, objectMapper, SidecarBank.BNP, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.accountsTimeout = accountsTimeout;
        this.initiateTimeout = initiateTimeout;
        this.keypadTimeout = keypadTimeout;
        this.completeTimeout = completeTimeout;
    }

    public InitiateResult initiateAuth(String customerId, String brand) {
        return authPost(
            "/initiate",
            new InitiateBody(customerId, brand),
            InitiateResult.class,
            initiateTimeout,
            "Could not start the BNP Paribas login",
            SidecarErrorCode.INVALID_CREDENTIALS,
            SidecarErrorCode.UPSTREAM_UNAVAILABLE
        );
    }

    /** Sends the clicked tiles; the answer says whether a code is still expected. */
    public KeypadAck submitKeypad(String processId, List<Integer> positions) {
        KeypadAck ack = authPost(
            "/keypad",
            new KeypadBody(processId, positions),
            KeypadAck.class,
            keypadTimeout,
            "Could not send the BNP Paribas keypad choice",
            SidecarErrorCode.INVALID_CREDENTIALS,
            SidecarErrorCode.APP_VALIDATION_TIMEOUT
        );
        if (!KeypadAck.APPROVAL_PENDING.equals(ack.status()) && !KeypadAck.CODE_REQUIRED.equals(ack.status())) {
            throw authSidecar.coded(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED,
                SidecarBank.BNP.message(SidecarErrorCode.UPSTREAM_FORMAT_CHANGED), null);
        }
        return ack;
    }

    public String completeAuth(String processId, String code) {
        SessionBody body = authPost(
            "/complete",
            new CompleteBody(processId, code),
            SessionBody.class,
            completeTimeout,
            "Could not finish the BNP Paribas login",
            code == null ? SidecarErrorCode.INVALID_CREDENTIALS : SidecarErrorCode.INVALID_OTP,
            SidecarErrorCode.APP_VALIDATION_TIMEOUT
        );
        return body.sessionState();
    }

    public AccountsSnapshot fetchAccounts(String sessionState) {
        return sidecar.post(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountsSnapshot.class,
            accountsTimeout,
            "Could not fetch BNP Paribas accounts",
            SidecarErrorCode.SESSION_EXPIRED
        );
    }

    private <T> T authPost(
        String path,
        Object body,
        Class<T> type,
        Duration timeout,
        String message,
        SidecarErrorCode authenticationFailure,
        SidecarErrorCode timeoutCode
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
                    throw authSidecar.coded(timeoutCode, SidecarBank.BNP.message(timeoutCode), null);
                }
                // The sidecar answers 409 for a changed page (KEYPAD_CHANGED) but also for a bank
                // that wants an action on its site (ACTION_REQUIRED) or an unsupported second
                // factor (MFA_TYPE_UNSUPPORTED): a declared detail wins, the status is the fallback.
                // 408 and 429 only ever carry the one code this mapping already gives them.
                if (status == 409 && authSidecar.responseCode(response) == null) {
                    throw authSidecar.coded(SidecarErrorCode.KEYPAD_CHANGED,
                        SidecarBank.BNP.message(SidecarErrorCode.KEYPAD_CHANGED), null);
                }
                if (status == 429) {
                    throw authSidecar.coded(SidecarErrorCode.RATE_LIMITED,
                        SidecarBank.BNP.message(SidecarErrorCode.RATE_LIMITED), null);
                }
            }
            if (isDecodingFailure(ex)) {
                log.warn("{}: sidecar returned an undecodable response", message);
                throw authSidecar.coded(SidecarErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            throw authSidecar.mapError(message, ex, authenticationFailure);
        }
    }

    private static boolean isDecodingFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DecodingException || current instanceof JacksonException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record InitiateBody(String customerId, String brand) {
        @Override
        public String toString() {
            return "InitiateBody[customerId=***, brand=" + brand + "]";
        }
    }

    private record KeypadBody(String processId, List<Integer> positions) {
        @Override
        public String toString() {
            return "KeypadBody[processId=" + processId + ", positions=***]";
        }
    }

    private record CompleteBody(String processId, String code) {
        @Override
        public String toString() {
            return "CompleteBody[processId=" + processId + ", code=***]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SessionBody(String sessionState) {
        @Override
        public String toString() {
            return "SessionBody[sessionState=***]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitiateResult(String processId, Keypad keypad, int expiresInSeconds) {}

    /**
     * The ten shuffled keys of the bank's keypad: the digit written on each, in DOM order (left to
     * right, top to bottom). The member clicks their own digits; only the clicked positions return.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keypad(List<String> keys, int columns) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeypadAck(String processId, String status) {
        public static final String APPROVAL_PENDING = "APPROVAL_PENDING";
        public static final String CODE_REQUIRED = "CODE_REQUIRED";
    }

    public record Unsupported(String externalId, String familyCode) {}

    public record Position(
        String isin,
        String label,
        BigDecimal quantity,
        BigDecimal value,
        BigDecimal pnl
    ) {}

    /**
     * One product. {@code kind} is the sidecar's own vocabulary (CURRENT_ACCOUNT, LIVRET_A, LDDS,
     * LEP, LIVRET_JEUNE, PEL, CEL, SAVINGS, PEA, COMPTE_TITRES, ASSURANCE_VIE, PER, LOAN).
     * {@code balance} is the total valuation; for a securities account {@code cashBalance} is the
     * cash leg and {@code positions} the lines. A loan's balance is negative, as the bank shows it.
     */
    public record AccountData(
        String externalId,
        String kind,
        String name,
        BigDecimal balance,
        BigDecimal cashBalance,
        String currency,
        String iban,
        List<SidecarTransaction> transactions,
        List<Position> positions,
        boolean snapshotComplete
    ) {
        public AccountData {
            transactions = transactions == null ? List.of() : List.copyOf(transactions);
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountsSnapshot(String brand, List<AccountData> accounts, List<Unsupported> unsupported, String sessionState) {
        public AccountsSnapshot {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }
    }
}
