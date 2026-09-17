package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.FortuneoErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FortuneoAdapterTest {

    @Test
    void constructor_acceptsBracketedIpv6LoopbackOverHttp() {
        assertThatCode(() -> new FortuneoAdapter("http://[::1]:8001", new ObjectMapper()))
            .doesNotThrowAnyException();
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

    @Test
    void fetchAccounts_preservesAStableSidecarErrorCode() {
        FortuneoAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    @Test
    void fetchAccounts_preservesInvestorProfileRequired() {
        FortuneoAdapter adapter = adapterReturning(
            HttpStatus.CONFLICT,
            "{\"detail\":\"INVESTOR_PROFILE_REQUIRED\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode())
                    .isEqualTo(FortuneoErrorCode.INVESTOR_PROFILE_REQUIRED.name())
            );
    }

    @Test
    void completeAuth_mapsAnUncoded401ToInvalidOtp() {
        FortuneoAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void initiateAuth_mapsAnEmptySuccessResponseToUnavailable() {
        FortuneoAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void completeAuth_preservesValidationErrorCodeFromTheSidecar() {
        FortuneoAdapter adapter = adapterReturning(
            HttpStatus.BAD_REQUEST,
            "{\"detail\":\"INVALID_OTP\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void fetchAccounts_mapsMalformedErrorJsonToUnavailable() {
        FortuneoAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_mapsUnknownSidecarCodeToUnavailable() {
        FortuneoAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        FortuneoAdapter adapter = new FortuneoAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(FortuneoErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    private FortuneoAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new FortuneoAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }
}
