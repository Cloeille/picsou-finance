package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarErrorTranslator;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.netty.http.client.HttpClient;

/**
 * Sidecar client. The three login calls are deliberately plain single-shot requests: there is no
 * retry filter on the {@link WebClient}, none is added here, and a failure is translated, never
 * replayed, because a wrong password consumes a bank attempt and can lock the account.
 */
@Component
public class CaisseEpargneAdapter implements CaisseEpargnePort {
    private static final Duration DEFAULT_CHECK_TIMEOUT = Duration.ofSeconds(45);
    /** One token replay, one synthesis call, then up to 20 transaction pages per account. */
    private static final Duration DEFAULT_ACCOUNTS_TIMEOUT = Duration.ofSeconds(120);

    /** The sidecar waits up to 150 s for the human to approve the push: stay above it. */
    static final Duration INITIATE_TIMEOUT = Duration.ofSeconds(90);
    static final Duration COMPLETE_TIMEOUT = Duration.ofSeconds(170);
    /** The sidecar gives /keypad 60 s (clicks, Valider, Sécur'Pass page): stay above it, below nginx's 90 s. */
    static final Duration KEYPAD_TIMEOUT = Duration.ofSeconds(70);

    private final WebClient client;
    private final SidecarErrorTranslator<CaisseEpargneErrorCode> sidecar;
    /** Same mapping, except a 410 means the pending login is gone, not that a stored session died. */
    private final SidecarErrorTranslator<CaisseEpargneErrorCode> authSidecar;
    private final Duration checkTimeout;
    private final Duration accountsTimeout;
    private final Duration initiateTimeout;
    private final Duration completeTimeout;
    private final Duration keypadTimeout;

    @Autowired
    public CaisseEpargneAdapter(
        SidecarWebClientFactory clients,
        @Value("${app.caisse-epargne-auth.url:http://caisse-epargne-auth:8001}") String url,
        ObjectMapper objectMapper
    ) {
        this(
            // A real account with a long history exceeds Spring's 256 KB default buffer.
            clients.create("Caisse d'Epargne", url,
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
     * request went out. For {@code /auth/initiate} that would be a second password attempt the
     * user never made, so the replay is switched off: one call, one attempt.
     */
    static HttpClient httpClient() {
        return HttpClient.create().disableRetry(true);
    }

    CaisseEpargneAdapter(WebClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_CHECK_TIMEOUT, DEFAULT_ACCOUNTS_TIMEOUT);
    }

    CaisseEpargneAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout
    ) {
        this(client, objectMapper, checkTimeout, accountsTimeout, INITIATE_TIMEOUT, COMPLETE_TIMEOUT);
    }

    CaisseEpargneAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout
    ) {
        this(client, objectMapper, checkTimeout, accountsTimeout, initiateTimeout, completeTimeout, KEYPAD_TIMEOUT);
    }

    CaisseEpargneAdapter(
        WebClient client,
        ObjectMapper objectMapper,
        Duration checkTimeout,
        Duration accountsTimeout,
        Duration initiateTimeout,
        Duration completeTimeout,
        Duration keypadTimeout
    ) {
        this.client = client;
        this.sidecar = translator(client, objectMapper, CaisseEpargneErrorCode.SESSION_EXPIRED);
        this.authSidecar = translator(client, objectMapper, CaisseEpargneErrorCode.AUTH_ATTEMPT_EXPIRED);
        this.checkTimeout = checkTimeout;
        this.accountsTimeout = accountsTimeout;
        this.initiateTimeout = initiateTimeout;
        this.completeTimeout = completeTimeout;
        this.keypadTimeout = keypadTimeout;
    }

    private static SidecarErrorTranslator<CaisseEpargneErrorCode> translator(
        WebClient client, ObjectMapper objectMapper, CaisseEpargneErrorCode gone
    ) {
        return new SidecarErrorTranslator<>(
            client,
            objectMapper,
            CaisseEpargneErrorCode.class,
            "Caisse d'Epargne",
            CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE,
            gone,
            CaisseEpargneAdapter::friendlyMessage
        );
    }

    @Override
    public InitiateResult initiateAuth(String customerId) {
        return authPost(
            "/initiate",
            new InitiateBody(customerId),
            InitiateResult.class,
            initiateTimeout,
            "Could not start the Caisse d'Epargne login",
            CaisseEpargneErrorCode.INVALID_CREDENTIALS,
            CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT,
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
            "Could not send the Caisse d'Epargne keypad choice",
            CaisseEpargneErrorCode.INVALID_CREDENTIALS,
            CaisseEpargneErrorCode.KEYPAD_EXPIRED,
            CaisseEpargneErrorCode.INVALID_POSITIONS
        );
        if (!"SECURPASS_PENDING".equals(ack.status())) {
            throw authSidecar.coded(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED,
                friendlyMessage(CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED), null);
        }
    }

    @Override
    public String completeAuth(String processId) {
        SessionBody body = authPost(
            "/complete",
            new CompleteBody(processId),
            SessionBody.class,
            completeTimeout,
            "Could not finish the Caisse d'Epargne login",
            CaisseEpargneErrorCode.INVALID_CREDENTIALS,
            CaisseEpargneErrorCode.APP_VALIDATION_TIMEOUT,
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
        CaisseEpargneErrorCode authenticationFailure,
        CaisseEpargneErrorCode timeoutCode,
        CaisseEpargneErrorCode unprocessableCode
    ) {
        try {
            T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).retrieve().bodyToMono(type)
                .timeout(timeout).block();
            if (response == null) {
                throw authSidecar.coded(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE, message, null);
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
                    throw authSidecar.coded(CaisseEpargneErrorCode.KEYPAD_CHANGED,
                        friendlyMessage(CaisseEpargneErrorCode.KEYPAD_CHANGED), null);
                }
                if (status == 429) {
                    throw authSidecar.coded(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE,
                        "Too many Caisse d'Epargne logins are pending. Please wait a few minutes.", null);
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
            "Could not check the Caisse d'Epargne session",
            CaisseEpargneErrorCode.SESSION_EXPIRED
        );
    }

    @Override
    public AccountsSnapshot fetchAccounts(String sessionState) {
        return sidecar.post(
            "/accounts",
            Map.of("sessionState", sessionState),
            AccountsSnapshot.class,
            accountsTimeout,
            "Could not fetch Caisse d'Epargne accounts",
            CaisseEpargneErrorCode.SESSION_EXPIRED
        );
    }

    private static String friendlyMessage(CaisseEpargneErrorCode code) {
        return switch (code) {
            case SESSION_EXPIRED -> "The Caisse d'Epargne session expired";
            case INVALID_SESSION_STATE -> "The stored Caisse d'Epargne session is unusable";
            case INVALID_CREDENTIALS -> "Caisse d'Epargne refused the identifier or password";
            case KEYPAD_EXPIRED -> "The Caisse d'Epargne keypad expired. Start again.";
            case INVALID_POSITIONS -> "The keypad choice is not valid. Start again.";
            case KEYPAD_CHANGED -> "Caisse d'Epargne changed its login page. Nothing was sent.";
            case APP_VALIDATION_TIMEOUT -> "The sign-in was not approved in Sécur'Pass in time";
            case AUTH_ATTEMPT_EXPIRED -> "This Caisse d'Epargne sign-in attempt expired. Start again.";
            case UPSTREAM_FORMAT_CHANGED -> "The Caisse d'Epargne data format changed";
            case UPSTREAM_UNAVAILABLE, INTERNAL_ERROR -> "Caisse d'Epargne is temporarily unavailable";
        };
    }
}
