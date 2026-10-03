package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.AmexErrorCode;
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

class AmexAdapterTest {

    @Test
    void initiateAuth_sendsTheChosenOtpMethod() {
        List<String> bodies = new ArrayList<>();
        AmexAdapter adapter = adapterRecording(bodies, """
            {"processId":"p-1","mfaRequired":true,"mfaType":"OTP","sessionState":null}
            """);

        var result = adapter.initiateAuth("user", "secret", "email");

        assertThat(result.mfaRequired()).isTrue();
        assertThat(result.mfaType()).isEqualTo("OTP");
        assertThat(bodies).singleElement().satisfies(body ->
            assertThat(body).contains("\"method\":\"email\"")
        );
    }

    @Test
    void initiateAuth_mapsAnUncoded401ToInvalidCredentials() {
        AmexAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"Authentication rejected\"}"
        );

        assertThatThrownBy(() -> adapter.initiateAuth("user", "secret", "sms"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmexErrorCode.INVALID_CREDENTIALS.name())
            );
    }

    @Test
    void completeAuth_sendsTheOtpField() {
        List<String> bodies = new ArrayList<>();
        AmexAdapter adapter = adapterRecording(bodies, """
            {"sessionState":"cookies"}
            """);

        assertThat(adapter.completeAuth("process-1", "123456")).isEqualTo("cookies");
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body).contains("\"processId\":\"process-1\"");
            assertThat(body).contains("\"otp\":\"123456\"");
        });
    }

    @Test
    void completeAuth_mapsA401ToInvalidOtp() {
        AmexAdapter adapter = adapterReturning(
            HttpStatus.UNAUTHORIZED,
            "{\"detail\":\"INVALID_OTP\"}"
        );

        assertThatThrownBy(() -> adapter.completeAuth("process", "000000"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmexErrorCode.INVALID_OTP.name())
            );
    }

    @Test
    void completeAuth_mapsAnExpiredAttemptFromItsStatusAlone() {
        AmexAdapter adapter = adapterReturning(HttpStatus.GONE, "{}");

        assertThatThrownBy(() -> adapter.completeAuth("process", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmexErrorCode.AUTH_ATTEMPT_EXPIRED.name())
            );
    }

    @Test
    void fetchAccounts_mapsTheStrictSidecarContract() {
        AmexAdapter adapter = adapterReturning(HttpStatus.OK, """
            [{
              "externalId":"amex_e2f509c466f5294f15abd873dbbf8a62",
              "name":"American Express",
              "type":"CREDIT_CARD",
              "balanceEur":-1250.75,
              "statementBalance":-1250.75,
              "paymentDueDate":"2025-11-10",
              "minimumPayment":-50.00,
              "rewardsPoints":12000,
              "directDebit":null,
              "transactions":[
                {"date":"2025-11-01","description":"Grocer","amount":-30.00,"status":"pending","category":null}
              ],
              "pendingComplete":true,
              "snapshotComplete":true
            }]
            """);

        var accounts = adapter.fetchAccounts("encrypted-cookies");

        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0)).satisfies(account -> {
            assertThat(account.balanceEur()).isEqualByComparingTo("-1250.75");
            assertThat(account.pendingComplete()).isTrue();
            assertThat(account.snapshotComplete()).isTrue();
            assertThat(account.transactions()).singleElement().satisfies(tx -> {
                assertThat(tx.label()).isEqualTo("Grocer");
                assertThat(tx.pending()).isTrue();
            });
        });
    }

    @Test
    void fetchAccounts_treatsAnEmptyArrayAsAnIncompleteRead() {
        AmexAdapter adapter = adapterReturning(HttpStatus.OK, "[]");

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(AmexErrorCode.INVALID_DATA.name())
            );
    }

    @Test
    void fetchAccounts_mapsNetworkTimeoutToAnExplicitRetryableFailure() {
        ExchangeFunction neverResponds = request -> Mono.never();
        AmexAdapter adapter = new AmexAdapter(
            WebClient.builder().exchangeFunction(neverResponds).build(),
            new ObjectMapper(),
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(20)
        );

        assertThatThrownBy(() -> adapter.fetchAccounts("cookies"))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(AmexErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long");
            });
    }

    private AmexAdapter adapterReturning(HttpStatus status, String body) {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build());
        return new AmexAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }

    private AmexAdapter adapterRecording(List<String> bodies, String responseBody) {
        ExchangeFunction exchange = request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            bodies.add(recorder.getBodyAsString().block());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        };
        return new AmexAdapter(
            WebClient.builder().exchangeFunction(exchange).build(),
            new ObjectMapper()
        );
    }
}
