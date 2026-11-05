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
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorumAdapterTest {

    @Test
    void fetchPortfolio_mapsTheStrictSidecarContract() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"realEstate":{
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
            },"life":[]}
            """);

        var snapshot = adapter.fetchPortfolio("encrypted-browser-state").realEstate();

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
            {"realEstate":{
              "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
              "totalValuationEur":534.37,"valuationDate":"2026-09-26","snapshotComplete":true,
              "holdings":[{
                "fundCode":"US","label":"CORUM USA","quantity":2.67183,
                "withdrawalPrice":176,"subscriptionPrice":200,
                "displayedValueEur":534.37,"valuationDate":"2026-09-26"
              }]
            },"life":[]}
            """);

        var holding = adapter.fetchPortfolio("state").realEstate().holdings().getFirst();

        // 534.37 is what CORUM shows: quantity x the subscription price.
        // The withdrawal value is 470.24, and the difference is the entry fee.
        assertThat(holding.displayedValueEur()).isEqualByComparingTo("534.37");
        assertThat(holding.withdrawalValue()).isEqualByComparingTo("470.24208");
        assertThat(holding.withdrawalValue()).isLessThan(holding.displayedValueEur());
    }

    @Test
    void holding_withoutWithdrawalPriceYieldsNoValueRatherThanTheSubscriptionOne() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"realEstate":{
              "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
              "totalValuationEur":534.37,"valuationDate":"2026-09-26","snapshotComplete":true,
              "holdings":[{
                "fundCode":"US","label":"CORUM USA","quantity":2.67183,
                "withdrawalPrice":null,"subscriptionPrice":200,
                "displayedValueEur":534.37,"valuationDate":"2026-09-26"
              }]
            },"life":[]}
            """);

        var holding = adapter.fetchPortfolio("state").realEstate().holdings().getFirst();

        // Null, not the subscription price: an absent quote is not a quote.
        assertThat(holding.withdrawalValue()).isNull();
    }

    /** A true flag is the sidecar's promise that the funds reconcile. A false one must not be trusted. */
    @Test
    void fetchPortfolio_refusesASnapshotTheSidecarDidNotCertify() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"realEstate":{
              "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
              "totalValuationEur":100.00,"valuationDate":"2026-09-26","snapshotComplete":false,
              "holdings":[]
            },"life":[]}
            """);

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.PORTFOLIO_INCOMPLETE.name())
            );
    }

    /** The fixed sidecar contract for a CORUM Life contract, euro fund included as a holding. */
    @Test
    void fetchPortfolio_mapsTheLifeContractsFromPortfolio() {
        AtomicReference<String> path = new AtomicReference<>();
        CorumAdapter adapter = adapterRecording(path, HttpStatus.OK, """
            {
              "realEstate":null,
              "life":[{
                "contractCode":"1001",
                "currency":"EUR",
                "totalValuationEur":1534.37,
                "valuationDate":"2026-11-04",
                "valuationLineCount":3,
                "snapshotComplete":true,
                "holdings":[{
                  "productCode":"XL","label":"CORUM XL","quantity":2.65955,"unitPrice":195,
                  "valueEur":518.61,"netInvestedEur":480.0,"valuationDate":"2026-11-04","euroFund":false
                },{
                  "productCode":"FGCORUM","label":"CORUM EuroLife (fonds euros)","quantity":1015.76,
                  "unitPrice":1,"valueEur":1015.76,"netInvestedEur":null,"valuationDate":null,"euroFund":true
                }]
              }]
            }
            """);

        var portfolio = adapter.fetchPortfolio("state");

        assertThat(path.get()).isEqualTo("/portfolio");
        assertThat(portfolio.realEstate()).isNull();
        assertThat(portfolio.life()).singleElement().satisfies(contract -> {
            assertThat(contract.contractCode()).isEqualTo("1001");
            assertThat(contract.currency()).isEqualTo("EUR");
            assertThat(contract.totalValuationEur()).isEqualByComparingTo("1534.37");
            assertThat(contract.valuationDate()).isEqualTo(LocalDate.of(2026, 11, 4));
            assertThat(contract.valuationLineCount()).isEqualTo(3);
            assertThat(contract.snapshotComplete()).isTrue();
            assertThat(contract.holdings()).hasSize(2);
            var xl = contract.holdings().get(0);
            assertThat(xl.productCode()).isEqualTo("XL");
            assertThat(xl.quantity()).isEqualByComparingTo("2.65955");
            assertThat(xl.unitPrice()).isEqualByComparingTo("195");
            assertThat(xl.valueEur()).isEqualByComparingTo("518.61");
            assertThat(xl.netInvestedEur()).isEqualByComparingTo("480");
            assertThat(xl.euroFund()).isFalse();
            var euroFund = contract.holdings().get(1);
            assertThat(euroFund.euroFund()).isTrue();
            assertThat(euroFund.netInvestedEur()).isNull();
            assertThat(euroFund.valuationDate()).isNull();
        });
    }

    /** The contract-level facts and the per-line asset class, as the current sidecar sends them. */
    @Test
    void fetchPortfolio_mapsTheLifeContractFacts() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "realEstate":null,
              "life":[{
                "contractCode":"1001","currency":"EUR","totalValuationEur":518.61,
                "valuationDate":"2026-11-04","valuationLineCount":1,
                "netInvestedEur":500.0,"paidInEur":520,"openedAt":"2024-01-15",
                "scheduledPayment":{"amountEur":50,"frequency":"monthly"},
                "snapshotComplete":true,
                "holdings":[{
                  "productCode":"XL","label":"CORUM XL","quantity":2.65955,"unitPrice":195,
                  "valueEur":518.61,"netInvestedEur":480.0,"valuationDate":"2026-11-04","euroFund":false,
                  "assetClass":"realEstate"
                }]
              }]
            }
            """);

        var contract = adapter.fetchPortfolio("state").life().getFirst();

        assertThat(contract.netInvestedEur()).isEqualByComparingTo("500");
        assertThat(contract.paidInEur()).isEqualByComparingTo("520");
        assertThat(contract.openedAt()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(contract.scheduledPayment().amountEur()).isEqualByComparingTo("50");
        assertThat(contract.scheduledPayment().frequency()).isEqualTo("monthly");
        assertThat(contract.holdings().getFirst().assetClass()).isEqualTo("realEstate");
    }

    /** A sidecar that predates the contract facts: they decode as null, not as a failure. */
    @Test
    void fetchPortfolio_readsALifeContractWithoutTheContractFacts() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "realEstate":null,
              "life":[{
                "contractCode":"1001","currency":"EUR","totalValuationEur":518.61,
                "valuationDate":"2026-11-04","valuationLineCount":1,"snapshotComplete":true,
                "holdings":[{
                  "productCode":"XL","label":"CORUM XL","quantity":2.65955,"unitPrice":195,
                  "valueEur":518.61,"netInvestedEur":480.0,"valuationDate":"2026-11-04","euroFund":false
                }]
              }]
            }
            """);

        var contract = adapter.fetchPortfolio("state").life().getFirst();

        assertThat(contract.netInvestedEur()).isNull();
        assertThat(contract.paidInEur()).isNull();
        assertThat(contract.openedAt()).isNull();
        assertThat(contract.scheduledPayment()).isNull();
        assertThat(contract.holdings().getFirst().assetClass()).isNull();
        assertThat(contract.operations()).isNull();
    }

    /** The operation list, signed amounts and fund codes as the sidecar sends them. */
    @Test
    void fetchPortfolio_mapsTheLifeOperations() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "realEstate":null,
              "life":[{
                "contractCode":"1001","currency":"EUR","totalValuationEur":518.61,
                "valuationDate":"2026-11-04","valuationLineCount":1,"snapshotComplete":true,
                "holdings":[{
                  "productCode":"XL","label":"CORUM XL","quantity":2.65955,"unitPrice":195,
                  "valueEur":518.61,"netInvestedEur":480.0,"valuationDate":"2026-11-04","euroFund":false
                }],
                "operations":[
                  {"operationId":"700010","type":"couponDetachment","category":"couponDetachment",
                   "date":"2026-10-31","amountEur":3.21,"fundCodes":["XL"]},
                  {"operationId":"700008","type":"administrativeManagementCosts","category":null,
                   "date":"2026-09-30","amountEur":-1.42,"fundCodes":null}
                ]
              }]
            }
            """);

        var operations = adapter.fetchPortfolio("state").life().getFirst().operations();

        assertThat(operations).hasSize(2);
        assertThat(operations.get(0).operationId()).isEqualTo("700010");
        assertThat(operations.get(0).type()).isEqualTo("couponDetachment");
        assertThat(operations.get(0).date()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(operations.get(0).amountEur()).isEqualByComparingTo("3.21");
        assertThat(operations.get(0).fundCodes()).containsExactly("XL");
        assertThat(operations.get(1).amountEur()).isEqualByComparingTo("-1.42");
        assertThat(operations.get(1).category()).isNull();
        assertThat(operations.get(1).fundCodes()).isNull();
    }

    /** One uncertified life contract fails the whole read: it never drops out silently. */
    @Test
    void fetchPortfolio_refusesALifeContractTheSidecarDidNotCertify() {
        CorumAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "realEstate":{
                "contractCode":"000000","propertyRightType":"FULL_PROPERTY","currency":"EUR",
                "totalValuationEur":534.37,"valuationDate":"2026-09-26","snapshotComplete":true,
                "holdings":[{
                  "fundCode":"US","label":"CORUM USA","quantity":2.67183,
                  "withdrawalPrice":176,"subscriptionPrice":200,
                  "displayedValueEur":534.37,"valuationDate":"2026-09-26"
                }]
              },
              "life":[{
                "contractCode":"1001","currency":"EUR","totalValuationEur":100.0,
                "valuationDate":null,"valuationLineCount":0,"snapshotComplete":false,"holdings":[]
              }]
            }
            """);

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.PORTFOLIO_INCOMPLETE.name())
            );
    }

    @Test
    void fetchPortfolio_preservesAStableSidecarErrorCode() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    @Test
    void fetchPortfolio_surfacesSeveralContractsAsItsOwnCode() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.CONFLICT, "{\"detail\":\"MULTIPLE_CONTRACTS\"}"
        );

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.MULTIPLE_CONTRACTS.name())
            );
    }

    /** A bare 401 from the sidecar means the session died, not that the login was wrong. */
    @Test
    void fetchPortfolio_mapsAnUncoded401ToSessionExpired() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
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
    void fetchPortfolio_mapsMalformedErrorJsonToUnavailable() {
        CorumAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchPortfolio_mapsUnknownSidecarCodeToUnavailable() {
        CorumAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchPortfolio_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        CorumAdapter adapter = new CorumAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchPortfolio("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(CorumErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    private CorumAdapter adapterRecording(AtomicReference<String> path, HttpStatus status, String body) {
        ExchangeFunction exchange = request -> {
            path.set(request.url().getPath());
            return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build());
        };
        return new CorumAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        );
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
