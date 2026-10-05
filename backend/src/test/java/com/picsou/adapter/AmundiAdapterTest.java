package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.AmundiErrorCode;
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

class AmundiAdapterTest {

    @Test
    void fetchPlans_mapsTheStrictSidecarContract() {
        AmundiAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"PEG001",
              "name":"Plan d'Épargne Groupe",
              "planKind":"PEG",
              "employer":"ACME SA",
              "balanceEur":1234.56,
              "positions":[{
                "isin":"FR0010405035",
                "label":"Amundi Label Actions Solidaires",
                "quantity":12.3456,
                "unitValue":100.00,
                "valueEur":1234.56,
                "pnlEur":34.56
              }],
              "snapshotComplete":true
            }]
            """);

        var plans = adapter.fetchPlans("encrypted-browser-state");

        assertThat(plans).singleElement().satisfies(plan -> {
            assertThat(plan.externalId()).isEqualTo("PEG001");
            assertThat(plan.planKind()).isEqualTo("PEG");
            assertThat(plan.employer()).isEqualTo("ACME SA");
            assertThat(plan.balanceEur()).isEqualByComparingTo("1234.56");
            assertThat(plan.snapshotComplete()).isTrue();
            assertThat(plan.positions()).singleElement().satisfies(position -> {
                assertThat(position.isin()).isEqualTo("FR0010405035");
                assertThat(position.quantity()).isEqualByComparingTo("12.3456");
                assertThat(position.unitValue()).isEqualByComparingTo("100.00");
                assertThat(position.valueEur()).isEqualByComparingTo("1234.56");
                assertThat(position.pnlEur()).isEqualByComparingTo("34.56");
            });
        });
    }

    @Test
    void fetchPlans_preservesAStableSidecarErrorCode() {
        AmundiAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchPlans("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    @Test
    void initiateAuth_surfacesACaptchaWallAsItsOwnCode() {
        AmundiAdapter adapter = adapterReturning(
            HttpStatus.FORBIDDEN,
            "{\"detail\":\"CAPTCHA_BLOCKED\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.CAPTCHA_BLOCKED.name())
            );
    }

    @Test
    void completeAuth_surfacesAnUnconfirmedAppPushAsItsOwnCode() {
        AmundiAdapter adapter = adapterReturning(
            HttpStatus.REQUEST_TIMEOUT,
            "{\"detail\":\"APP_VALIDATION_TIMEOUT\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", null))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.APP_VALIDATION_TIMEOUT.name())
            );
    }

    @Test
    void completeAuth_sendsAnExplicitNullCodeForAnAppPush() {
        List<String> bodies = new ArrayList<>();
        AmundiAdapter adapter = adapterRecording(bodies, """
            {"sessionState":"state"}
            """);

        assertThat(adapter.completeAuth("process-1", null)).isEqualTo("state");
        // The sidecar forbids unknown fields but needs the key present, so an
        // omitted `code` would be rejected outright rather than read as a push.
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"processId\":\"process-1\"");
            assertThat(body).contains("\"code\":null");
        });
    }

    @Test
    void completeAuth_mapsAnUncoded401ToInvalidOtp() {
        AmundiAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void completeAuth_mapsAnExpiredAttemptFromItsStatusAlone() {
        AmundiAdapter adapter = adapterReturning(HttpStatus.GONE, "{}");

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.AUTH_ATTEMPT_EXPIRED.name())
            );
    }

    @Test
    void initiateAuth_mapsAnEmptySuccessResponseToUnavailable() {
        AmundiAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth("login", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchPlans_treatsAnEmptyArrayAsAnIncompleteRead() {
        AmundiAdapter adapter = adapterReturning(HttpStatus.OK, "[]");

        assertThatThrownBy(() -> adapter.fetchPlans("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.PORTFOLIO_INCOMPLETE.name())
            );
    }

    @Test
    void fetchPlans_mapsMalformedErrorJsonToUnavailable() {
        AmundiAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchPlans("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchPlans_mapsUnknownSidecarCodeToUnavailable() {
        AmundiAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY,
            "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchPlans("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchPlans_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        AmundiAdapter adapter = new AmundiAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchPlans("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(AmundiErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    /**
     * An app push waits on a human, so the completion call must outlive the
     * plain auth budget -- otherwise a validation that was about to succeed
     * would be cut off client-side.
     */
    @Test
    void completeAuth_waitsLongerThanAPlainAuthenticationCall() {
        ExchangeFunction neverResponds = request -> Mono.never();
        AmundiAdapter adapter = new AmundiAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(600),
            Duration.ofMillis(20)
        );

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> adapter.completeAuth("process", null))
            .isInstanceOf(SyncException.class);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThan(100);
    }

    private AmundiAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new AmundiAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }

    private AmundiAdapter adapterRecording(List<String> bodies, String responseBody) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            bodies.add(recorder.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        };
        return new AmundiAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }
}
