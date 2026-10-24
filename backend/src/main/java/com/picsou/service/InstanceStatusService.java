package com.picsou.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.InstanceStatusResponse;
import com.picsou.dto.ReleaseUpdate;
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
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Admin instance check. In-use sidecars are probed in parallel (2s each, 3s overall).
 * A probe failure is data, never an error response.
 */
@Service
public class InstanceStatusService {

    private static final Logger log = LoggerFactory.getLogger(InstanceStatusService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration OVERALL_DEADLINE = Duration.ofSeconds(3);
    private static final String BASE_PYTHON = "sidecar-python";
    private static final String BASE_CHROMIUM = "sidecar-chromium";

    private final List<SidecarTarget> sidecars;
    private final ReleaseCheckService releaseCheck;
    private final String currentVersion;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public InstanceStatusService(
        ReleaseCheckService releaseCheck,
        SetupService setupService,
        TradeRepublicSessionRepository trSessions,
        BoursoSessionRepository boursoSessions,
        CaisseEpargneSessionRepository caisseEpargneSessions,
        RevolutSessionRepository revolutSessions,
        BourseDirectSessionRepository bourseDirectSessions,
        DegiroSessionRepository degiroSessions,
        AmundiSessionRepository amundiSessions,
        AmexSessionRepository amexSessions,
        FortuneoSessionRepository fortuneoSessions,
        CorumSessionRepository corumSessions,
        SofidySessionRepository sofidySessions,
        @Value("${info.app.version:dev}") String currentVersion,
        @Value("${app.tr-auth.url:http://tr-auth:8001}") String trUrl,
        @Value("${app.bourso-auth.url:http://bourso-auth:8001}") String boursoUrl,
        @Value("${app.caisse-epargne-auth.url:http://caisse-epargne-auth:8001}") String caisseEpargneUrl,
        @Value("${app.revolut-auth.url:http://revolut-auth:8002}") String revolutUrl,
        @Value("${app.bourse-direct-auth.url:http://bourse-direct-auth:8001}") String bourseDirectUrl,
        @Value("${app.degiro-auth.url:http://degiro-auth:8001}") String degiroUrl,
        @Value("${app.amundi-auth.url:http://amundi-auth:8001}") String amundiUrl,
        @Value("${app.amex-auth.url:http://amex-auth:8001}") String amexUrl,
        @Value("${app.fortuneo-auth.url:http://fortuneo-auth:8001}") String fortuneoUrl,
        @Value("${app.corum-auth.url:http://corum-auth:8001}") String corumUrl,
        @Value("${app.sofidy-auth.url:http://sofidy-auth:8001}") String sofidyUrl
    ) {
        this(releaseCheck, currentVersion, registry(
            setupService,
            trUrl, boursoUrl, caisseEpargneUrl, revolutUrl, bourseDirectUrl, degiroUrl,
            amundiUrl, amexUrl, fortuneoUrl, corumUrl, sofidyUrl,
            trSessions, boursoSessions, caisseEpargneSessions, revolutSessions, bourseDirectSessions,
            degiroSessions, amundiSessions, amexSessions, fortuneoSessions, corumSessions, sofidySessions
        ), defaultWebClient());
    }

    InstanceStatusService(ReleaseCheckService releaseCheck, String currentVersion,
                          List<SidecarTarget> sidecars, WebClient webClient) {
        this.releaseCheck = releaseCheck;
        this.currentVersion = currentVersion == null || currentVersion.isBlank() ? "dev" : currentVersion.trim();
        this.sidecars = List.copyOf(sidecars);
        this.webClient = webClient;
    }

    public InstanceStatusResponse status() {
        return new InstanceStatusResponse(currentVersion, updateOrNull(), issuesOrEmpty());
    }

    List<SidecarTarget> targets() {
        return sidecars;
    }

    static String boursoUrl(SetupService setupService, String propertyUrl) {
        return setupService.readSetting(SetupService.KEY_BOURSO_AUTH_URL)
            .filter(value -> !value.isBlank())
            .orElse(propertyUrl);
    }

    private ReleaseUpdate updateOrNull() {
        try {
            return releaseCheck.check(currentVersion);
        } catch (RuntimeException ex) {
            log.warn("instance-status.release.failed", ex);
            return null;
        }
    }

    private List<SidecarIssue> issuesOrEmpty() {
        try {
            return probeInUse();
        } catch (RuntimeException ex) {
            log.warn("instance-status.probe.failed", ex);
            return List.of();
        }
    }

    private List<SidecarIssue> probeInUse() {
        List<SidecarTarget> inUse = new ArrayList<>();
        for (SidecarTarget target : sidecars) {
            try {
                if (target.sessions().getAsLong() > 0) {
                    inUse.add(target);
                }
            } catch (RuntimeException ex) {
                log.warn("instance-status.sidecar.count_failed name={}", target.name(), ex);
            }
        }
        if (inUse.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<Optional<SidecarIssue>>> futures = inUse.stream()
                .map(target -> CompletableFuture.supplyAsync(() -> probe(target), executor))
                .toList();
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(OVERALL_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                futures.forEach(future -> future.cancel(true));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
            } catch (ExecutionException ex) {
                log.warn("instance-status.probe.failed", ex);
            }
            List<SidecarIssue> issues = new ArrayList<>();
            for (int i = 0; i < inUse.size(); i++) {
                resultOrUnreachable(futures.get(i), inUse.get(i)).ifPresent(issues::add);
            }
            issues.sort(Comparator.comparing(SidecarIssue::sidecar));
            return List.copyOf(issues);
        } finally {
            // close() waits for in-flight probes and would blow the 3s bound.
            executor.shutdownNow();
        }
    }

    private static Optional<SidecarIssue> resultOrUnreachable(
        CompletableFuture<Optional<SidecarIssue>> future,
        SidecarTarget target
    ) {
        if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
            return future.join();
        }
        return Optional.of(issue(target, SidecarIssueCode.SIDECAR_UNREACHABLE));
    }

    private Optional<SidecarIssue> probe(SidecarTarget target) {
        String rawUrl;
        try {
            rawUrl = target.url().get();
        } catch (RuntimeException ex) {
            log.info("instance-status.sidecar.url_failed name={} err={}", target.name(), ex.toString());
            return Optional.of(issue(target, SidecarIssueCode.SIDECAR_UNREACHABLE));
        }
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.of(issue(target, SidecarIssueCode.SIDECAR_UNREACHABLE));
        }
        try {
            HealthResponse response = getHealth(healthUri(rawUrl));
            if (response == null || response.status < 200 || response.status >= 300) {
                log.info("instance-status.sidecar.unreachable name={} status={}",
                    target.name(), response == null ? -1 : response.status);
                return Optional.of(issue(target, SidecarIssueCode.SIDECAR_UNREACHABLE));
            }
            return classify(target, response.body);
        } catch (RuntimeException ex) {
            log.info("instance-status.sidecar.unreachable name={} err={}", target.name(), ex.toString());
            return Optional.of(issue(target, SidecarIssueCode.SIDECAR_UNREACHABLE));
        }
    }

    private HealthResponse getHealth(URI uri) {
        return webClient.get()
            .uri(uri)
            .exchangeToMono(response -> {
                int status = response.statusCode().value();
                if (!response.statusCode().is2xxSuccessful()) {
                    return response.releaseBody().thenReturn(new HealthResponse(status, new byte[0]));
                }
                return response.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .map(body -> new HealthResponse(status, body));
            })
            .timeout(PROBE_TIMEOUT)
            .block(PROBE_TIMEOUT);
    }

    private Optional<SidecarIssue> classify(SidecarTarget target, byte[] body) {
        if (target.expectedBase() == null) {
            return Optional.empty();
        }
        String base = readBase(body);
        if (target.expectedBase().equals(base)) {
            return Optional.empty();
        }
        return Optional.of(issue(target, SidecarIssueCode.SIDECAR_OUTDATED));
    }

    private String readBase(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode base = objectMapper.readTree(body).get("base");
            if (base == null || !base.isTextual()) {
                return null;
            }
            String text = base.asText().trim();
            return text.isEmpty() ? null : text;
        } catch (Exception ex) {
            return null;
        }
    }

    private static SidecarIssue issue(SidecarTarget target, SidecarIssueCode code) {
        return new SidecarIssue(code, target.name(), target.expectedBase());
    }

    static URI healthUri(String base) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return URI.create(trimmed + "/health");
    }

    private static List<SidecarTarget> registry(
        SetupService setupService,
        String trUrl,
        String boursoUrl,
        String caisseEpargneUrl,
        String revolutUrl,
        String bourseDirectUrl,
        String degiroUrl,
        String amundiUrl,
        String amexUrl,
        String fortuneoUrl,
        String corumUrl,
        String sofidyUrl,
        TradeRepublicSessionRepository trSessions,
        BoursoSessionRepository boursoSessions,
        CaisseEpargneSessionRepository caisseEpargneSessions,
        RevolutSessionRepository revolutSessions,
        BourseDirectSessionRepository bourseDirectSessions,
        DegiroSessionRepository degiroSessions,
        AmundiSessionRepository amundiSessions,
        AmexSessionRepository amexSessions,
        FortuneoSessionRepository fortuneoSessions,
        CorumSessionRepository corumSessions,
        SofidySessionRepository sofidySessions
    ) {
        return List.of(
            new SidecarTarget("bourso-auth", BASE_PYTHON, () -> boursoUrl(setupService, boursoUrl), boursoSessions::count),
            new SidecarTarget("sofidy-auth", BASE_PYTHON, () -> sofidyUrl, sofidySessions::count),
            new SidecarTarget("degiro-auth", BASE_PYTHON, () -> degiroUrl, degiroSessions::count),
            new SidecarTarget("tr-auth", BASE_CHROMIUM, () -> trUrl, trSessions::count),
            new SidecarTarget("caisse-epargne-auth", BASE_CHROMIUM, () -> caisseEpargneUrl, caisseEpargneSessions::count),
            new SidecarTarget("bourse-direct-auth", BASE_CHROMIUM, () -> bourseDirectUrl, bourseDirectSessions::count),
            new SidecarTarget("amundi-auth", BASE_CHROMIUM, () -> amundiUrl, amundiSessions::count),
            new SidecarTarget("fortuneo-auth", BASE_CHROMIUM, () -> fortuneoUrl, fortuneoSessions::count),
            new SidecarTarget("corum-auth", BASE_CHROMIUM, () -> corumUrl, corumSessions::count),
            new SidecarTarget("amex-auth", null, () -> amexUrl, amexSessions::count),
            new SidecarTarget("revolut-auth", null, () -> revolutUrl, revolutSessions::count)
        );
    }

    private static WebClient defaultWebClient() {
        // A 3xx is not a healthy sidecar; following it would hide a mispointed URL.
        HttpClient reactor = HttpClient.create()
            .followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) PROBE_TIMEOUT.toMillis())
            .responseTimeout(PROBE_TIMEOUT);
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(reactor))
            .build();
    }

    private record HealthResponse(int status, byte[] body) {}
}
