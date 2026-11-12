package com.picsou.adapter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoundedUtf8BodyTest {

    @Test
    void bodyWithinTheCap_isReturnedAsUtf8() throws Exception {
        BodySubscriber<String> subscriber = BoundedUtf8Body.handler(100).apply(responseInfo(2));
        subscriber.onSubscribe(mock(Flow.Subscription.class));

        subscriber.onNext(List.of(ByteBuffer.wrap("é".getBytes(StandardCharsets.UTF_8))));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().get()).isEqualTo("é");
    }

    @Test
    void advertisedLengthOverTheCap_cancelsBeforeReading() {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        BodySubscriber<String> subscriber = BoundedUtf8Body.handler(100).apply(responseInfo(101));

        subscriber.onSubscribe(subscription);

        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
            .hasMessageContaining("response too large");
    }

    @Test
    void streamPastTheCapDespiteALyingLength_cancelsAndFails() {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        BodySubscriber<String> subscriber = BoundedUtf8Body.handler(100).apply(responseInfo(-1));
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[60])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[60])));

        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().get())
            .hasMessageContaining("response too large");
    }

    @Test
    void isTooLarge_recognisesTheCapFailureThroughWrappers() {
        BodySubscriber<String> subscriber = BoundedUtf8Body.handler(1).apply(responseInfo(2));
        subscriber.onSubscribe(mock(Flow.Subscription.class));

        Throwable failure = catchFailure(subscriber);

        assertThat(BoundedUtf8Body.isTooLarge(failure)).isTrue();
        assertThat(BoundedUtf8Body.isTooLarge(new IOException("connection reset"))).isFalse();
    }

    private static Throwable catchFailure(BodySubscriber<String> subscriber) {
        try {
            subscriber.getBody().toCompletableFuture().get();
            throw new AssertionError("expected the body to fail");
        } catch (ExecutionException ex) {
            return ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static ResponseInfo responseInfo(long contentLength) {
        Map<String, List<String>> headers = contentLength < 0
            ? Map.of() : Map.of("Content-Length", List.of(Long.toString(contentLength)));
        ResponseInfo info = mock(ResponseInfo.class);
        when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return info;
    }
}
