package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.LbpErrorCode;
import com.picsou.port.LbpPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sidecar client. The three login calls are deliberately plain single-shot requests: there is
 * no retry filter on the {@link WebClient}, none is added here, and a failure is translated,
 * never replayed, because a wrong password consumes a bank attempt and can lock the account.
 */
@Component
public class LbpAdapter implements LbpPort {
    private static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofSeconds(45);
    /** One session replay, then the account and transaction reads. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(120);

    static final Duration INITIATE_TIMEOUT = Duration.ofSeconds(90);
    /** The sidecar gives /keypad 60 s (clicks, Valider, the mobile-approval page): stay above it. */
    static final Duration KEYPAD_TIMEOUT = Duration.ofSeconds(70);
    /** The sidecar waits up to 150 s for the human to approve on the phone: stay above it. */
    static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(170);

    private final WebClient client;
    private final SidecarErrorTranslator<LbpErrorCode> sidecar;
    /** Same mapping, except a 410 means the pending login is gone, not that a stored session died. */
    private final SidecarErrorTranslator<LbpErrorCode> authSidecar;
    private final Duration checkTimeout;
    private final Duration accountsTimeout;
    private final Duration initiateTimeout;
    private final Duration keypadTimeout;
    private final Duration completeTimeout;

    @Autowired
    public LbpAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.lbp-auth.url:http://lbp-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            // A real account with a long history may exceed Spring's 256 KB default buffer.
            clients.create("La Banque Postale", url,
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
        this.sidecar = translator(client, objectMapper, LbpErrorCode.SESSION_EXPIRED);
        this.authSidecar = translator(client, objectMapper, LbpErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.checkTimeout = checkTimeout;
        this.accountsTimeout = accountsTimeout;
        this.initiateTimeout = initiateTimeout;
        this.keypadTimeout = keypadTimeout;
        this.completeTimeout = completeTimeout;
    }

    private static SidecarErrorTranslator<LbpErrorCode> translator(
        WebClient client, ObjectMapper objectMapper, LbpErrorCode gone
    ) {
        return new SidecarErrorTranslator<>(
            client,
            objectMapper,
            LbpErrorCode.class,
            "La Banque Postale",
            LbpErrorCode.UPSTREAM_UNAVAILABLE,
            gone,
            LbpAdapter::friendlyMessage
        );
    }

    @Override
    public InitiateResult initiateAuth(String customerId) {
        return authPost(
            "/initiate",
            new InitiateBody(customerId),
            InitiateResult.class,
            initiateTimeout,
            "Could not start the La Banque Postale login",
            LbpErrorCode.INVALID_CREDENTIALS,
            LbpErrorCode.APP_VALIDATION_TIMEOUT,
            null
        );
    }

    @Override
    public void submitKeypad(String processId, List<Integer> positions) {
        KeypadAck ack = authPost(
            "/keypad",
            new KeypadBody(processId, positions),
            KeypadAck.class,
            keypadTimeout,
            "Could not send the La Banque Postale keypad choice",
            LbpErrorCode.INVALID_CREDENTIALS,
            LbpErrorCode.KEYPAD_EXPIRED,
            LbpErrorCode.INVALID_POSITIONS
        );
        if (!"APPROVAL_PENDING".equals(ack.status())) {
            throw authSidecar.coded(LbpErrorCode.UPSTREAM_FORMAT_CHANGED,
                friendlyMessage(LbpErrorCode.UPSTREAM_FORMAT_CHANGED), null);
        }
    }

    @Override
    public String completeAuth(String processId) {
        SessionBody body = authPost(
            "/complete",
            new CompleteBody(processId),
            SessionBody.class,
            completeTimeout,
            "Could not finish the La Banque Postale login",
            LbpErrorCode.INVALID_CREDENTIALS,
            LbpErrorCode.APP_VALIDATION_TIMEOUT,
            null
        );
        return body.sessionState();
    }

    /**
     * One request, no retry. Statuses that only the login contract defines (408, 409, 422, 429)
     * are resolved here; everything else goes through the shared translator. No request body,
     * header or response body is ever logged or put in a message.
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
        LbpErrorCode authenticationFailure,
        LbpErrorCode timeoutCode,
        LbpErrorCode unprocessableCode
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw authSidecar.coded(LbpErrorCode.UPSTREAM_UNAVAILABLE, message, null);
            }
            return response;
        } catch (RuntimeException ex) {
            if (ex instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                if (status == 408) {
                    throw authSidecar.coded(timeoutCode, friendlyMessage(timeoutCode), null);
                }
                if (status == 422 && unprocessableCode != null) {
                    throw authSidecar.coded(unprocessableCode, friendlyMessage(unprocessableCode), null);
                }
                if (status == 409) {
                    throw authSidecar.coded(LbpErrorCode.KEYPAD_CHANGED,
                        friendlyMessage(LbpErrorCode.KEYPAD_CHANGED), null);
                }
                if (status == 429) {
                    throw authSidecar.coded(LbpErrorCode.UPSTREAM_UNAVAILABLE,
                        "Too many La Banque Postale logins are pending. Please wait a few minutes.", null);
                }
            }
            throw authSidecar.mapError(message, ex, authenticationFailure);
        }
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

    @Override
    public CheckResult checkSession(String sessionState) {
        return sidecar.post(
            "/token-check",
            Map.of("sessionState", sessionState),
            CheckResult.class,
            checkTimeout,
            "Could not check the La Banque Postale session",
            LbpErrorCode.SESSION_EXPIRED
        );
    }

    @Override
    public AccountsSnapshot fetchAccounts(String sessionState) {
        return sidecar.post(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountsSnapshot.class,
            accountsTimeout,
            "Could not fetch La Banque Postale accounts",
            LbpErrorCode.SESSION_EXPIRED
        );
    }

    private static String friendlyMessage(LbpErrorCode code) {
        return switch (code) {
            case SESSION_EXPIRED -> "The La Banque Postale session expired";
            case INVALID_SESSION_STATE -> "The stored La Banque Postale session is unusable";
            case INVALID_CREDENTIALS -> "La Banque Postale refused the identifier or password";
            case KEYPAD_EXPIRED -> "The La Banque Postale keypad expired. Start again.";
            case INVALID_POSITIONS -> "The keypad choice is not valid. Start again.";
            case KEYPAD_CHANGED -> "La Banque Postale changed its login page. Nothing was sent.";
            case APP_VALIDATION_TIMEOUT -> "The sign-in was not approved in the mobile app in time";
            case AUTH_ATTEMPT_EXPIRED -> "This La Banque Postale sign-in attempt expired. Start again.";
            case UPSTREAM_FORMAT_CHANGED -> "The La Banque Postale data format changed";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "La Banque Postale is temporarily unavailable";
        };
    }
}
