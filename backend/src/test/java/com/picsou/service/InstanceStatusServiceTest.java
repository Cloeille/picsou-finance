package com.picsou.service;

import com.picsou.dto.SidecarIssue;
import com.picsou.dto.SidecarIssueCode;
import com.picsou.repository.AmexSessionRepository;
import com.picsou.repository.AmundiSessionRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.BourseDirectSessionRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FortuneoSessionRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.SofidySessionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstanceStatusServiceTest {

    @Test
    void matchingBase_isNotAnIssue() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 200, "{\"status\":\"ok\",\"base\":\"sidecar-python\"}");
        try {
            var response = service(target("bourso-auth", "sidecar-python", url(server), 1)).status();
            assertThat(hits).hasValue(1);
            assertThat(response.issues()).isEmpty();
            assertThat(response.currentVersion()).isEqualTo("1.1.0");
            assertThat(response.update()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingBase_isOutdated() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 200, "{\"status\":\"ok\"}");
        try {
            var response = service(target("bourso-auth", "sidecar-python", url(server), 1)).status();
            assertThat(hits).hasValue(1);
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_OUTDATED, "bourso-auth", "sidecar-python"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void differentBase_isOutdated() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 200, "{\"status\":\"ok\",\"base\":\"sidecar-chromium\"}");
        try {
            var response = service(target("degiro-auth", "sidecar-python", url(server), 1)).status();
            assertThat(hits).hasValue(1);
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_OUTDATED, "degiro-auth", "sidecar-python"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void amexWithBase_isNotAnIssue() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 200, "{\"status\":\"ok\",\"base\":\"sidecar-chromium\"}");
        try {
            var response = service(target("amex-auth", null, url(server), 1)).status();
            assertThat(hits).hasValue(1);
            assertThat(response.issues()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusedPort_isUnreachable() throws Exception {
        var response = service(target("corum-auth", "sidecar-chromium", refusedUrl(), 1)).status();
        assertThat(response.issues()).containsExactly(
            new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "corum-auth", "sidecar-chromium"));
    }

    @Test
    void non2xx_isUnreachable() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 500, "{\"status\":\"down\"}");
        try {
            var response = service(target("tr-auth", "sidecar-chromium", url(server), 1)).status();
            assertThat(hits).hasValue(1);
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "tr-auth", "sidecar-chromium"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unusedSidecar_isNotProbed() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 500, "{}");
        try {
            var response = service(target("sofidy-auth", "sidecar-python", url(server), 0)).status();
            assertThat(hits).hasValue(0);
            assertThat(response.issues()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void issues_areSortedBySidecarName() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = health(hits, 200, "{\"status\":\"ok\"}");
        try {
            var response = service(
                target("corum-auth", "sidecar-chromium", refusedUrl(), 1),
                target("bourso-auth", "sidecar-python", url(server), 1)
            ).status();
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_OUTDATED, "bourso-auth", "sidecar-python"),
                new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "corum-auth", "sidecar-chromium"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inUseSidecars_areProbedInParallel() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        server.createContext("/health", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            byte[] payload = "{\"status\":\"ok\",\"base\":\"sidecar-python\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            String base = url(server);
            var response = service(
                target("bourso-auth", "sidecar-python", base, 1),
                target("sofidy-auth", "sidecar-python", base, 1)
            ).status();
            assertThat(maxInFlight.get()).isGreaterThanOrEqualTo(2);
            assertThat(response.issues()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registry_encodesNameBaseUrlAndSessionCount() {
        SetupService setup = mock(SetupService.class);
        when(setup.readSetting(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.of("http://setup-bourso"));
        var tr = mock(TradeRepublicSessionRepository.class);
        var bourso = mock(BoursoSessionRepository.class);
        var caisse = mock(CaisseEpargneSessionRepository.class);
        var revolut = mock(RevolutSessionRepository.class);
        var bourse = mock(BourseDirectSessionRepository.class);
        var degiro = mock(DegiroSessionRepository.class);
        var amundi = mock(AmundiSessionRepository.class);
        var amex = mock(AmexSessionRepository.class);
        var fortuneo = mock(FortuneoSessionRepository.class);
        var corum = mock(CorumSessionRepository.class);
        var sofidy = mock(SofidySessionRepository.class);
        when(tr.count()).thenReturn(1L);
        when(bourso.count()).thenReturn(2L);
        when(caisse.count()).thenReturn(3L);
        when(revolut.count()).thenReturn(4L);
        when(bourse.count()).thenReturn(5L);
        when(degiro.count()).thenReturn(6L);
        when(amundi.count()).thenReturn(7L);
        when(amex.count()).thenReturn(8L);
        when(fortuneo.count()).thenReturn(9L);
        when(corum.count()).thenReturn(10L);
        when(sofidy.count()).thenReturn(11L);

        InstanceStatusService wired = new InstanceStatusService(
            disabledReleases(),
            setup,
            tr, bourso, caisse, revolut, bourse, degiro, amundi, amex, fortuneo, corum, sofidy,
            "1.1.0",
            "http://tr", "http://property-bourso", "http://caisse", "http://revolut", "http://bourse",
            "http://degiro", "http://amundi", "http://amex", "http://fortuneo", "http://corum", "http://sofidy");

        assertThat(wired.targets())
            .extracting(SidecarTarget::name, SidecarTarget::expectedBase)
            .containsExactly(
                tuple("bourso-auth", "sidecar-python"),
                tuple("sofidy-auth", "sidecar-python"),
                tuple("degiro-auth", "sidecar-python"),
                tuple("tr-auth", "sidecar-chromium"),
                tuple("caisse-epargne-auth", "sidecar-chromium"),
                tuple("bourse-direct-auth", "sidecar-chromium"),
                tuple("amundi-auth", "sidecar-chromium"),
                tuple("fortuneo-auth", "sidecar-chromium"),
                tuple("corum-auth", "sidecar-chromium"),
                tuple("amex-auth", null),
                tuple("revolut-auth", null));

        assertThat(byName(wired, "bourso-auth").url().get()).isEqualTo("http://setup-bourso");
        when(setup.readSetting(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.of("  "));
        assertThat(byName(wired, "bourso-auth").url().get()).isEqualTo("http://property-bourso");
        when(setup.readSetting(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.empty());
        assertThat(byName(wired, "bourso-auth").url().get()).isEqualTo("http://property-bourso");

        assertThat(byName(wired, "tr-auth").url().get()).isEqualTo("http://tr");
        assertThat(byName(wired, "sofidy-auth").url().get()).isEqualTo("http://sofidy");
        assertThat(byName(wired, "degiro-auth").url().get()).isEqualTo("http://degiro");
        assertThat(byName(wired, "caisse-epargne-auth").url().get()).isEqualTo("http://caisse");
        assertThat(byName(wired, "revolut-auth").url().get()).isEqualTo("http://revolut");
        assertThat(byName(wired, "bourse-direct-auth").url().get()).isEqualTo("http://bourse");
        assertThat(byName(wired, "amundi-auth").url().get()).isEqualTo("http://amundi");
        assertThat(byName(wired, "fortuneo-auth").url().get()).isEqualTo("http://fortuneo");
        assertThat(byName(wired, "corum-auth").url().get()).isEqualTo("http://corum");
        assertThat(byName(wired, "amex-auth").url().get()).isEqualTo("http://amex");

        assertThat(byName(wired, "tr-auth").sessions().getAsLong()).isEqualTo(1L);
        assertThat(byName(wired, "bourso-auth").sessions().getAsLong()).isEqualTo(2L);
        assertThat(byName(wired, "caisse-epargne-auth").sessions().getAsLong()).isEqualTo(3L);
        assertThat(byName(wired, "revolut-auth").sessions().getAsLong()).isEqualTo(4L);
        assertThat(byName(wired, "bourse-direct-auth").sessions().getAsLong()).isEqualTo(5L);
        assertThat(byName(wired, "degiro-auth").sessions().getAsLong()).isEqualTo(6L);
        assertThat(byName(wired, "amundi-auth").sessions().getAsLong()).isEqualTo(7L);
        assertThat(byName(wired, "amex-auth").sessions().getAsLong()).isEqualTo(8L);
        assertThat(byName(wired, "fortuneo-auth").sessions().getAsLong()).isEqualTo(9L);
        assertThat(byName(wired, "corum-auth").sessions().getAsLong()).isEqualTo(10L);
        assertThat(byName(wired, "sofidy-auth").sessions().getAsLong()).isEqualTo(11L);
    }

    private static SidecarTarget byName(InstanceStatusService service, String name) {
        return service.targets().stream().filter(target -> target.name().equals(name)).findFirst().orElseThrow();
    }

    private static InstanceStatusService service(SidecarTarget... targets) {
        return new InstanceStatusService(disabledReleases(), "1.1.0", List.of(targets), WebClient.builder().build());
    }

    private static ReleaseCheckService disabledReleases() {
        return new ReleaseCheckService(false, "http://127.0.0.1:9", "Cloeille/picsou-finance",
            Clock.systemUTC(), Duration.ofSeconds(1), WebClient.builder().build());
    }

    private static SidecarTarget target(String name, String expectedBase, String url, long sessions) {
        return new SidecarTarget(name, expectedBase, () -> url, () -> sessions);
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String refusedUrl() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static HttpServer health(AtomicInteger hits, int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        server.createContext("/health", exchange -> {
            hits.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static ExecutorService daemonPool() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "instance-status-stub");
            thread.setDaemon(true);
            return thread;
        });
    }
}
