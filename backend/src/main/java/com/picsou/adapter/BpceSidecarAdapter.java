package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.BpceErrorCode;
import com.picsou.port.BpcePort;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The client half every BPCE sidecar shares (Caisse d'Epargne, Banque Populaire): same routes,
 * same statuses, same JSON. Each bank's adapter only adds its {@code /initiate} body.
 *
 * <p>The three login calls are deliberately plain single-shot requests: there is no retry filter
 * on the {@link WebClient}, none is added here, and a failure is translated, never replayed,
 * because a wrong password consumes a bank attempt and can lock the account.
 */
abstract class BpceSidecarAdapter implements BpcePort {
    static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofSeconds(45);
    /** One token replay, one synthesis call, then up to 20 transaction pages per account. */
    static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(120);

    /** The sidecar waits up to 150 s for the human to approve the push: stay above it. */
    static final Duration INITIATE_TIMEOUT = Duration.ofSeconds(90);
    static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(170);
    /** The sidecar gives /keypad 60 s (clicks, Valider, Sécur'Pass page): stay above it, below nginx's 90 s. */
    static final Duration KEYPAD_TIMEOUT = Duration.ofSeconds(70);

    private final String label;
    private final WebClient client;
    private final SidecarErrorTranslator<BpceErrorCode> sidecar;
    /** Same mapping, except a 410 means the pending login is gone, not that a stored session died. */
    private final SidecarErrorTranslator<BpceErrorCode> authSidecar;
    private final Duration checkTimeout;
    private final Duration accountsTimeout;
    private final Duration initiateTimeout;
    private final Duration completeTimeout;
    private final Duration keypadTimeout;

    BpceSidecarAdapter(
        String label,
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout,
        Duration keypadTimeout
    ) {
        this.label = label;
        this.client = client;
        this.sidecar = translator(client, objectMapper, BpceErrorCode.SESSION_EXPIRED);
        this.authSidecar = translator(client, objectMapper, BpceErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.checkTimeout = checkTimeout;
        this.accountsTimeout = accountsTimeout;
        this.initiateTimeout = initiateTimeout;
        this.completeTimeout = completeTimeout;
        this.keypadTimeout = keypadTimeout;
    }

    /**
     * The production client: a real account with a long history exceeds Spring's 256 KB default
     * buffer, and the connection never replays a request (see {@link #httpClient()}).
     */
    static WebClient client(SidecarWebClientFactory clients, String label, String url) {
        return clients.create(label, url,
            builder -> builder
                .clientConnector(new ReactorClientHttpConnector(httpClient()))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)));
    }

    /**
     * reactor-netty silently replays a request once when a pooled connection is reset before the
     * request went out. For {@code /auth/initiate} that would be a second password attempt the
     * user never made, so the replay is switched off: one call, one attempt.
     */
    static HttpClient httpClient() {
        return HttpClient.create().disableRetry(true);
    }

    private SidecarErrorTranslator<BpceErrorCode> translator(
        WebClient client, ObjectMapper objectMapper, BpceErrorCode gone
    ) {
        return new SidecarErrorTranslator<>(
            client,
            objectMapper,
            BpceErrorCode.class,
            label,
            BpceErrorCode.UPSTREAM_UNAVAILABLE,
            gone,
            this::friendlyMessage
        );
    }

    /** {@code POST /initiate} with the bank's own body. One attempt, no retry. */
    protected InitiateResult initiate(Object body) {
        return authPost(
            "/initiate",
            body,
            InitiateResult.class,
            initiateTimeout,
            "Could not start the " + label + " login",
            BpceErrorCode.INVALID_CREDENTIALS,
            BpceErrorCode.APP_VALIDATION_TIMEOUT,
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
            "Could not send the " + label + " keypad choice",
            BpceErrorCode.INVALID_CREDENTIALS,
            BpceErrorCode.KEYPAD_EXPIRED,
            BpceErrorCode.INVALID_POSITIONS
        );
        if (!"SECURPASS_PENDING".equals(ack.status())) {
            throw authSidecar.coded(BpceErrorCode.UPSTREAM_FORMAT_CHANGED,
                friendlyMessage(BpceErrorCode.UPSTREAM_FORMAT_CHANGED), null);
        }
    }

    @Override
    public String completeAuth(String processId) {
        SessionBody body = authPost(
            "/complete",
            new CompleteBody(processId),
            SessionBody.class,
            completeTimeout,
            "Could not finish the " + label + " login",
            BpceErrorCode.INVALID_CREDENTIALS,
            BpceErrorCode.APP_VALIDATION_TIMEOUT,
            null
        );
        return body.sessionState();
    }

    /**
     * One request, no retry. Statuses that only the login contract defines (408, 409, 422, 429) are
     * resolved here; everything else goes through the shared translator. No request body, header
     * or response body is ever logged or put in a message.
     *
     * @param timeoutCode       what a 408 means on this call (Sécur'Pass not approved, keypad expired)
     * @param unprocessableCode what a 422 means on this call, or null when it carries no such meaning
     */
    private <T> T authPost(
        String path,
        Object body,
        Class<T> type,
        Duration timeout,
        String message,
        BpceErrorCode authenticationFailure,
        BpceErrorCode timeoutCode,
        BpceErrorCode unprocessableCode
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw authSidecar.coded(BpceErrorCode.UPSTREAM_UNAVAILABLE, message, null);
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
                    throw authSidecar.coded(BpceErrorCode.KEYPAD_CHANGED,
                        friendlyMessage(BpceErrorCode.KEYPAD_CHANGED), null);
                }
                if (status == 429) {
                    throw authSidecar.coded(BpceErrorCode.UPSTREAM_UNAVAILABLE,
                        "Too many " + label + " logins are pending. Please wait a few minutes.", null);
                }
            }
            throw authSidecar.mapError(message, ex, authenticationFailure);
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
            "Could not check the " + label + " session",
            BpceErrorCode.SESSION_EXPIRED
        );
    }

    @Override
    public AccountsSnapshot fetchAccounts(String sessionState) {
        return sidecar.post(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountsSnapshot.class,
            accountsTimeout,
            "Could not fetch " + label + " accounts",
            BpceErrorCode.SESSION_EXPIRED
        );
    }

    private String friendlyMessage(BpceErrorCode code) {
        return switch (code) {
            case SESSION_EXPIRED -> "The " + label + " session expired";
            case INVALID_SESSION_STATE -> "The stored " + label + " session is unusable";
            case INVALID_CREDENTIALS -> label + " refused the identifier or password";
            case KEYPAD_EXPIRED -> "The " + label + " keypad expired. Start again.";
            case INVALID_POSITIONS -> "The keypad choice is not valid. Start again.";
            case KEYPAD_CHANGED -> label + " changed its login page. Nothing was sent.";
            case APP_VALIDATION_TIMEOUT -> "The sign-in was not approved in Sécur'Pass in time";
            case AUTH_ATTEMPT_EXPIRED -> "This " + label + " sign-in attempt expired. Start again.";
            case UPSTREAM_FORMAT_CHANGED -> "The " + label + " data format changed";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> label + " is temporarily unavailable";
        };
    }
}
