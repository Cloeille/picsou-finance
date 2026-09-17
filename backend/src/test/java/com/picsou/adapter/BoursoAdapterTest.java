package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.model.AccountType;
import com.picsou.port.BoursoErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoursoAdapterTest {

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"bourso_e2f509c466f5294f15abd873dbbf8a62",
              "name":"BoursoBank",
              "type":"CHECKING",
              "balanceEur":20810.50,
              "cashBalance":null,
              "positions":[],
              "snapshotComplete":true
            },{
              "externalId":"bourso_9651d8edd5975de1b9eff3865505f15f",
              "name":"PEA DOE",
              "type":"PEA",
              "balanceEur":143088.89,
              "cashBalance":3088.89,
              "positions":[{
                "isin":"IE00B4L5Y983",
                "symbol":"1rTCW8",
                "label":"iShares Core MSCI World",
                "quantity":1000,
                "buyingPriceEur":128.00,
                "currentPrice":140.00,
                "quoteCurrency":"EUR",
                "currentValueEur":140000.00,
                "pnlEur":12000.00
              }],
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-cookies");

        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0)).satisfies(account -> {
            assertThat(account.type()).isEqualTo(AccountType.CHECKING);
            assertThat(account.balanceEur()).isEqualByComparingTo("20810.50");
            assertThat(account.cashBalance()).isNull();
            assertThat(account.positions()).isEmpty();
            assertThat(account.snapshotComplete()).isTrue();
        });
        assertThat(accounts.get(1)).satisfies(account -> {
            assertThat(account.type()).isEqualTo(AccountType.PEA);
            assertThat(account.cashBalance()).isEqualByComparingTo("3088.89");
            assertThat(account.positions()).singleElement().satisfies(position -> {
                assertThat(position.isin()).isEqualTo("IE00B4L5Y983");
                assertThat(position.symbol()).isEqualTo("1rTCW8");
                assertThat(position.quantity()).isEqualByComparingTo("1000");
                assertThat(position.buyingPriceEur()).isEqualByComparingTo("128.00");
                assertThat(position.quoteCurrency()).isEqualTo("EUR");
                assertThat(position.currentValueEur()).isEqualByComparingTo("140000.00");
                assertThat(position.pnlEur()).isEqualByComparingTo("12000.00");
            });
        });
    }

    @Test
    void fetchAccounts_preservesAStableSidecarErrorCode() {
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("cookies");
            });
    }

    @Test
    void fetchAccounts_surfacesAScrapingBreakageAsItsOwnCode() {
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"UPSTREAM_FORMAT_CHANGED\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.UPSTREAM_FORMAT_CHANGED.name())
            );
    }

    @Test
    void fetchAccounts_surfacesAnExpiredSessionSoTheUserIsAskedToReconnect() {
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"SESSION_EXPIRED\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.SESSION_EXPIRED.name())
            );
    }

    @Test
    void initiateAuth_reportsAnUnsupportedSecondFactorRatherThanBadCredentials() {
        // BoursoBank asking for an SMS code is a configuration problem the user
        // can fix; calling it a wrong password sends them to reset it instead.
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.NOT_IMPLEMENTED,
            "{\"detail\":\"MFA_TYPE_UNSUPPORTED\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("12345678", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.MFA_TYPE_UNSUPPORTED.name());
                assertThat(error.getMessage()).contains("app validation");
            });
    }

    @Test
    void initiateAuth_mapsAnUncoded401ToInvalidCredentials() {
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("12345678", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.INVALID_CREDENTIALS.name())
            );
    }

    @Test
    void initiateAuth_reportsAFraudEducationInterstitialRatherThanBadCredentials() {
        // BoursoBank parks a valid login on its fraud-education notice until the
        // holder ticks it on the bank's website; calling it a wrong password
        // sends the user to reset credentials that already work.
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"FRAUD_ACK_REQUIRED\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("12345678", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.FRAUD_ACK_REQUIRED.name());
                assertThat(error.getMessage()).contains("fraud-prevention notice");
            });
    }

    @Test
    void initiateAuth_reportsAPendingAppPush() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"processId":"p-1","mfaRequired":true,"mfaType":"APP_PUSH","sessionState":null}
            """);

        var result = adapter.initiateAuth("12345678", "123456");

        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.mfaType()).isEqualTo("APP_PUSH");
        assertThat(result.sessionState()).isNull();
    }

    @Test
    void completeAuth_surfacesAnUnconfirmedAppPushAsItsOwnCode() {
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.REQUEST_TIMEOUT,
            "{\"detail\":\"APP_VALIDATION_TIMEOUT\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.APP_VALIDATION_TIMEOUT.name())
            );
    }

    @Test
    void completeAuth_sendsAnExplicitNullCodeBecauseTheSidecarForbidsUnknownFields() {
        List<String> bodies = new ArrayList<>();
        BoursoAdapter adapter = adapterRecording(bodies, """
            {"sessionState":"cookies"}
            """);

        assertThat(adapter.completeAuth("process-1")).isEqualTo("cookies");
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"processId\":\"process-1\"");
            assertThat(body).contains("\"code\":null");
        });
    }

    @Test
    void completeAuth_mapsAnExpiredAttemptFromItsStatusAlone() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.GONE, "{}");

        assertThatThrownBy(() -> adapter.completeAuth("process"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.AUTH_ATTEMPT_EXPIRED.name())
            );
    }

    @Test
    void initiateAuth_mapsAnEmptySuccessResponseToUnavailable() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth("12345678", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_treatsAnEmptyArrayAsAnIncompleteRead() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.OK, "[]");

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.PORTFOLIO_INCOMPLETE.name())
            );
    }

    @Test
    void fetchAccounts_mapsMalformedErrorJsonToUnavailable() {
        BoursoAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_mapsUnknownSidecarCodeToUnavailable() {
        BoursoAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchAccounts_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        BoursoAdapter adapter = new BoursoAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(BoursoErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    /**
     * The push waits on a human unlocking their phone, so the completion call
     * must outlive the plain auth budget -- otherwise a validation that was
     * about to succeed would be cut off client-side.
     */
    @Test
    void completeAuth_waitsLongerThanAPlainAuthenticationCall() {
        ExchangeFunction neverResponds = request -> Mono.never();
        BoursoAdapter adapter = new BoursoAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(600),
            Duration.ofMillis(20)
        );

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> adapter.completeAuth("process"))
            .isInstanceOf(SyncException.class);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThan(100);
    }

    private BoursoAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new BoursoAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }

    private BoursoAdapter adapterRecording(List<String> bodies, String responseBody) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            bodies.add(recorder.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        };
        return new BoursoAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }
}
