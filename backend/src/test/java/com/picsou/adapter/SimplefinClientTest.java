package com.picsou.adapter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.config.CryptoEncryption;
import com.picsou.dto.SimplefinConnectionStatusResponse;
import com.picsou.exception.SyncException;
import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
import com.picsou.service.AccountService;
import com.picsou.service.BankTransactionImportService;
import com.picsou.service.SimplefinStatusWriter;
import com.picsou.service.SimplefinSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimplefinClientTest {

    private static final String ACCESS = "https://user1234:secret@beta-bridge.simplefin.org/simplefin";

    private static final String ACCOUNT_SET = """
        {
          "errlist": [{"code": "act.failed", "msg": "One account lagged."}],
          "connections": [{"conn_id": "CON-1", "name": "Chase Bank Chase Tom", "org_name": "Chase"}],
          "accounts": [
            {
              "id": "chk",
              "name": "Checking",
              "conn_id": "CON-1",
              "currency": "USD",
              "balance": "10.00",
              "transactions": [
                {"id": "tx-1", "posted": 1767225600, "amount": "-4.50", "description": "Coffee"},
                {"id": "tx-pending", "posted": 1767225600, "amount": "-1.00", "description": "Hold", "pending": true}
              ]
            },
            {
              "id": "sav",
              "name": "Savings",
              "conn_id": "CON-1",
              "currency": "USD",
              "balance": "80.00",
              "transactions": []
            },
            {
              "id": "miles",
              "name": "Rewards",
              "conn_id": "CON-1",
              "currency": "https://example.com/miles",
              "balance": "12",
              "transactions": []
            }
          ]
        }
        """;

    private final RecordingTransport transport = new RecordingTransport();
    private final SimplefinClient client = new SimplefinClient(transport);
    private final BankTransactionImportService importService = mock(BankTransactionImportService.class);

    @Test
    void claimPostsTheDecodedUrlAndKeepsTheAccessUrl() {
        transport.next = new SimplefinTransport.Response(200, ACCESS + "\n");

        String access = client.claim(token("https://beta-bridge.simplefin.org/simplefin/claim/abc"));

        assertThat(access).isEqualTo(ACCESS);
        assertThat(transport.method).isEqualTo("POST");
        assertThat(transport.uri).isEqualTo(URI.create("https://beta-bridge.simplefin.org/simplefin/claim/abc"));
        assertThat(transport.authorization).isNull();
    }

    @Test
    void fetchSendsBasicAuthAndDropsPendingTransactions() {
        transport.next = new SimplefinTransport.Response(200, ACCOUNT_SET);

        SimplefinAccountSet set = client.fetchAccounts(ACCESS, LocalDate.of(2026, 1, 1));

        assertThat(transport.method).isEqualTo("GET");
        assertThat(transport.uri.getUserInfo()).isNull();
        assertThat(transport.uri.getPath()).isEqualTo("/simplefin/accounts");
        assertThat(transport.uri.getQuery()).contains("version=2").contains("start-date=1767225600");
        assertThat(transport.authorization).startsWith("Basic ");
        assertThat(set.errors()).containsExactly("One account lagged.");
        assertThat(set.accounts()).hasSize(3);
        assertThat(set.accounts().get(0).externalId()).isEqualTo("sfin_CON-1_chk");
        assertThat(set.accounts().get(0).connectionName()).isEqualTo("Chase");
        assertThat(set.accounts().get(0).transactions()).hasSize(1);
        assertThat(set.accounts().get(0).transactions().get(0).amount()).isEqualByComparingTo("-4.50");
        assertThat(set.accounts().get(0).transactions().get(0).date()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void errorsWithNoAccountsFailTheSync() {
        transport.next = new SimplefinTransport.Response(200, """
            {"errlist": [{"msg": "Bridge is down."}], "connections": [], "accounts": []}
            """);

        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, LocalDate.of(2026, 1, 1)))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("Bridge is down.");
    }

    @Test
    void refusedClaimAndRevokedAccessHaveDistinctMessages() {
        transport.next = new SimplefinTransport.Response(403, "");
        assertThatThrownBy(() -> client.claim(token("https://beta-bridge.simplefin.org/simplefin/claim/abc")))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("already been used");

        transport.next = new SimplefinTransport.Response(403, "");
        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, LocalDate.of(2026, 1, 1)))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("revoked");
    }

    @Test
    void paymentRequiredIsItsOwnMessage() {
        transport.next = new SimplefinTransport.Response(402, "");
        assertThatThrownBy(() -> client.fetchAccounts(ACCESS, LocalDate.of(2026, 1, 1)))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("payment");
    }

    @Test
    void anUnusablePostedDateIsDroppedAndTheRestAreKept() {
        transport.next = new SimplefinTransport.Response(200, """
            {"accounts":[{"id":"chk","name":"Checking","currency":"USD","balance":"1.00","transactions":[
              {"id":"bad","posted":9223372036854775807,"amount":"1.00","description":"Way out"},
              {"id":"ok","posted":1767225600,"amount":"-2.00","description":"Coffee"}]}]}
            """);

        SimplefinAccountSet set = client.fetchAccounts(ACCESS, LocalDate.of(2026, 1, 1));

        assertThat(set.accounts().get(0).transactions()).singleElement()
            .satisfies(tx -> assertThat(tx.externalId()).isEqualTo("ok"));
    }

    @Test
    void aLongAccountIdKeepsTheSimplefinPrefix() {
        String hashed = SimplefinJson.externalAccountId("c".repeat(200), "a".repeat(200));
        assertThat(hashed).startsWith("sfin_").hasSize("sfin_".length() + 64);
        assertThat(SimplefinJson.externalAccountId("CON-1", "chk")).isEqualTo("sfin_CON-1_chk");
    }

    @Test
    void redirectsAreRefused() {
        transport.next = new SimplefinTransport.Response(302, "");
        assertThatThrownBy(() -> client.claim(token("https://beta-bridge.simplefin.org/simplefin/claim/abc")))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("redirected");
    }

    @Test
    void aClaimThatIsNotHttpsNeverLeavesTheProcess() {
        assertRejected(token("http://beta-bridge.simplefin.org/simplefin/claim/abc"));
        assertRejected(token("https://user:pass@beta-bridge.simplefin.org/simplefin/claim/abc"));
        assertRejected("not base64!!!");
        assertThat(transport.calls).isZero();
    }

    @Test
    void aTokenForAnotherHostNeverLeavesTheProcess() {
        assertRejected(token("https://10.0.0.5:8443/x"));
        assertRejected(token("https://bridge.simplefin.org/simplefin/claim/abc"));
        assertRejected(token("https://beta-bridge.simplefin.org.evil.example/simplefin/claim/abc"));
        assertThat(transport.calls).isZero();
    }

    @Test
    void aClaimThatHandsBackAnotherHostIsNotKept() {
        transport.next = new SimplefinTransport.Response(200, "https://user:pass@10.0.0.5:8443/simplefin\n");

        assertThatThrownBy(() -> client.claim(token("https://beta-bridge.simplefin.org/simplefin/claim/abc")))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining(SimplefinUrls.BRIDGE_HOST);
        assertThatThrownBy(() -> client.fetchAccounts("https://user:pass@10.0.0.5:8443/simplefin", LocalDate.of(2026, 1, 1)))
            .isInstanceOf(SyncException.class);
        assertThat(transport.calls).isEqualTo(1);
    }

    @Test
    void anOversizedBodyIsRefusedWhileItIsRead() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/small", exchange -> {
            byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(new byte[8_000]);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> small = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/small")).build(),
                SimplefinClient.boundedUtf8(100));
            assertThat(small.body()).isEqualTo("ok");

            assertThatThrownBy(() -> http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big")).build(),
                SimplefinClient.boundedUtf8(100)))
                .hasMessageContaining("response too large");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aPlusInTheAccessPasswordIsNotTurnedIntoASpace() {
        String header = SimplefinUrls.accountsRequest(
            "https://user:p+ss%2Bword@beta-bridge.simplefin.org/simplefin",
            LocalDate.of(2026, 1, 1)).authorization();
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("user:p+ss+word");
    }

    // ---- host allowlist at the client: refused means no request at all ----------------------

    private static final String CLAIM = "https://beta-bridge.simplefin.org/simplefin/claim/abc";
    private static final String USERNAME = "usr98765";
    private static final String PASSWORD = "s3cr3tPassw0rd";
    private static final String SECRET_ACCESS = "https://" + USERNAME + ":" + PASSWORD + "@beta-bridge.simplefin.org/simplefin";

    @Test
    void noForeignClaimDestinationReachesTheTransport() {
        for (String url : SimplefinUrlsTest.FOREIGN_DESTINATIONS) {
            assertThatThrownBy(() -> client.claim(token(url))).as(url).isInstanceOf(SyncException.class);
            assertThatThrownBy(() -> client.claim(url)).as("raw " + url).isInstanceOf(SyncException.class);
        }
        assertThat(transport.calls).isZero();
    }

    @Test
    void noForeignAccessUrlReachesTheTransportWhenFetching() {
        for (String url : SimplefinUrlsTest.foreignAccessUrls()) {
            assertThatThrownBy(() -> client.fetchAccounts(url, LocalDate.of(2026, 1, 1)))
                .as(url).isInstanceOf(SyncException.class);
        }
        assertThat(transport.calls).isZero();
    }

    @Test
    void anUnusableTokenNeverReachesTheTransport() {
        assertRejected(null);
        assertRejected("");
        assertRejected(" \n\t ");
        assertRejected("%%%%");
        assertRejected(Base64.getEncoder().encodeToString("hello world".getBytes(StandardCharsets.UTF_8)));
        assertRejected("A".repeat(SimplefinUrls.MAX_TOKEN_CHARS + 1));
        assertThat(transport.calls).isZero();
    }

    @Test
    void anUppercaseHostOnTheBridgeIsClaimedOnce() {
        transport.next = new SimplefinTransport.Response(200, ACCESS);

        String access = client.claim(token("HTTPS://BETA-BRIDGE.SIMPLEFIN.ORG/simplefin/claim/abc"));

        assertThat(access).isEqualTo(ACCESS);
        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.uri.getHost()).isEqualToIgnoringCase(SimplefinUrls.BRIDGE_HOST);
    }

    @Test
    void aTokenWrappedInWhitespaceIsClaimedOnce() {
        transport.next = new SimplefinTransport.Response(200, ACCESS);

        client.claim("\n  " + token(CLAIM) + "  \r\n");

        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.uri).isEqualTo(URI.create(CLAIM));
    }

    @Test
    void theDefaultPortIsClaimedOnTheBridge() {
        transport.next = new SimplefinTransport.Response(200, ACCESS);

        client.claim(token("https://beta-bridge.simplefin.org:443/simplefin/claim/abc"));

        assertThat(transport.calls).isEqualTo(1);
        assertThat(transport.uri.getHost()).isEqualTo(SimplefinUrls.BRIDGE_HOST);
    }

    // ---- the access URL a claim returns -------------------------------------------------------

    @Test
    void aClaimAnswerPointingAnywhereElseIsRefusedAfterExactlyOneRequest() {
        for (String foreign : SimplefinUrlsTest.foreignAccessUrls()) {
            for (String body : List.of(foreign, foreign + "\n", "\"" + foreign + "\"", "  " + foreign + "  ")) {
                RecordingTransport single = new RecordingTransport();
                single.next = new SimplefinTransport.Response(200, body);

                assertThatThrownBy(() -> new SimplefinClient(single).claim(token(CLAIM)))
                    .as(body).isInstanceOf(SyncException.class);
                assertThat(single.calls).as("one claim, no follow-up request for " + body).isEqualTo(1);
                assertThat(single.method).isEqualTo("POST");
                assertThat(single.uri).isEqualTo(URI.create(CLAIM));
            }
        }
    }

    @Test
    void aClaimAnswerWithoutCredentialsOrWithGarbageIsRefused() {
        for (String body : new String[] {
            "", "   ", "\"\"", "\"", "null", "{\"error\":\"nope\"}", "<html>login</html>",
            "https://beta-bridge.simplefin.org/simplefin",
            "https://" + USERNAME + "@beta-bridge.simplefin.org/simplefin",
            "https://" + USERNAME + ":@beta-bridge.simplefin.org/simplefin",
            "http://" + USERNAME + ":" + PASSWORD + "@beta-bridge.simplefin.org/simplefin",
        }) {
            RecordingTransport single = new RecordingTransport();
            single.next = new SimplefinTransport.Response(200, body);
            assertThatThrownBy(() -> new SimplefinClient(single).claim(token(CLAIM)))
                .as(body).isInstanceOf(SyncException.class)
                .satisfies(ex -> assertThat(SimplefinUrlsTest.everythingPrintable(ex)).doesNotContain(PASSWORD));
            assertThat(single.calls).isEqualTo(1);
        }
    }

    @Test
    void aClaimAnswerOnTheBridgeIsReturnedUnchanged() {
        for (String body : List.of(SECRET_ACCESS, SECRET_ACCESS + "\r\n", "\"" + SECRET_ACCESS + "\"")) {
            transport.next = new SimplefinTransport.Response(200, body);
            assertThat(client.claim(token(CLAIM))).isEqualTo(SECRET_ACCESS);
        }
    }

    @Test
    void aClaimAnswerForAnotherHostIsNeverPersisted() {
        transport.next = new SimplefinTransport.Response(200, "https://" + USERNAME + ":" + PASSWORD + "@10.0.0.5:8443/simplefin");
        SimplefinConnectionRepository connections = mock(SimplefinConnectionRepository.class);
        CryptoEncryption encryption = mock(CryptoEncryption.class);
        SimplefinSyncService service = serviceWith(connections, encryption, knownMember(), mock(SimplefinStatusWriter.class));

        assertThatThrownBy(() -> service.connect(token(CLAIM), 7L)).isInstanceOf(SyncException.class);

        assertThat(transport.calls).isEqualTo(1);
        verify(connections, never()).save(any());
        verifyNoInteractions(encryption);
    }

    @Test
    void aForeignTokenNeverReachesThePersistenceLayerEither() {
        SimplefinConnectionRepository connections = mock(SimplefinConnectionRepository.class);
        SimplefinSyncService service = serviceWith(
            connections, mock(CryptoEncryption.class), knownMember(), mock(SimplefinStatusWriter.class));

        assertThatThrownBy(() -> service.connect(token("https://10.0.0.5/x"), 7L)).isInstanceOf(SyncException.class);

        assertThat(transport.calls).isZero();
        verifyNoInteractions(connections);
    }

    // ---- secrets in messages and logs ---------------------------------------------------------

    @Test
    void aRefusedAccessUrlNeverPutsItsCredentialsInTheMessageOrCause() {
        for (String url : SimplefinUrlsTest.foreignAccessUrls()) {
            assertThatThrownBy(() -> client.fetchAccounts(url, LocalDate.of(2026, 1, 1)))
                .satisfies(ex -> assertThat(SimplefinUrlsTest.everythingPrintable(ex))
                    .doesNotContain(PASSWORD).doesNotContain(USERNAME));
        }
    }

    @Test
    void aServerErrorDoesNotEchoTheResponseBodyOrTheCredentialsAnywhere() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SimplefinClient.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            transport.next = new SimplefinTransport.Response(500, "boom " + SECRET_ACCESS + " Basic dXNyOTg3NjU6czNjcjN0");

            assertThatThrownBy(() -> client.fetchAccounts(SECRET_ACCESS, LocalDate.of(2026, 1, 1)))
                .isInstanceOf(SyncException.class)
                .hasMessage("SimpleFIN could not complete the request (HTTP 500).");
            assertThatThrownBy(() -> client.claim(token(CLAIM)))
                .isInstanceOf(SyncException.class)
                .hasMessage("SimpleFIN could not complete the request (HTTP 500).");

            assertThat(logs.list).isNotEmpty();
            for (ILoggingEvent event : logs.list) {
                assertThat(allText(event)).doesNotContain(PASSWORD).doesNotContain(USERNAME).doesNotContain("Basic ");
            }
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void theAuthorizationHeaderIsTheOnlyPlaceTheCredentialsTravel() {
        transport.next = new SimplefinTransport.Response(200, ACCOUNT_SET);

        client.fetchAccounts(SECRET_ACCESS, LocalDate.of(2026, 1, 1));

        assertThat(transport.uri.toString()).doesNotContain(USERNAME).doesNotContain(PASSWORD).doesNotContain("@");
        assertThat(transport.uri.getRawQuery()).doesNotContain(USERNAME).doesNotContain(PASSWORD);
        assertThat(transport.authorization).doesNotContain(PASSWORD);
        String decoded = new String(Base64.getDecoder().decode(transport.authorization.substring("Basic ".length())),
            StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(USERNAME + ":" + PASSWORD);
    }

    @Test
    void aFailedSyncOnAForeignStoredUrlSendsNothingAndLogsNoCredentials() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SimplefinSyncService.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            SimplefinConnectionRepository connections = mock(SimplefinConnectionRepository.class);
            CryptoEncryption encryption = mock(CryptoEncryption.class);
            SimplefinStatusWriter statusWriter = mock(SimplefinStatusWriter.class);
            SimplefinConnection stored = SimplefinConnection.builder().id(3L).accessUrl("ciphertext").build();
            when(connections.findByMemberId(7L)).thenReturn(Optional.of(stored));
            // e.g. a row written by an older build that accepted any host
            when(encryption.decrypt("ciphertext")).thenReturn("https://" + USERNAME + ":" + PASSWORD + "@10.0.0.5:8443/simplefin");
            when(importService.sharedHistoryStart()).thenReturn(LocalDate.now().minusDays(30));
            SimplefinSyncService service = serviceWith(connections, encryption, mock(FamilyMemberRepository.class), statusWriter);

            assertThatThrownBy(() -> service.sync(7L))
                .isInstanceOf(SyncException.class)
                .satisfies(ex -> assertThat(SimplefinUrlsTest.everythingPrintable(ex))
                    .doesNotContain(PASSWORD).doesNotContain(USERNAME));
            assertThat(service.resyncReporting(7L).message()).doesNotContain(PASSWORD).doesNotContain(USERNAME);

            assertThat(transport.calls).isZero();
            verify(statusWriter, org.mockito.Mockito.atLeastOnce()).markError(3L);
            assertThat(logs.list).isNotEmpty();
            for (ILoggingEvent event : logs.list) {
                assertThat(allText(event)).doesNotContain(PASSWORD).doesNotContain(USERNAME).doesNotContain("10.0.0.5");
            }
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void partialErrorsComeOnlyFromTheBridgeJson() {
        transport.next = new SimplefinTransport.Response(200, """
            {"errlist": [{"code":"gen.auth","msg":"Reauthenticate at the bank."}, "A plain string.", {"code":"x"}],
             "errors": ["Legacy error."], "accounts": []}
            """);

        assertThatThrownBy(() -> client.fetchAccounts(SECRET_ACCESS, LocalDate.of(2026, 1, 1)))
            .isInstanceOf(SyncException.class)
            .hasMessage("Reauthenticate at the bank. A plain string. Legacy error.");
    }

    @Test
    void aBridgeErrorMessageIsNeverMixedWithTheRequestDetails() {
        transport.next = new SimplefinTransport.Response(200, "{\"errlist\":[{\"msg\":\"Bank offline.\"}],\"accounts\":[]}");

        assertThatThrownBy(() -> client.fetchAccounts(SECRET_ACCESS, LocalDate.of(2026, 1, 1)))
            .satisfies(ex -> assertThat(SimplefinUrlsTest.everythingPrintable(ex))
                .doesNotContain(USERNAME).doesNotContain(PASSWORD).doesNotContain("beta-bridge").doesNotContain("Basic"));
    }

    // ---- what the status endpoint can show ----------------------------------------------------

    @Test
    void theStatusPayloadOnlyCarriesTheLastFourOfTheUsername() {
        SimplefinConnectionRepository connections = mock(SimplefinConnectionRepository.class);
        CryptoEncryption encryption = mock(CryptoEncryption.class);
        SimplefinConnection stored = SimplefinConnection.builder().id(3L).accessUrl("ciphertext-of-the-access-url").build();
        when(connections.findByMemberId(7L)).thenReturn(Optional.of(stored));
        when(encryption.decrypt("ciphertext-of-the-access-url")).thenReturn(SECRET_ACCESS);
        SimplefinSyncService service = serviceWith(connections, encryption, mock(FamilyMemberRepository.class), mock(SimplefinStatusWriter.class));

        SimplefinConnectionStatusResponse status = service.getConnectionStatus(7L);

        assertThat(status.maskedToken()).isEqualTo("••••8765");
        assertThat(status.toString())
            .doesNotContain(PASSWORD).doesNotContain(USERNAME).doesNotContain("beta-bridge")
            .doesNotContain("ciphertext").doesNotContain("https://");
        assertThat(new ObjectMapper().valueToTree(status).toString())
            .doesNotContain(PASSWORD).doesNotContain(USERNAME).doesNotContain("ciphertext");
    }

    @Test
    void aShortUsernameIsMaskedEntirely() {
        SimplefinConnectionRepository connections = mock(SimplefinConnectionRepository.class);
        CryptoEncryption encryption = mock(CryptoEncryption.class);
        when(connections.findByMemberId(7L)).thenReturn(Optional.of(SimplefinConnection.builder().id(3L).accessUrl("c").build()));
        when(encryption.decrypt("c")).thenReturn("https://abcd:" + PASSWORD + "@beta-bridge.simplefin.org/simplefin");
        SimplefinSyncService service = serviceWith(connections, encryption, mock(FamilyMemberRepository.class), mock(SimplefinStatusWriter.class));

        assertThat(service.getConnectionStatus(7L).maskedToken()).isEqualTo("••••");
    }

    @Test
    void aStoredValueThatCannotBeDecryptedShowsAFixedMaskAndNothingElse() {
        SimplefinConnectionRepository connections = mock(SimplefinConnectionRepository.class);
        CryptoEncryption encryption = mock(CryptoEncryption.class);
        when(connections.findByMemberId(7L)).thenReturn(
            Optional.of(SimplefinConnection.builder().id(3L).accessUrl("stored-ciphertext").build()));
        when(encryption.decrypt("stored-ciphertext")).thenThrow(new IllegalStateException("bad key for stored-ciphertext"));
        SimplefinSyncService service = serviceWith(connections, encryption, mock(FamilyMemberRepository.class), mock(SimplefinStatusWriter.class));

        SimplefinConnectionStatusResponse status = service.getConnectionStatus(7L);

        assertThat(status.status()).isEqualTo("ERROR");
        assertThat(status.maskedToken()).isEqualTo("••••");
        assertThat(status.toString()).doesNotContain("stored-ciphertext");
    }

    @Test
    void theStatusPayloadHasNoFieldThatCouldHoldTheAccessUrl() {
        assertThat(Arrays.stream(SimplefinConnectionStatusResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName))
            .containsExactly("connected", "connectionId", "status", "lastSyncedAt", "maskedToken");
    }

    @Test
    void printingAConnectionNeverShowsTheStoredAccessValue() {
        FamilyMember member = new FamilyMember();
        member.setId(7L);
        SimplefinConnection connection = SimplefinConnection.builder()
            .id(3L).member(member).accessUrl("cipher-SENTINEL-VALUE").build();

        // No Lombok @Data / @ToString on the entity or AuditableEntity: Object.toString() only.
        assertThat(connection.toString()).doesNotContain("SENTINEL").startsWith("com.picsou.model.SimplefinConnection@");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static FamilyMemberRepository knownMember() {
        FamilyMember member = new FamilyMember();
        member.setId(7L);
        FamilyMemberRepository members = mock(FamilyMemberRepository.class);
        when(members.findById(7L)).thenReturn(Optional.of(member));
        return members;
    }

    private SimplefinSyncService serviceWith(
        SimplefinConnectionRepository connections,
        CryptoEncryption encryption,
        FamilyMemberRepository members,
        SimplefinStatusWriter statusWriter
    ) {
        return new SimplefinSyncService(
            client, connections, mock(AccountRepository.class), members,
            mock(AccountService.class), importService, encryption, statusWriter);
    }

    private static String allText(ILoggingEvent event) {
        String text = event.getFormattedMessage();
        if (event.getThrowableProxy() != null) text += "\n" + ThrowableProxyUtil.asString(event.getThrowableProxy());
        return text;
    }

    private void assertRejected(String setupToken) {
        assertThatThrownBy(() -> client.claim(setupToken)).isInstanceOf(SyncException.class);
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
