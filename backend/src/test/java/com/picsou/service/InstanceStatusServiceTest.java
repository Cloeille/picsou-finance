package com.picsou.service;

import com.picsou.dto.SidecarIssue;
import com.picsou.dto.SidecarIssueCode;
import com.picsou.model.AppSetting;
import com.picsou.repository.AmexSessionRepository;
import com.picsou.repository.AppSettingRepository;
import com.picsou.repository.AppUserRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.AmundiSessionRepository;
import com.picsou.repository.BoursoSessionRepository;
import com.picsou.repository.BourseDirectSessionRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.CmesSessionRepository;
import com.picsou.repository.CorumSessionRepository;
import com.picsou.repository.DegiroSessionRepository;
import com.picsou.repository.FortuneoSessionRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.SofidySessionRepository;
import com.picsou.repository.TradeRepublicSessionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    void probeTimeout_isUnreachableAndBounded() throws Exception {
        HttpServer server = hang("/health", 10_000);
        try {
            Duration probe = Duration.ofMillis(200);
            InstantStart start = InstantStart.now();
            var response = service(probe, Duration.ofSeconds(5),
                target("tr-auth", "sidecar-chromium", url(server), 1)).status();
            // 5s overall deadline: under 3s means the per-probe timeout won, not the deadline.
            assertThat(start.elapsed()).isLessThan(Duration.ofSeconds(3));
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "tr-auth", "sidecar-chromium"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirect_isUnreachable() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        int port = server.getAddress().getPort();
        byte[] healthy = "{\"status\":\"ok\",\"base\":\"sidecar-python\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/healthy");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(301, healthy.length);
            exchange.getResponseBody().write(healthy);
            exchange.close();
        });
        server.createContext("/healthy", exchange -> {
            followed.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, healthy.length);
            exchange.getResponseBody().write(healthy);
            exchange.close();
        });
        server.start();
        try {
            var response = service(target("bourso-auth", "sidecar-python", url(server), 1)).status();
            assertThat(followed).hasValue(0);
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "bourso-auth", "sidecar-python"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hangingReleaseAndSidecars_returnWithinOverallDeadline() throws Exception {
        HttpServer github = hang("/repos/" + ReleaseCheckService.REPOSITORY + "/releases/latest", 10_000);
        HttpServer sidecars = hang("/health", 10_000);
        try {
            Duration releaseTimeout = Duration.ofSeconds(5);
            ReleaseCheckService releases = new ReleaseCheckService(
                true,
                url(github),
                Clock.systemUTC(),
                releaseTimeout,
                ReleaseCheckService.defaultWebClient(releaseTimeout));
            InstanceStatusService service = new InstanceStatusService(
                releases,
                "1.0.0",
                List.of(
                    target("bourso-auth", "sidecar-python", url(sidecars), 1),
                    target("sofidy-auth", "sidecar-python", url(sidecars), 1)),
                InstanceStatusService.defaultWebClient(InstanceStatusService.PROBE_TIMEOUT),
                InstanceStatusService.PROBE_TIMEOUT,
                InstanceStatusService.OVERALL_DEADLINE);
            InstantStart start = InstantStart.now();
            var response = service.status();
            assertThat(start.elapsed()).isLessThan(Duration.ofMillis(3500));
            assertThat(response.update()).isNull();
            assertThat(response.issues()).containsExactly(
                new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "bourso-auth", "sidecar-python"),
                new SidecarIssue(SidecarIssueCode.SIDECAR_UNREACHABLE, "sofidy-auth", "sidecar-python"));
        } finally {
            github.stop(0);
            sidecars.stop(0);
        }
    }

    @Test
    void registry_encodesNameBaseUrlAndSessionCount() {
        AppSettingRepository settings = mock(AppSettingRepository.class);
        when(settings.findByKey(SetupService.KEY_BOURSO_AUTH_URL))
            .thenReturn(Optional.of(setting("http://setup-bourso")));
        SetupService setup = new SetupService(
            settings, mock(AppUserRepository.class), mock(FamilyMemberRepository.class), mock(PasswordEncoder.class));
        MockEnvironment env = new MockEnvironment()
            .withProperty("app.bourso-auth.url", "http://property-bourso")
            .withProperty("app.sofidy-auth.url", "http://sofidy")
            .withProperty("app.cmes-auth.url", "http://cmes")
            .withProperty("app.degiro-auth.url", "http://degiro")
            .withProperty("app.tr-auth.url", "http://tr")
            .withProperty("app.caisse-epargne-auth.url", "http://caisse")
            .withProperty("app.bourse-direct-auth.url", "http://bourse")
            .withProperty("app.amundi-auth.url", "http://amundi")
            .withProperty("app.fortuneo-auth.url", "http://fortuneo")
            .withProperty("app.corum-auth.url", "http://corum")
            .withProperty("app.amex-auth.url", "http://amex")
            .withProperty("app.revolut-auth.url", "http://revolut");
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
        var cmes = mock(CmesSessionRepository.class);
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
        when(cmes.count()).thenReturn(12L);

        InstanceStatusService wired = new InstanceStatusService(
            disabledReleases(),
            setup,
            env,
            tr, bourso, caisse, revolut, bourse, degiro, amundi, amex, fortuneo, corum, sofidy, cmes,
            "1.1.0");

        assertThat(wired.targets())
            .extracting(SidecarTarget::name, SidecarTarget::expectedBase)
            .containsExactly(
                tuple("bourso-auth", "sidecar-python"),
                tuple("sofidy-auth", "sidecar-python"),
                tuple("cmes-auth", "sidecar-python"),
                tuple("degiro-auth", "sidecar-python"),
                tuple("tr-auth", "sidecar-chromium"),
                tuple("caisse-epargne-auth", "sidecar-chromium"),
                tuple("bourse-direct-auth", "sidecar-chromium"),
                tuple("amundi-auth", "sidecar-chromium"),
                tuple("fortuneo-auth", "sidecar-chromium"),
                tuple("corum-auth", "sidecar-chromium"),
                tuple("amex-auth", "sidecar-camoufox"),
                tuple("revolut-auth", "sidecar-camoufox"));

        assertThat(byName(wired, "bourso-auth").url().get()).isEqualTo("http://setup-bourso");
        when(settings.findByKey(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.of(setting("  ")));
        assertThat(byName(wired, "bourso-auth").url().get()).isEqualTo("http://property-bourso");
        when(settings.findByKey(SetupService.KEY_BOURSO_AUTH_URL)).thenReturn(Optional.empty());
        assertThat(byName(wired, "bourso-auth").url().get()).isEqualTo("http://property-bourso");

        assertThat(byName(wired, "tr-auth").url().get()).isEqualTo("http://tr");
        assertThat(byName(wired, "sofidy-auth").url().get()).isEqualTo("http://sofidy");
        assertThat(byName(wired, "cmes-auth").url().get()).isEqualTo("http://cmes");
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
        assertThat(byName(wired, "cmes-auth").sessions().getAsLong()).isEqualTo(12L);
    }

    private static AppSetting setting(String value) {
        return AppSetting.builder().key(SetupService.KEY_BOURSO_AUTH_URL).value(value).build();
    }

    private static SidecarTarget byName(InstanceStatusService service, String name) {
        return service.targets().stream().filter(target -> target.name().equals(name)).findFirst().orElseThrow();
    }

    private static InstanceStatusService service(SidecarTarget... targets) {
        return service(InstanceStatusService.PROBE_TIMEOUT, InstanceStatusService.OVERALL_DEADLINE, targets);
    }

    private static InstanceStatusService service(Duration probeTimeout, Duration overall, SidecarTarget... targets) {
        return new InstanceStatusService(
            disabledReleases(),
            "1.1.0",
            List.of(targets),
            InstanceStatusService.defaultWebClient(probeTimeout),
            probeTimeout,
            overall);
    }

    private static ReleaseCheckService disabledReleases() {
        Duration timeout = Duration.ofSeconds(1);
        return new ReleaseCheckService(false, "http://127.0.0.1:9", Clock.systemUTC(), timeout,
            ReleaseCheckService.defaultWebClient(timeout));
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

    private static HttpServer hang(String path, long sleepMs) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(daemonPool());
        server.createContext(path, exchange -> {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
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

    private record InstantStart(long nanos) {
        static InstantStart now() {
            return new InstantStart(System.nanoTime());
        }

        Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - nanos);
        }
    }
}
