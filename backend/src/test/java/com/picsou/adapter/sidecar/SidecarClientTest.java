package com.picsou.adapter.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.SidecarErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidecarClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void aDeclaredDetailKeepsItsCodeAndTheBanksWording() {
        SidecarClient client = clientReturning(SidecarBank.CORUM, HttpStatus.CONFLICT, "{\"detail\":\"MULTIPLE_CONTRACTS\"}");

        assertThatThrownBy(() -> post(client))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo("MULTIPLE_CONTRACTS");
                assertThat(error.getMessage()).isEqualTo("This CORUM login holds more than one real-estate contract");
            });
    }

    /** CORUM's table cannot store INVALID_OTP, so the client must treat it like any unknown code. */
    @Test
    void aKnownCodeTheBankNeverDeclaredIsTreatedAsUnknownAndFallsThroughOnStatus() {
        SidecarClient client = clientReturning(SidecarBank.CORUM, HttpStatus.BAD_GATEWAY, "{\"detail\":\"INVALID_OTP\"}");

        assertThatThrownBy(() -> post(client))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
                assertThat(error.getMessage()).isEqualTo("CORUM is temporarily unavailable");
            });
    }

    @Test
    void anUndeclaredCodeOnAClientErrorReportsTheCallersMessage() {
        SidecarClient client = clientReturning(SidecarBank.CORUM, HttpStatus.BAD_REQUEST, "{\"detail\":\"INVALID_OTP\"}");

        assertThatThrownBy(() -> post(client))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
                assertThat(error.getMessage()).isEqualTo("Could not call CORUM");
            });
    }

    @Test
    void aNameNoEnumConstantCarriesIsUnknownToo() {
        SidecarClient client = clientReturning(SidecarBank.AMEX, HttpStatus.BAD_GATEWAY, "{\"detail\":\"NEW_UPSTREAM_FAILURE\"}");

        assertThatThrownBy(() -> post(client))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    void aBare401MeansWhatTheCallSays() {
        SidecarClient client = clientReturning(SidecarBank.CORUM, HttpStatus.UNAUTHORIZED, "{\"detail\":\"nope\"}");

        assertThatThrownBy(() -> client.post("/x", Map.of(), Map.class, TIMEOUT, "Could not call CORUM",
            SidecarErrorCode.INVALID_CREDENTIALS))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo("INVALID_CREDENTIALS");
                assertThat(error.getMessage()).isEqualTo("CORUM rejected the credentials");
            });
    }

    @Test
    void a410MapsToTheCodeTheClientWasBuiltWith() {
        SidecarClient pending = clientReturning(SidecarBank.CAISSE_EPARGNE, HttpStatus.GONE, "", SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
        SidecarClient stored = clientReturning(SidecarBank.CAISSE_EPARGNE, HttpStatus.GONE, "", SidecarErrorCode.SESSION_EXPIRED);

        assertThatThrownBy(() -> post(pending))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("AUTH_ATTEMPT_EXPIRED"));
        assertThatThrownBy(() -> post(stored))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo("SESSION_EXPIRED"));
    }

    @Test
    void anEmptyListIsAFailureNotAnEmptyPortfolio() {
        SidecarClient client = clientReturning(SidecarBank.AMEX, HttpStatus.OK, "[]");

        assertThatThrownBy(() -> client.postForList("/x", Map.of(), String[].class, TIMEOUT, "Could not call Amex",
            SidecarErrorCode.INVALID_DATA, "Amex returned no account"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo("INVALID_DATA");
                assertThat(error.getMessage()).isEqualTo("Amex returned no account");
            });
    }

    @Test
    void aTimeoutIsUnavailableAndNamesTheBank() {
        ExchangeFunction neverResponds = request -> Mono.never();
        SidecarClient client = new SidecarClient(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(), SidecarBank.BOURSO, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);

        assertThatThrownBy(() -> client.post("/x", Map.of(), Map.class, Duration.ofMillis(20), "Could not call",
            null))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
                assertThat(error.getMessage()).isEqualTo("BoursoBank took too long to respond. Please try again.");
            });
    }

    private static Object post(SidecarClient client) {
        return client.post("/x", Map.of(), Map.class, TIMEOUT, "Could not call " + "CORUM", null);
    }

    private static SidecarClient clientReturning(SidecarBank bank, HttpStatus status, String body) {
        return clientReturning(bank, status, body, SidecarErrorCode.AUTH_ATTEMPT_EXPIRED);
    }

    private static SidecarClient clientReturning(
        SidecarBank bank, HttpStatus status, String body, SidecarErrorCode attemptExpired
    ) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new SidecarClient(
            WebClient.builder().exchangeFunction(exchange).build(), new ObjectMapper(), bank, attemptExpired);
    }
}
