package com.picsou.service;

import com.picsou.dto.ReleaseUpdate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseCheckServiceTest {

    private static final String REPO_PATH = "/repos/" + ReleaseCheckService.REPOSITORY + "/releases/latest";
    private static final String RELEASE_URL = "https://github.com/" + ReleaseCheckService.REPOSITORY + "/releases/tag/1.0.10";

    @Test
    void semver_1_0_10_isNewerThan_1_0_9() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        HttpServer server = github(hits, () -> releaseJson("1.0.10", RELEASE_URL), 200, accept, userAgent);
        try {
            ReleaseCheckService newer = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            assertThat(checked(newer, "1.0.9")).isEqualTo(new ReleaseUpdate("1.0.10", RELEASE_URL));

            ReleaseCheckService notNewer = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            assertThat(checked(notNewer, "1.0.10")).isNull();
            assertThat(checked(notNewer, "1.0.11")).isNull();
            assertThat(accept.get()).isEqualTo("application/vnd.github+json");
            assertThat(userAgent.get()).isEqualTo("picsou-finance");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void leadingV_comparesAndPassesTagThrough() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        String url = "https://github.com/" + ReleaseCheckService.REPOSITORY + "/releases/tag/v1.0.10";
        HttpServer server = github(hits, () -> releaseJson("v1.0.10", url), 200, null, null);
        try {
            ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            assertThat(checked(service, "v1.0.9")).isEqualTo(new ReleaseUpdate("v1.0.10", url));
            assertThat(checked(service, "1.0.9")).isEqualTo(new ReleaseUpdate("v1.0.10", url));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonSemverCurrent_returnsNullWithoutCalling() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = github(hits, () -> releaseJson("1.2.0", RELEASE_URL), 200, null, null);
        try {
            ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            assertThat(checked(service, "dev")).isNull();
            assertThat(checked(service, "nightly-abc1234")).isNull();
            assertThat(checked(service, "1.1.0-abc1234")).isNull();
            assertThat(hits).hasValue(0);

            assertThat(checked(service, "1.0.0")).isEqualTo(new ReleaseUpdate("1.2.0", RELEASE_URL));
            assertThat(hits).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabled_makesNoHttpCall() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = github(hits, () -> releaseJson("9.9.9", RELEASE_URL), 200, null, null);
        try {
            ReleaseCheckService service = service(server, false, Clock.systemUTC(), Duration.ofSeconds(5));
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(hits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cache_hitsWithinSixHours_andRefetchesAfter() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> tag = new AtomicReference<>("1.0.10");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        HttpServer server = github(hits, () -> releaseJson(tag.get(), RELEASE_URL), 200, null, null);
        try {
            ReleaseCheckService service = service(server, true, clock, Duration.ofSeconds(5));
            assertThat(checked(service, "1.0.9").latestVersion()).isEqualTo("1.0.10");
            tag.set("1.3.0");
            assertThat(checked(service, "1.0.9").latestVersion()).isEqualTo("1.0.10");
            clock.advance(Duration.ofHours(6).minusNanos(1));
            assertThat(checked(service, "1.0.9").latestVersion()).isEqualTo("1.0.10");
            assertThat(hits).hasValue(1);

            clock.advance(Duration.ofNanos(1));
            assertThat(checked(service, "1.0.9").latestVersion()).isEqualTo("1.3.0");
            assertThat(hits).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void github500_returnsNull_andIsCached() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        HttpServer server = github(hits, () -> "{\"message\":\"nope\"}", 500, null, null);
        try {
            ReleaseCheckService service = service(server, true, clock, Duration.ofSeconds(5));
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(hits).hasValue(1);

            clock.advance(Duration.ofHours(6));
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(hits).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void githubTimeout_returnsNull_andIsCached() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        server.createContext(REPO_PATH, exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofMillis(200));
            Instant start = Instant.now();
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(hits).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void untrustedHtmlUrl_isAFailedFetch() throws Exception {
        for (String url : new String[] {"javascript:alert(1)", "https://evil.example/releases/1"}) {
            AtomicInteger hits = new AtomicInteger();
            HttpServer server = github(hits, () -> releaseJson("1.0.10", url), 200, null, null);
            try {
                ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
                assertThat(checked(service, "1.0.0")).isNull();
                assertThat(hits).hasValue(1);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void redirectToValidRelease_isNotAnUpdate() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        int port = server.getAddress().getPort();
        byte[] payload = releaseJson("9.9.9", RELEASE_URL).getBytes(StandardCharsets.UTF_8);
        server.createContext(REPO_PATH, exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/followed");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(301, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/followed", exchange -> {
            followed.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            assertThat(checked(service, "1.0.0")).isNull();
            assertThat(followed).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentColdCache_hitsGitHubOnce() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        HttpServer server = holding(hits, inHandler, finish, releaseJson("1.0.10", RELEASE_URL));
        try {
            ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            CompletableFuture<ReleaseUpdate> first = service.checkAsync("1.0.0");
            assertThat(inHandler.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<ReleaseUpdate> second = service.checkAsync("1.0.0");
            finish.countDown();
            ReleaseUpdate expected = new ReleaseUpdate("1.0.10", RELEASE_URL);
            assertThat(first.join()).isEqualTo(expected);
            assertThat(second.join()).isEqualTo(expected);
            assertThat(hits).hasValue(1);
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }

    @Test
    void cancelledCaller_doesNotPreventCacheFill() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        HttpServer server = holding(hits, inHandler, finish, releaseJson("1.0.10", RELEASE_URL));
        try {
            ReleaseCheckService service = service(server, true, Clock.systemUTC(), Duration.ofSeconds(5));
            CompletableFuture<ReleaseUpdate> first = service.checkAsync("1.0.0");
            assertThat(inHandler.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(first.cancel(true)).isTrue();
            assertThat(first.isCancelled()).isTrue();
            finish.countDown();

            ReleaseUpdate cached = null;
            long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < end) {
                CompletableFuture<ReleaseUpdate> second = service.checkAsync("1.0.0");
                if (second.isDone() && !second.isCancelled()) {
                    cached = second.join();
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(cached).isEqualTo(new ReleaseUpdate("1.0.10", RELEASE_URL));
            assertThat(hits).hasValue(1);
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }

    private static ReleaseUpdate checked(ReleaseCheckService service, String version) {
        return service.checkAsync(version).join();
    }

    private static ReleaseCheckService service(HttpServer server, boolean enabled, Clock clock, Duration timeout) {
        return new ReleaseCheckService(
            enabled,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            clock,
            timeout,
            ReleaseCheckService.defaultWebClient(timeout));
    }

    private static String releaseJson(String tag, String url) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"" + url + "\"}";
    }

    private static HttpServer github(AtomicInteger hits, Supplier<String> body, int status,
                                     AtomicReference<String> accept, AtomicReference<String> userAgent) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        server.createContext(REPO_PATH, exchange -> {
            hits.incrementAndGet();
            if (accept != null) {
                accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            }
            if (userAgent != null) {
                userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            }
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer holding(AtomicInteger hits, CountDownLatch inHandler, CountDownLatch finish,
                                      String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        server.createContext(REPO_PATH, exchange -> {
            hits.incrementAndGet();
            inHandler.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static ExecutorService daemonPool() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "release-check-stub");
            thread.setDaemon(true);
            return thread;
        });
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
