package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SofidyErrorCode;
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

class SofidyAdapterTest {

    @Test
    void fetchSnapshot_mapsTheStrictSidecarContract() {
        SofidyAdapter adapter = adapterReturning(HttpStatus.OK, """
            {
              "currency":"EUR",
              "totalEur":300.00,
              "valuationDate":"2026-09-27",
              "snapshotComplete":true,
              "holdings":[{
                "fundCode":"XY",
                "label":"FONDS-EXEMPLE",
                "quantity":3.00000,
                "withdrawalPrice":100.00,
                "totalEur":300.00
              }]
            }
            """);

        var snapshot = adapter.fetchSnapshot("encrypted-session");

        assertThat(snapshot.currency()).isEqualTo("EUR");
        assertThat(snapshot.totalEur()).isEqualByComparingTo("300.00");
        assertThat(snapshot.valuationDate()).hasToString("2026-09-27");
        assertThat(snapshot.snapshotComplete()).isTrue();
        assertThat(snapshot.holdings()).hasSize(1);
        assertThat(snapshot.holdings().getFirst().fundCode()).isEqualTo("XY");
    }

    /**
     * The load-bearing rule. Sofidy prints "Valeur unitaire" and the page footnote
     * says it is the withdrawal price for a capital-variable SCPI, so the balance
     * may be quantity x that figure. A fund where the portal has no unit value
     * yields no balance at all: the previous one is kept and the position reports
     * PRICE_INCOMPLETE, exactly as a manual entry without a price would.
     */
    @Test
    void holding_valuesItselfAtTheWithdrawalPriceAndYieldsNothingWhenThatIsAbsent() {
        SofidyAdapter priced = adapterReturning(HttpStatus.OK, """
            {
              "currency":"EUR","totalEur":300.00,"valuationDate":"2026-09-27",
              "snapshotComplete":true,
              "holdings":[{
                "fundCode":"XY","label":"FONDS-EXEMPLE","quantity":3.00000,
                "withdrawalPrice":100.00,"totalEur":300.00
              }]
            }
            """);
        SofidyAdapter unpriced = adapterReturning(HttpStatus.OK, """
            {
              "currency":"EUR","totalEur":300.00,"valuationDate":"2026-09-27",
              "snapshotComplete":true,
              "holdings":[{
                "fundCode":"XY","label":"FONDS-EXEMPLE","quantity":3.00000,
                "withdrawalPrice":null,"totalEur":300.00
              }]
            }
            """);

        assertThat(priced.fetchSnapshot("state").holdings().getFirst().withdrawalValue())
            .isEqualByComparingTo("300.00");
        assertThat(unpriced.fetchSnapshot("state").holdings().getFirst().withdrawalValue())
            .isNull();
    }

    /** A true flag is the sidecar's promise that the funds reconcile. A false one must not be trusted. */
    @Test
    void fetchSnapshot_refusesASnapshotTheSidecarDidNotCertify() {
        SofidyAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"currency":"EUR","totalEur":100.00,"valuationDate":"2026-09-27",
             "snapshotComplete":false,"holdings":[]}
            """);

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.PORTFOLIO_INCOMPLETE.name())
            );
    }

    /**
     * A holder whose funds were all sold still gets a totals row. That is a real,
     * complete, empty portfolio, and refusing it would make a full exit
     * impossible to sync.
     */
    @Test
    void fetchSnapshot_acceptsACompleteEmptyPortfolio() {
        SofidyAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"currency":"EUR","totalEur":0,"valuationDate":"2026-09-27",
             "snapshotComplete":true,"holdings":[]}
            """);

        var snapshot = adapter.fetchSnapshot("state");

        assertThat(snapshot.holdings()).isEmpty();
        assertThat(snapshot.snapshotComplete()).isTrue();
    }

    @Test
    void fetchSnapshot_preservesAStableSidecarErrorCode() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"PORTFOLIO_INCOMPLETE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.PORTFOLIO_INCOMPLETE.name());
                assertThat(error.getMessage()).doesNotContain("state");
            });
    }

    /** A bare 401 from the sidecar means the session died, not that the login was wrong. */
    @Test
    void fetchSnapshot_mapsAnUncoded401ToSessionExpired() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.SESSION_EXPIRED.name())
            );
    }

    @Test
    void fetchSnapshot_mapsUnknownSidecarCodeToUnavailable() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.BAD_GATEWAY, "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}"
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchSnapshot_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        SofidyAdapter adapter = new SofidyAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    /**
     * The two-step login, end to end. Sofidy never opens a session on the first
     * call, so a result carrying one here would mean the sidecar reported success
     * where the portal only asked for a code.
     */
    @Test
    void initiateAuth_returnsTheProcessIdAndNoSession() {
        SofidyAdapter adapter = adapterReturning(HttpStatus.OK, """
            {"processId":"p-1","mfaRequired":true,"mfaType":"EMAIL","sessionState":null}
            """);

        var result = adapter.initiateAuth("000000", "password");

        assertThat(result.processId()).isEqualTo("p-1");
        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.sessionState()).isNull();
    }

    @Test
    void completeAuth_returnsTheSessionState() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.OK, "{\"sessionState\":\"state-blob\"}"
        );

        assertThat(adapter.completeAuth("p-1", "123456")).isEqualTo("state-blob");
    }

    @Test
    void initiateAuth_mapsBadCredentialsToInvalidCredentials() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"INVALID_CREDENTIALS\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("000000", "wrong"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.INVALID_CREDENTIALS.name())
            );
    }

    /**
     * A wrong e-mail code must read as "try again", not as Sofidy being down. A
     * 502 here would send the user to the wrong place with the wrong fix.
     */
    @Test
    void completeAuth_mapsAWrongVerificationCodeToItsOwnCode() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"MFA_INVALID\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("p-1", "000000"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.MFA_INVALID.name())
            );
    }

    @Test
    void completeAuth_mapsAnExpiredAttemptToItsOwnCode() {
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.GONE, "{\"detail\":\"AUTH_ATTEMPT_EXPIRED\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("p-1", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.AUTH_ATTEMPT_EXPIRED.name())
            );
    }

    @Test
    void completeAuth_mapsAnUncoded401ToARejectedCode() {
        // On this route the only 401 Sofidy can mean is "that code is wrong": the
        // session does not exist yet, and the sidecar sends 410 for a dead
        // attempt. A 401 read as a dead session would tell the user to reconnect
        // when the code they just typed is what needs retyping.
        SofidyAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED, "{\"detail\":\"nope\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("p-1", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.MFA_INVALID.name())
            );
    }

    @Test
    void initiateAuth_mapsAnEmptySuccessResponseToUnavailable() {
        SofidyAdapter adapter = adapterReturning(HttpStatus.OK, "");

        assertThatThrownBy(() -> adapter.initiateAuth("000000", "password"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    @Test
    void fetchSnapshot_mapsMalformedErrorJsonToUnavailable() {
        SofidyAdapter adapter = adapterReturning(HttpStatus.BAD_GATEWAY, "not-json");

        assertThatThrownBy(() -> adapter.fetchSnapshot("state"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(SofidyErrorCode.UPSTREAM_UNAVAILABLE.name())
            );
    }

    private SofidyAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new SofidyAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper(),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        );
    }
}
