package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.adapter.sidecar.SidecarAuthenticationException;
import com.picsou.adapter.sidecar.SidecarTestSupport;
import com.picsou.adapter.sidecar.SidecarWebClientFactory;
import com.picsou.model.AccountType;
import com.sun.net.httpserver.HttpServer;
import com.picsou.support.WebClientStubs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FortuneoAdapterTest {

    @Test
    void constructor_acceptsBracketedIpv6LoopbackOverHttp() {
        assertThatCode(() -> new FortuneoAdapter(
            SidecarTestSupport.factory("fortuneo-auth", "http://[::1]:8001"), new ObjectMapper()))
            .doesNotThrowAnyException();
    }

    @Test
    void initiateAuth_presentsTheSharedKeyAndReportsItsRejectionAsADeploymentFault() throws Exception {
        List<String> keys = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/initiate", exchange -> {
            keys.add(exchange.getRequestHeaders().getFirst(SidecarWebClientFactory.API_KEY_HEADER));
            exchange.getResponseHeaders().add("WWW-Authenticate", SidecarWebClientFactory.AUTH_CHALLENGE);
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            FortuneoAdapter adapter = new FortuneoAdapter(
                SidecarTestSupport.factory("fortuneo-auth", "http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper()
            );

            assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
                .isInstanceOfSatisfying(SidecarAuthenticationException.class, error ->
                    assertThat(error.getCode()).isEqualTo(SidecarAuthenticationException.CODE));
            assertThat(keys).containsExactly("test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        FortuneoAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"pea-42",
              "name":"PEA Fortuneo",
              "type":"PEA",
              "balanceEur":1250.50,
              "cashBalance":250.50,
              "positions":[{
                "isin":"FR0000000001",
                "symbol":"ACME",
                "label":"Acme SA",
                "quantity":10,
                "buyingPriceEur":80,
                "currentPrice":100,
                "quoteCurrency":"EUR",
                "currentValueEur":1000,
                "pnlEur":200
              }],
              "transactions":[],
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-browser-state");

        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo("pea-42");
            assertThat(account.type()).isEqualTo(AccountType.PEA);
            assertThat(account.balanceEur()).isEqualByComparingTo("1250.50");
            assertThat(account.snapshotComplete()).isTrue();
            assertThat(account.positions()).singleElement().satisfies(position -> {
                assertThat(position.symbol()).isEqualTo("ACME");
                assertThat(position.quoteCurrency()).isEqualTo("EUR");
                assertThat(position.currentValueEur()).isEqualByComparingTo("1000");
            });
        });
    }

    @Test
    void fetchAccounts_mapsACashAccountWithTransactions() {
        FortuneoAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"cc-7",
              "name":"Compte Courant",
              "type":"CHECKING",
              "balanceEur":500.00,
              "cashBalance":500.00,
              "positions":[],
              "transactions":[{
                "date":"2026-07-20",
                "label":"Virement recu",
                "amount":100.00,
                "category":"Virement"
              }],
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-browser-state");

        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.type()).isEqualTo(AccountType.CHECKING);
            assertThat(account.positions()).isEmpty();
            assertThat(account.transactions()).singleElement().satisfies(tx ->
                assertThat(tx.label()).isEqualTo("Virement recu"));
        });
    }

    private FortuneoAdapter adapterReturning(HttpStatus status, String body) {
        return new FortuneoAdapter(WebClientStubs.returning(status, body), new ObjectMapper());
    }
}
