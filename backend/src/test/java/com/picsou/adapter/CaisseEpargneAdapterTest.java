package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.CaisseEpargneErrorCode;
import com.picsou.port.CaisseEpargnePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.sun.net.httpserver.HttpServer;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaisseEpargneAdapterTest {

    private static final String CONTRACT = """
        {
          "accounts": [
            {"externalId": "1001", "kind": "CURRENT_ACCOUNT", "name": "COMPTE COURANT",
             "balance": "1234.56", "currency": "EUR",
             "iban": "FR0000000000000000000000000", "ibanAmbiguous": false,
             "authorizedOverdraft": "500.00", "ceiling": null,
             "remainingDepositCapacity": null, "fillingRatio": null,
             "cardNature": null, "parentExternalId": null,
             "transactions": [
               {"externalId": "t1", "date": "2026-10-01", "dueDate": "2026-10-01",
                "amount": "-12.30", "currency": "EUR", "label": "SUPERMARCHE"}
             ],
             "snapshotComplete": true},
            {"externalId": "1002", "kind": "LIVRET_A", "name": "LIVRET A",
             "balance": "5000.00", "currency": "EUR",
             "iban": null, "ibanAmbiguous": false,
             "authorizedOverdraft": null, "ceiling": "22950.00",
             "remainingDepositCapacity": "17950.00", "fillingRatio": "0.2179",
             "cardNature": null, "parentExternalId": null,
             "transactions": [], "snapshotComplete": true},
            {"externalId": "2001", "kind": "CARD", "name": null,
             "balance": "-87.10", "currency": "EUR",
             "iban": null, "ibanAmbiguous": false,
             "authorizedOverdraft": null, "ceiling": null,
             "remainingDepositCapacity": null, "fillingRatio": null,
             "cardNature": "DEFERRED_DEBIT", "parentExternalId": "1001",
             "transactions": [
               {"externalId": "c1", "date": "2026-10-02", "dueDate": "2026-10-31",
                "amount": "-87.10", "currency": "EUR", "label": "RESTAURANT"}
             ],
             "snapshotComplete": true}
          ],
          "unsupported": [{"externalId": "9001", "familyCode": "7"}]
        }
        """;

    @Test
    void portExposesTheReadPathAndTheKeypadLoginAndNothingElse() {
        // initiate/keypad/complete exist; no method takes a password: only key positions.
        var names = new TreeSet<String>();
        for (Method method : CaisseEpargnePort.class.getMethods()) names.add(method.getName());
        assertThat(names).containsExactly("checkSession", "completeAuth", "fetchAccounts", "initiateAuth", "submitKeypad");
    }

    @Test
    void fetchAccounts_mapsTheSidecarContractWithDecimalsAsBigDecimal() {
        CaisseEpargneAdapter adapter = adapterReturning(HttpStatus.OK, CONTRACT);

        CaisseEpargnePort.AccountsSnapshot snapshot = adapter.fetchAccounts("state");

        assertThat(snapshot.accounts()).hasSize(3);
        CaisseEpargnePort.AccountData current = snapshot.accounts().get(0);
        assertThat(current.externalId()).isEqualTo("1001");
        assertThat(current.kind()).isEqualTo("CURRENT_ACCOUNT");
        assertThat(current.balance()).isEqualByComparingTo("1234.56");
        assertThat(current.currency()).isEqualTo("EUR");
        assertThat(current.iban()).isEqualTo("FR0000000000000000000000000");
        assertThat(current.ibanAmbiguous()).isFalse();
        assertThat(current.authorizedOverdraft()).isEqualByComparingTo("500.00");
        assertThat(current.snapshotComplete()).isTrue();
        assertThat(current.transactions()).singleElement().satisfies(tx -> {
            assertThat(tx.externalId()).isEqualTo("t1");
            assertThat(tx.date()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(tx.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(tx.amount()).isEqualByComparingTo("-12.30");
            assertThat(tx.label()).isEqualTo("SUPERMARCHE");
        });

        CaisseEpargnePort.AccountData livret = snapshot.accounts().get(1);
        assertThat(livret.kind()).isEqualTo("LIVRET_A");
        assertThat(livret.ceiling()).isEqualByComparingTo("22950.00");
        assertThat(livret.remainingDepositCapacity()).isEqualByComparingTo("17950.00");
        assertThat(livret.fillingRatio()).isEqualByComparingTo("0.2179");
        assertThat(livret.iban()).isNull();
        assertThat(livret.transactions()).isEmpty();

        CaisseEpargnePort.AccountData card = snapshot.accounts().get(2);
        assertThat(card.kind()).isEqualTo("CARD");
        assertThat(card.name()).isNull();
        assertThat(card.balance()).isEqualByComparingTo("-87.10");
        assertThat(card.cardNature()).isEqualTo("DEFERRED_DEBIT");
        assertThat(card.parentExternalId()).isEqualTo("1001");
        assertThat(card.transactions().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 10, 31));

        assertThat(snapshot.unsupported()).singleElement().satisfies(u -> {
            assertThat(u.externalId()).isEqualTo("9001");
            assertThat(u.familyCode()).isEqualTo("7");
        });
    }

    @Test
    void fetchAccounts_postsTheSessionStateToAccounts() {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        CaisseEpargneAdapter adapter = adapterRecording(paths, bodies, CONTRACT);

        adapter.fetchAccounts("opaque-state");

        assertThat(paths).containsExactly("/accounts");
        assertThat(bodies).singleElement().satisfies(body ->
            assertThat(body).contains("\"sessionState\":\"opaque-state\""));
    }

    @Test
    void checkSession_postsToTokenCheckAndReadsTheLifetime() {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        CaisseEpargneAdapter adapter = adapterRecording(paths, bodies, "{\"ok\":true,\"expiresIn\":267}");

        CaisseEpargnePort.CheckResult result = adapter.checkSession("opaque-state");

        assertThat(paths).containsExactly("/token-check");
        assertThat(result.ok()).isTrue();
        assertThat(result.expiresIn()).isEqualTo(267);
    }

    @Test
    void checkSession_surfacesAnExpiredSession() {
        CaisseEpargneAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"SESSION_EXPIRED\"}");

        assertThatThrownBy(() -> adapter.checkSession("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.SESSION_EXPIRED.name()));
    }

    @Test
    void fetchAccounts_preservesEveryStableSidecarErrorCode() {
        record Case(HttpStatus status, CaisseEpargneErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.UNAUTHORIZED, CaisseEpargneErrorCode.SESSION_EXPIRED),
            new Case(HttpStatus.BAD_GATEWAY, CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.BAD_GATEWAY, CaisseEpargneErrorCode.UPSTREAM_FORMAT_CHANGED),
            new Case(HttpStatus.BAD_REQUEST, CaisseEpargneErrorCode.INVALID_SESSION_STATE),
            new Case(HttpStatus.INTERNAL_SERVER_ERROR, CaisseEpargneErrorCode.INTERNAL_ERROR))) {
            CaisseEpargneAdapter adapter = adapterReturning(c.status(), "{\"detail\":\"" + c.code().name() + "\"}");

            assertThatThrownBy(() -> adapter.fetchAccounts("secret-state"))
                .isInstanceOfSatisfying(SyncException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(c.code().name());
                    assertThat(error.getMessage()).doesNotContain("secret-state");
                });
        }
    }

    @Test
    void fetchAccounts_mapsAnUnknownSidecarCodeToUnavailable() {
        CaisseEpargneAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"UPSTREAM_ERROR\"}");

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name()));
    }

    @Test
    void fetchAccounts_mapsANetworkTimeoutToARetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CaisseEpargneErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    @Test
    void errorCodesMatchTheSidecarContractExactly() {
        assertThat(Arrays.stream(CaisseEpargneErrorCode.values()).map(Enum::name))
            .containsExactlyInAnyOrder(
                "SESSION_EXPIRED", "UPSTREAM_UNAVAILABLE", "UPSTREAM_FORMAT_CHANGED",
                "INVALID_SESSION_STATE", "INTERNAL_ERROR",
                "INVALID_CREDENTIALS", "KEYPAD_CHANGED", "APP_VALIDATION_TIMEOUT", "AUTH_ATTEMPT_EXPIRED",
                "KEYPAD_EXPIRED", "INVALID_POSITIONS");
    }

    // -- helpers ------------------------------------------------------------

    @Test
    void fetchAccounts_decodesAPayloadLargerThanTheDefault256KbCodecLimit() throws Exception {
        // A real account with many transactions easily exceeds Spring's 256 KB default buffer.
        StringBuilder txs = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            if (i > 0) txs.append(',');
            txs.append("{\"externalId\":\"t").append(i).append("\",\"date\":\"2026-10-01\",")
                .append("\"dueDate\":\"2026-10-01\",\"amount\":\"-1.00\",\"currency\":\"EUR\",")
                .append("\"label\":\"").append("X".repeat(80)).append("\"}");
        }
        String body = "{\"accounts\":[{\"externalId\":\"1001\",\"kind\":\"CURRENT_ACCOUNT\","
            + "\"name\":\"C\",\"balance\":\"1.00\",\"currency\":\"EUR\",\"iban\":null,"
            + "\"ibanAmbiguous\":false,\"authorizedOverdraft\":null,\"ceiling\":null,"
            + "\"remainingDepositCapacity\":null,\"fillingRatio\":null,\"cardNature\":null,"
            + "\"parentExternalId\":null,\"transactions\":[" + txs + "],\"snapshotComplete\":true}],"
            + "\"unsupported\":[]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes.length).isGreaterThan(256 * 1024);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/accounts", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            CaisseEpargneAdapter adapter = new CaisseEpargneAdapter(
                new SidecarWebClientFactory("test-key"),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ObjectMapper());

            CaisseEpargnePort.AccountsSnapshot snapshot = adapter.fetchAccounts("state");

            assertThat(snapshot.accounts()).singleElement()
                .satisfies(a -> assertThat(a.transactions()).hasSize(3000));
        } finally {
            server.stop(0);
        }
    }

    private CaisseEpargneAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(exchange).build(), new ObjectMapper());
    }

    private CaisseEpargneAdapter adapterRecording(List<String> paths, List<String> bodies, String responseBody) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            paths.add(request.url().getPath());
            bodies.add(recorder.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        };
        return new CaisseEpargneAdapter(
            WebClient.builder().exchangeFunction(exchange).build(), new ObjectMapper());
    }
}
