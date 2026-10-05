package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.BourseDirectErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BourseDirectAdapterTest {

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        BourseDirectAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"pea-42",
              "name":"PEA principal",
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
    void fetchAccounts_preservesAStableSidecarErrorCode() {
        BourseDirectAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    @Test
    void completeAuth_mapsAnUncoded401ToInvalidOtp() {
        BourseDirectAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void initiateAuth_mapsAnEmptySuccessResponseToUnavailable() {
        BourseDirectAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void completeAuth_preservesValidationErrorCodeFromTheSidecar() {
        BourseDirectAdapter adapter = adapterReturning(
            HttpStatus.BAD_REQUEST,
            "{\"detail\":\"INVALID_OTP\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void fetchAccounts_mapsMalformedErrorJsonToUnavailable() {
        BourseDirectAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_mapsUnknownSidecarCodeToUnavailable() {
        BourseDirectAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        BourseDirectAdapter adapter = new BourseDirectAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(BourseDirectErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    private BourseDirectAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new BourseDirectAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }
}
