package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.CorumErrorCode;
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

class CorumAdapterTest {

    @Test
    void fetchSnapshot_mapsTheStrictSidecarContract() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "contractCode":"000000",
              "propertyRightType":"FULL_PROPERTY",
              "currency":"EUR",
              "totalValuationEur":1052.98,
              "valuationDate":"2026-09-26",
              "snapshotComplete":true,
              "holdings":[{
                "fundCode":"US",
                "label":"CORUM USA",
                "quantity":2.67183,
                "withdrawalPrice":176,
                "subscriptionPrice":200,
                "displayedValueEur":534.37,
                "valuationDate":"2026-09-26"
              },{
                "fundCode":"XL",
                "label":"CORUM XL",
                "quantity":2.65955,
                "withdrawalPrice":171.6,
                "subscriptionPrice":195,
                "displayedValueEur":518.61,
                "valuationDate":"2026-09-26"
              }]
            }
            """);

        var snapshot = adapter.fetchSnapshot("encrypted-browser-state");

        assertThat(snapshot.contractCode()).isEqualTo("000000");
        assertThat(snapshot.currency()).isEqualTo("EUR");
        assertThat(snapshot.totalValuationEur()).isEqualByComparingTo("1052.98");
        assertThat(snapshot.snapshotComplete()).isTrue();
        assertThat(snapshot.holdings()).hasSize(2);
    }

    /**
     * The load-bearing rule: the holding carries both prices, and only the
     * withdrawal one may become a balance. A future refactor that reaches for
     * the displayed figure instead is the bug this test exists to catch.
     */
    @Test
    void holding_valuesItselfAtTheWithdrawalPriceNotTheDisplayedFigure() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
              "totalValuationEur":534.37,"valuationDate":"2026-09-26","snapshotComplete":true,
              "holdings":[{
                "fundCode":"US","label":"CORUM USA","quantity":2.67183,
                "withdrawalPrice":176,"subscriptionPrice":200,
                "displayedValueEur":534.37,"valuationDate":"2026-09-26"
              }]
            }
            """);

        var holding = adapter.fetchSnapshot("state").holdings().getFirst();

        // 534.37 is what CORUM shows: quantity x the subscription price.
        // The withdrawal value is 470.24, and the difference is the entry fee.
        assertThat(holding.displayedValueEur()).isEqualByComparingTo("534.37");
        assertThat(holding.withdrawalValue()).isEqualByComparingTo("470.24208");
        assertThat(holding.withdrawalValue()).isLessThan(holding.displayedValueEur());
    }

    @Test
    void holding_withoutWithdrawalPriceYieldsNoValueRatherThanTheSubscriptionOne() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
              "totalValuationEur":534.37,"valuationDate":"2026-09-26","snapshotComplete":true,
              "holdings":[{
                "fundCode":"US","label":"CORUM USA","quantity":2.67183,
                "withdrawalPrice":null,"subscriptionPrice":200,
                "displayedValueEur":534.37,"valuationDate":"2026-09-26"
              }]
            }
            """);

        var holding = adapter.fetchSnapshot("state").holdings().getFirst();

        // Null, not the subscription price: an absent quote is not a quote.
        assertThat(holding.withdrawalValue()).isNull();
    }

    /** A true flag is the sidecar's promise that the funds reconcile. A false one must not be trusted. */
    @Test
    void fetchSnapshot_refusesASnapshotTheSidecarDidNotCertify() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
              "totalValuationEur":100.00,"valuationDate":"2026-09-26","snapshotComplete":false,
              "holdings":[]
            }
            """);

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.PORTFOLIO_INCOMPLETE.name())
            );
    }

    @Test
    void fetchSnapshot_preservesAStableSidecarErrorCode() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    @Test
    void fetchSnapshot_surfacesSeveralContractsAsItsOwnCode() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.CONFLICT, "{\"detail\":\"MULTIPLE_CONTRACTS\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.MULTIPLE_CONTRACTS.name())
            );
    }

    /** A bare 401 from the sidecar means the session died, not that the login was wrong. */
    @Test
    void fetchSnapshot_mapsAnUncoded401ToSessionExpired() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.SESSION_EXPIRED.name())
            );
    }

    @Test
    void authenticate_returnsTheSessionState() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, "{\"sessionState\":\"state-blob\"}");

        assertThat(adapter.authenticate("000000", "password")).isEqualTo("state-blob");
    }

    @Test
    void authenticate_mapsBadCredentialsToInvalidCredentials() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"INVALID_CREDENTIALS\"}"
        );

        assertThatThrownBy(() -> adapter.authenticate("000000", "wrong"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.INVALID_CREDENTIALS.name())
            );
    }

    @Test
    void authenticate_mapsAnEmptySuccessResponseToUnavailable() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.authenticate("000000", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchSnapshot_mapsMalformedErrorJsonToUnavailable() {
        CorumAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchSnapshot_mapsUnknownSidecarCodeToUnavailable() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchSnapshot_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        CorumAdapter adapter = new CorumAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    private CorumAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new CorumAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        );
    }
}
