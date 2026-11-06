package com.picsou.support;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link WebClient}s that answer from canned JSON, so an adapter test never hand-rolls an
 * {@link ExchangeFunction}. Every response carries a JSON content type.
 */
public final class WebClientStubs {

    private WebClientStubs() {
    }

    /** One request seen by a {@link #recording} client: where it went and what it carried. */
    public record Call(String path, String body) {
    }

    /** Answers every request with {@code status} and {@code json}. */
    public static WebClient returning(HttpStatus status, String json) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(response(status, json))).build();
    }

    /** Like {@link #returning(HttpStatus, String)}, with one more response header (a {@code Retry-After}, say). */
    public static WebClient returning(HttpStatus status, String header, String value, String json) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header(header, value)
            .body(json)
            .build())).build();
    }

    /** Answers every request with 200 and {@code json}. */
    public static WebClient returning(String json) {
        return returning(HttpStatus.OK, json);
    }

    /** Fails every request with {@code error}, as a connection reset or a timeout would. */
    public static WebClient failing(Throwable error) {
        return WebClient.builder().exchangeFunction(request -> Mono.error(error)).build();
    }

    /** Never answers, so the caller's own timeout is what ends the call. */
    public static WebClient neverResponding() {
        return WebClient.builder().exchangeFunction(request -> Mono.never()).build();
    }

    /**
     * Answers the n-th request with 200 and the n-th body, whatever the URL. Asking for more
     * requests than there are bodies fails the test rather than inventing an answer.
     */
    public static WebClient sequence(String... jsonInOrder) {
        AtomicInteger next = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            int index = next.getAndIncrement();
            if (index >= jsonInOrder.length) {
                return Mono.error(new AssertionError(
                    "request " + (index + 1) + " to " + request.url() + " but only " + jsonInOrder.length + " answers"));
            }
            return Mono.just(response(HttpStatus.OK, jsonInOrder[index]));
        };
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    /** Answers every request with {@code status} and {@code json}, handing each request it saw to {@code sink}. */
    public static WebClient recording(Consumer<Call> sink, HttpStatus status, String json) {
        return WebClient.builder().exchangeFunction(request -> {
            MockClientHttpRequest written = new MockClientHttpRequest(request.method(), request.url());
            request.writeTo(written, ExchangeStrategies.withDefaults()).block();
            sink.accept(new Call(request.url().getPath(), written.getBodyAsString().block()));
            return Mono.just(response(status, json));
        }).build();
    }

    /** Like {@link #recording(Consumer, HttpStatus, String)}, appending each request to {@code calls}. */
    public static WebClient recording(List<Call> calls, HttpStatus status, String json) {
        return recording(calls::add, status, json);
    }

    /** Like {@link #recording(Consumer, HttpStatus, String)}, appending each request body to {@code bodies}. */
    public static WebClient recordingBodies(List<String> bodies, HttpStatus status, String json) {
        return recording(call -> bodies.add(call.body()), status, json);
    }

    private static ClientResponse response(HttpStatus status, String json) {
        return ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(json)
            .build();
    }
}
