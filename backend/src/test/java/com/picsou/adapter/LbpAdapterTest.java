package com.picsou.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.exception.SyncException;
import com.picsou.port.LbpErrorCode;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The login half of the sidecar contract: {@code /initiate}, {@code /keypad}, {@code /otp}.
 * One attempt only: a wrong password or code burns a bank attempt, so nothing here may replay.
 */
class LbpAdapterTest {

    private static final String CUSTOMER_ID = "1234567890";
    private static final List<Integer> POSITIONS = List.of(7, 3, 0, 9, 4, 1);
    private static final String IMAGE = "data:image/png;base64,iVBORw0KGgo=";
    private static final String INITIATE_OK = initiateJson("proc-1", 5, 90);
    private static final String KEYPAD_OK = "{\"processId\":\"proc-1\",\"status\":\"OTP_PENDING\"}";
    private static final String OTP_OK = "{\"sessionState\":\"opaque-cookie-jar\"}";

    private static String initiateJson(String processId, int columns, int expiresIn) {
        String images = String.join(",", Collections.nCopies(10, "\"" + IMAGE + "\""));
        return "{\"processId\":\"" + processId + "\",\"keypad\":{\"images\":[" + images
            + "],\"columns\":" + columns + "},\"expiresInSeconds\":" + expiresIn + "}";
    }

    private record Call(String path, String body) {}

    @Test
    void initiateAuth_postsOnlyTheIdentifierAndMapsTheKeypad() {
        List<Call> calls = new ArrayList<>();
        LbpAdapter adapter = recording(calls, HttpStatus.OK, INITIATE_OK);

        LbpAdapter.InitiateResult result = adapter.initiateAuth(CUSTOMER_ID);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/initiate");
            assertThat(call.body()).isEqualTo("{\"customerId\":\"" + CUSTOMER_ID + "\"}");
        });
        assertThat(result.processId()).isEqualTo("proc-1");
        assertThat(result.keypad().columns()).isEqualTo(5);
        assertThat(result.keypad().images()).hasSize(10).allMatch(IMAGE::equals);
        assertThat(result.expiresInSeconds()).isEqualTo(90);
    }

    @Test
    void submitKeypad_postsTheProcessIdAndPositionsAndExpectsOtpPending() {
        List<Call> calls = new ArrayList<>();
        LbpAdapter adapter = recording(calls, HttpStatus.OK, KEYPAD_OK);

        adapter.submitKeypad("proc-1", POSITIONS);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/keypad");
            assertThat(call.body()).isEqualTo("{\"processId\":\"proc-1\",\"positions\":[7,3,0,9,4,1]}");
        });
    }

    @Test
    void submitKeypad_refusesAnAnswerThatIsNotOtpPending() {
        for (String body : List.of("{\"processId\":\"proc-1\",\"status\":\"DONE\"}",
            "{\"processId\":\"proc-1\"}", "")) {
            LbpAdapter adapter = returning(HttpStatus.OK, body);

            assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS))
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isIn(
                        LbpErrorCode.UPSTREAM_FORMAT_CHANGED.name(),
                        LbpErrorCode.UPSTREAM_UNAVAILABLE.name()));
        }
    }

    @Test
    void submitOtp_postsTheProcessIdAndCodeAndMapsTheSession() {
        List<Call> calls = new ArrayList<>();
        LbpAdapter adapter = recording(calls, HttpStatus.OK, OTP_OK);

        assertThat(adapter.submitOtp("proc-1", "123456")).isEqualTo("opaque-cookie-jar");

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.path()).isEqualTo("/otp");
            assertThat(call.body()).isEqualTo("{\"processId\":\"proc-1\",\"code\":\"123456\"}");
        });
    }

    @Test
    void initiateAuth_mapsEverySidecarCodeToItsStableCode() {
        record Case(HttpStatus status, String detail, LbpErrorCode code) {}
        for (Case c : List.of(
            new Case(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", LbpErrorCode.INVALID_CREDENTIALS),
            new Case(HttpStatus.CONFLICT, "KEYPAD_CHANGED", LbpErrorCode.KEYPAD_CHANGED),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE", LbpErrorCode.UPSTREAM_UNAVAILABLE),
            new Case(HttpStatus.BAD_GATEWAY, "UPSTREAM_FORMAT_CHANGED", LbpErrorCode.UPSTREAM_FORMAT_CHANGED))) {
            LbpAdapter adapter = returning(c.status(), "{\"detail\":\"" + c.detail() + "\"}");

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
                .as(c.detail())
                .isInstanceOfSatisfying(SyncException.class, error ->
                    assertThat(error.getCode()).isEqualTo(c.code().name()));
        }
    }

    @Test
    void otpMaps408ToExpiredAnd422ToInvalid() {
        assertThatThrownBy(() -> returning(HttpStatus.REQUEST_TIMEOUT, "").submitOtp("proc-1", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(LbpErrorCode.OTP_EXPIRED.name()));
        assertThatThrownBy(() -> returning(HttpStatus.UNPROCESSABLE_ENTITY, "").submitOtp("proc-1", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(LbpErrorCode.OTP_INVALID.name()));
        assertThatThrownBy(() -> returning(HttpStatus.UNAUTHORIZED, "").submitOtp("proc-1", "123456"))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(LbpErrorCode.OTP_INVALID.name()));
    }

    @Test
    void initiateStillMaps408AsAnApprovalTimeout() {
        assertThatThrownBy(() -> returning(HttpStatus.REQUEST_TIMEOUT, "").initiateAuth(CUSTOMER_ID))
            .isInstanceOfSatisfying(SyncException.class, error ->
                assertThat(error.getCode()).isEqualTo(LbpErrorCode.APP_VALIDATION_TIMEOUT.name()));
    }

    @Test
    void everyLoginCallIsNeverRetried() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.REQUEST_TIMEOUT,
            HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_GATEWAY)) {
            AtomicInteger requests = new AtomicInteger();
            LbpAdapter adapter = adapterOf(request -> {
                requests.incrementAndGet();
                return Mono.just(ClientResponse.create(status).build());
            });

            assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID)).isInstanceOf(SyncException.class);
            assertThatThrownBy(() -> adapter.submitKeypad("proc-1", POSITIONS)).isInstanceOf(SyncException.class);
            assertThatThrownBy(() -> adapter.submitOtp("proc-1", "123456")).isInstanceOf(SyncException.class);

            assertThat(requests).as(status.toString()).hasValue(3);
        }
    }

    @Test
    void theAuthCallsHaveTheirOwnTimeouts() {
        assertThat(LbpAdapter.INITIATE_TIMEOUT).isEqualTo(Duration.ofSeconds(90));
        assertThat(LbpAdapter.KEYPAD_TIMEOUT).isEqualTo(Duration.ofSeconds(70));
        assertThat(LbpAdapter.OTP_TIMEOUT).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void aTimeoutBecomesARetryableUnavailable() {
        LbpAdapter adapter = new LbpAdapter(
            WebClient.builder().exchangeFunction(request -> Mono.never()).build(), new ObjectMapper(),
            Duration.ofSeconds(60), Duration.ofSeconds(60),
            Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(20));

        assertThatThrownBy(() -> adapter.initiateAuth(CUSTOMER_ID))
            .isInstanceOfSatisfying(SyncException.class, error -> {
                assertThat(error.getCode()).isEqualTo(LbpErrorCode.UPSTREAM_UNAVAILABLE.name());
                assertThat(error.getMessage()).contains("too long").doesNotContain(CUSTOMER_ID);
            });
        assertThatThrownBy(() -> adapter.submitOtp("proc-1", "123456")).isInstanceOf(SyncException.class);
    }

    @Test
    void theIdentifierThePositionsAndTheCodeNeverReachToString() throws Exception {
        assertRedacted("InitiateBody", CUSTOMER_ID);
        assertRedacted("KeypadBody", "7,3,0,9,4,1");
        assertRedacted("OtpBody", "123456");
    }

    private static void assertRedacted(String simpleName, String secret) throws Exception {
        Class<?> type = java.util.Arrays.stream(LbpAdapter.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals(simpleName)).findFirst().orElseThrow();
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object instance;
        if (simpleName.equals("InitiateBody")) {
            instance = constructor.newInstance((Object) secret);
        } else if (simpleName.equals("KeypadBody")) {
            instance = constructor.newInstance("proc-1", POSITIONS);
        } else {
            instance = constructor.newInstance("proc-1", secret);
        }
        assertThat(instance.toString()).doesNotContain(secret);
    }

    private static LbpAdapter adapterOf(ExchangeFunction exchange) {
        return new LbpAdapter(WebClient.builder().exchangeFunction(exchange).build(), new ObjectMapper());
    }

    private static LbpAdapter returning(HttpStatus status, String body) {
        return adapterOf(request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build()));
    }

    private static LbpAdapter recording(List<Call> calls, HttpStatus status, String responseBody) {
        return adapterOf(request -> {
            MockClientHttpRequest recorder = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(recorder, ExchangeStrategies.withDefaults()).block();
            calls.add(new Call(request.url().getPath(), recorder.getBodyAsString().block()));
            return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(responseBody)
                .build());
        });
    }
}
