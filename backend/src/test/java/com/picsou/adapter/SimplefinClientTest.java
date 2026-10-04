package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.SimplefinPort.SimplefinAccountSet;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimplefinClientTest {

    private static final String ACCESS = "https://user1234:secret@bridge.simplefin.org/simplefin";

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

    @Test
    void claimPostsTheDecodedUrlAndKeepsTheAccessUrl() {
        transport.next = new SimplefinTransport.Response(200, ACCESS + "\n");

        String access = client.claim(token("https://bridge.simplefin.org/simplefin/claim/abc"));

        assertThat(access).isEqualTo(ACCESS);
        assertThat(transport.method).isEqualTo("POST");
        assertThat(transport.uri).isEqualTo(URI.create("https://bridge.simplefin.org/simplefin/claim/abc"));
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
        assertThatThrownBy(() -> client.claim(token("https://bridge.simplefin.org/simplefin/claim/abc")))
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
        assertThatThrownBy(() -> client.claim(token("https://bridge.simplefin.org/simplefin/claim/abc")))
            .isInstanceOf(SyncException.class)
            .hasMessageContaining("redirected");
    }

    @Test
    void aClaimThatIsNotHttpsNeverLeavesTheProcess() {
        assertRejected(token("http://bridge.simplefin.org/simplefin/claim/abc"));
        assertRejected(token("https://user:pass@bridge.simplefin.org/simplefin/claim/abc"));
        assertRejected("not base64!!!");
        assertThat(transport.calls).isZero();
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
            "https://user:p+ss%2Bword@bridge.simplefin.org/simplefin",
            LocalDate.of(2026, 1, 1)).authorization();
        String decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("user:p+ss+word");
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
