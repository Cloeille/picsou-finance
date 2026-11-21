package com.picsou.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PerIpRateLimitTest {

    private static Bucket oneAnHour() {
        return Bucket.builder().addLimit(Bandwidth.builder().capacity(1).refillGreedy(1, Duration.ofHours(1)).build()).build();
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void eachClientAddressHasItsOwnBucket() {
        Map<String, Bucket> buckets = new HashMap<>();

        assertThat(PerIpRateLimit.tryConsume(buckets, from("192.0.2.1"), PerIpRateLimitTest::oneAnHour)).isTrue();
        assertThat(PerIpRateLimit.tryConsume(buckets, from("192.0.2.1"), PerIpRateLimitTest::oneAnHour)).isFalse();
        assertThat(PerIpRateLimit.tryConsume(buckets, from("192.0.2.2"), PerIpRateLimitTest::oneAnHour)).isTrue();
        assertThat(buckets).containsOnlyKeys("192.0.2.1", "192.0.2.2");
    }

    @Test
    void aRefusalIsA429ProblemWithTheWaitInWholeSeconds() {
        Map<String, Bucket> buckets = new HashMap<>();
        PerIpRateLimit.consumeProbe(buckets, from("192.0.2.1"), PerIpRateLimitTest::oneAnHour);
        ConsumptionProbe refused = PerIpRateLimit.consumeProbe(buckets, from("192.0.2.1"), PerIpRateLimitTest::oneAnHour);

        ResponseEntity<ProblemDetail> withWait = PerIpRateLimit.tooManyRequests(refused, "Slow down.");
        ResponseEntity<ProblemDetail> plain = PerIpRateLimit.tooManyRequests("Slow down.");

        assertThat(refused.isConsumed()).isFalse();
        assertThat(withWait.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(withWait.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(3599L, 3600L);
        assertThat(withWait.getBody().getDetail()).isEqualTo("Slow down.");
        assertThat(plain.getStatusCode().value()).isEqualTo(429);
        assertThat(plain.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(plain.getBody().getDetail()).isEqualTo("Slow down.");
    }
}
