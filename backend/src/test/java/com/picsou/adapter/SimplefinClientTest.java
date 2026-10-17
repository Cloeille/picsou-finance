package com.picsou.adapter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The client against a fake transport. The allowlist matrix lives in {@link SimplefinUrlsTest};
 * here one case per path proves the client calls the validation and refuses before any request.
 */
class SimplefinClientTest {

    private static final String BRIDGE = SimplefinUrls.BRIDGE_HOST;
    private static final String CLAIM = "https://" + BRIDGE + "/simplefin/claim/abc";
    private static final String USERNAME = "usr98765";
    private static final String PASSWORD = "s3cr3tPassw0rd";
    private static final String ACCESS = "https://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    // Limits of SimplefinClient, which keeps them private.
    private static final int CLAIM_BODY_CAP = 8_192;
    private static final int ACCOUNTS_BODY_CAP = 2_000_000;

    private static final String ACCOUNT_SET = """
        {
          "errlist": [{"code": "act.failed", "msg": "One account lagged."}],
          "connections": [{"conn_id": "CON-1", "name": "Chase Bank Chase Tom", "org_name": "Chase"}],
          "accounts": [
            {
              "id": "chk", "name": "Checking", "conn_id": "CON-1", "currency": "USD", "balance": "10.00",
              "transactions": [
                {"id": "tx-1", "posted": 1767225600, "amount": "-4.50", "description": "Coffee"},
                {"id": "tx-pending", "posted": 1767225600, "amount": "-1.00", "description": "Hold", "pending": true}
              ]
            }
          ]
        }
        """;

    private final RecordingTransport transport = new RecordingTransport();
    private final SimplefinClient client = new SimplefinClient(transport);

    @Test
    void claim_validToken_postsTheDecodedUrlAndReturnsTheAccessUrl() {
        transport.next = new SimplefinTransport.Response(200, ACCESS + "\n");

        String access = client.claim(token(CLAIM));

        assertThat(access).isEqualTo(ACCESS);
        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.method).isEqualTo("POST");
        assertThat(transport.uri).isEqualTo(URI.create(CLAIM));
        assertThat(transport.authorization).isNull();
    }

    @Test
    void claim_foreignHost_sendsNothing() {
        assertThatThrownBy(() -> client.claim(token("https://evil.example/simplefin/claim/abc")))
            .isInstanceOf(SyncException.class);

        assertThat(transport.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {ACCESS, ACCESS + "\r\n", "\"" + ACCESS + "\"", "  \"" + ACCESS + "\"  "})
    void claim_answerOnTheBridge_isReturnedUnwrapped(String body) {
        transport.next = new SimplefinTransport.Response(200, body);

        assertThat(client.claim(token(CLAIM))).isEqualTo(ACCESS);
    }

    @Test
    void claim_answerOnAnotherHost_isRefusedAfterExactlyOneRequest() {
        transport.next = new SimplefinTransport.Response(200, "https://user:pass@10.0.0.5:8443/simplefin\n");

        assertThatThrownBy(() -> client.claim(token(CLAIM)))
            .isInstanceOf(SyncException.class).hasMessageContaining(BRIDGE);

        assertThat(transport.calls).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ", "\"\"", "\"", "null", "{\"error\":\"nope\"}", "<html>login</html>",
        "https://" + BRIDGE + "/simplefin",
        "https://" + USERNAME + "@" + BRIDGE + "/simplefin",
        "http://" + USERNAME + ":" + PASSWORD + "@" + BRIDGE + "/simplefin",
    })
    void claim_unusableAnswer_isRefusedWithoutEchoingTheCredentials(String body) {
        transport.next = new SimplefinTransport.Response(200, body);

        assertThatThrownBy(() -> client.claim(token(CLAIM)))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(SimplefinUrlsTest.everythingPrintable(ex)).doesNotContain(PASSWORD));
    }

    @Test
    void claim_status403_saysTheTokenWasAlreadyUsed() {
        transport.next = new SimplefinTransport.Response(403, "");

        assertThatThrownBy(() -> client.claim(token(CLAIM)))
            .isInstanceOf(SyncException.class).hasMessageContaining("already been used");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 307, 308})
    void claim_redirect_isRefusedNotFollowed(int status) {
        transport.next = new SimplefinTransport.Response(status, "");

        assertThatThrownBy(() -> client.claim(token(CLAIM)))
            .isInstanceOf(SyncException.class).hasMessageContaining("redirected");
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    void claim_bodyOverTheCap_isRefused() {
        transport.next = new SimplefinTransport.Response(200, "a".repeat(CLAIM_BODY_CAP + 1));

        assertThatThrownBy(() -> client.claim(token(CLAIM)))
            .isInstanceOf(SyncException.class).hasMessageContaining("too large");
    }

    @Test
    void fetchAccounts_validAccess_sendsCredentialsOnlyInTheAuthorizationHeader() {
        transport.next = new SimplefinTransport.Response(200, ACCOUNT_SET);

        SimplefinAccountSet set = client.fetchAccounts(ACCESS, START);

        assertThat(transport.method).isEqualTo("GET");
        assertThat(transport.uri.getPath()).isEqualTo("/simplefin/accounts");
        assertThat(transport.uri.getQuery()).contains("version=2").contains("start-date=1767225600");
        assertThat(transport.uri.toString()).doesNotContain(USERNAME).doesNotContain(PASSWORD).doesNotContain("@");
        assertThat(basicCredentials(transport.authorization)).isEqualTo(USERNAME + ":" + PASSWORD);
        assertThat(set.errors()).containsExactly("One account lagged.");
        assertThat(set.accounts()).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("sfin_CON-1_chk");
            assertThat(account.connectionName()).isEqualTo("Chase");
            assertThat(account.transactions()).singleElement()
                .satisfies(tx -> assertThat(tx.externalId()).isEqualTo("tx-1"));
        });
    }

    @Test
    void fetchAccounts_foreignHost_sendsNothingAndKeepsTheCredentialsOutOfTheError() {
        String foreign = "https://" + USERNAME + ":" + PASSWORD + "@evil.example/simplefin";

        assertThatThrownBy(() -> client.fetchAccounts(foreign, START))
            .isInstanceOf(SyncException.class)
            .satisfies(ex -> assertThat(SimplefinUrlsTest.everythingPrintable(ex))
                .doesNotContain(PASSWORD).doesNotContain(USERNAME));
        assertThat(transport.calls).isZero();
    }

    @Test
    void fetchAccounts_status403_throwsSessionExpired() {
        transport.next = new SimplefinTransport.Response(403, "");

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START))
            .isInstanceOf(SyncException.class)
            .hasMessage("SimpleFIN refused the stored access. It may have been revoked. "
                + "Disconnect and connect with a new setup token.")
            .satisfies(ex -> assertThat(((SyncException) ex).getCode()).isEqualTo("SESSION_EXPIRED"));
    }

    @Test
    void fetchAccounts_status402_asksForPayment() {
        transport.next = new SimplefinTransport.Response(402, "");

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START))
            .isInstanceOf(SyncException.class)
            .hasMessage("SimpleFIN requires payment before it will return accounts.");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429, 500, 503})
    void fetchAccounts_otherErrorStatus_reportsOnlyTheStatus(int status) {
        transport.next = new SimplefinTransport.Response(status, "body " + ACCESS);

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START))
            .isInstanceOf(SyncException.class)
            .hasMessage("SimpleFIN could not complete the request (HTTP " + status + ").");
    }

    @Test
    void fetchAccounts_redirect_isRefusedNotFollowed() {
        transport.next = new SimplefinTransport.Response(302, "");

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START))
            .isInstanceOf(SyncException.class).hasMessageContaining("redirected");
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    void fetchAccounts_bodyOverTheCap_isRefused() {
        transport.next = new SimplefinTransport.Response(200, "a".repeat(ACCOUNTS_BODY_CAP + 1));

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START))
            .isInstanceOf(SyncException.class).hasMessageContaining("too large");
    }

    @Test
    void fetchAccounts_errorsAndNoAccounts_failsWithTheBridgeMessagesOnly() {
        transport.next = new SimplefinTransport.Response(200, """
            {"errlist": [{"code":"gen.auth","msg":"Reauthenticate at the bank."}, "A plain string.", {"code":"x"}],
             "errors": ["Legacy error."], "accounts": []}
            """);

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START))
            .isInstanceOf(SyncException.class)
            .hasMessage("Reauthenticate at the bank. A plain string. Legacy error.");
    }

    @Test
    void fetchAccounts_serverError_logsNeitherTheBodyNorTheCredentials() {
        Logger logger = (Logger) LoggerFactory.getLogger(SimplefinClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            transport.next = new SimplefinTransport.Response(500, "boom " + ACCESS + " Basic dXNyOTg3NjU6czNjcjN0");

            assertThatThrownBy(() -> client.fetchAccounts(ACCESS, START)).isInstanceOf(SyncException.class);

            assertThat(logs.list).isNotEmpty();
            for (ILoggingEvent event : logs.list) {
                assertThat(allText(event)).doesNotContain(PASSWORD).doesNotContain(USERNAME).doesNotContain("Basic ");
            }
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void boundedUtf8_bodyWithinTheCap_isReturnedAsUtf8() throws Exception {
        BodySubscriber<String> subscriber = SimplefinClient.boundedUtf8(100).apply(responseInfo(2));
        subscriber.onSubscribe(mock(Flow.Subscription.class));

        subscriber.onNext(List.of(ByteBuffer.wrap("é".getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().get()).isEqualTo("é");
    }

    @Test
    void boundedUtf8_advertisedLengthOverTheCap_cancelsBeforeReading() {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        BodySubscriber<String> subscriber = SimplefinClient.boundedUtf8(100).apply(responseInfo(101));

        subscriber.onSubscribe(subscription);

        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
            .hasMessageContaining("response too large");
    }

    @Test
    void boundedUtf8_streamPastTheCapDespiteALyingLength_cancelsAndFails() {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        BodySubscriber<String> subscriber = SimplefinClient.boundedUtf8(100).apply(responseInfo(-1));
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[60])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[60])));

        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
            .hasMessageContaining("response too large");
    }

    private static ResponseInfo responseInfo(long contentLength) {
        Map<String, List<String>> headers = contentLength < 0
            ? Map.of() : Map.of("Content-Length", List.of(Long.toString(contentLength)));
        ResponseInfo info = mock(ResponseInfo.class);
        when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return info;
    }

    private static String allText(ILoggingEvent event) {
        String text = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) text += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
        return text;
    }

    private static String basicCredentials(String authorization) {
        return new String(Base64.getDecoder().decode(authorization.substring("Basic ".length())), StandardCharsets.UTF_8);
    }

    private static String token(String url) {
        return Base64.getEncoder().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingTransport implements SimplefinTransport {
        Response next = new Response(200, "");
        String method;
        URI uri;
        String authorization;
        int calls;

        @Override
        public Response send(String method, URI uri, String authorization, int maxBody) {
            this.calls++;
            this.method = method;
            this.uri = uri;
            this.authorization = authorization;
            return next;
        }
    }
}
