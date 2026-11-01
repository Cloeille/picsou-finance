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
import com.picsou.repository.CicSessionRepository;
import com.picsou.repository.CmesSessionRepository;
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
import org.springframework.core.env.Environment;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Admin instance check. The release check, the session counts and the in-use sidecar
 * probes share one 3s deadline (2s per probe). A probe failure is data, never an error
 * response.
 */
@Service
public class InstanceStatusService {

    private static final Logger log = LoggerFactory.getLogger(InstanceStatusService.class);
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    static final Duration OVERALL_DEADLINE = Duration.ofSeconds(3);
    private static final String BASE_PYTHON = "sidecar-python";
    private static final String BASE_CHROMIUM = "sidecar-chromium";

    private final List<SidecarTarget> sidecars;
    private final ReleaseCheckService releaseCheck;
    private final String currentVersion;
    private final WebClient webClient;
    private final Duration probeTimeout;
    private final Duration overallDeadline;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean counting = new AtomicBoolean();

    @Autowired
    public InstanceStatusService(
        ReleaseCheckService releaseCheck,
        SetupService setupService,
        Environment env,
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
        CmesSessionRepository cmesSessions,
        CicSessionRepository cicSessions,
        @Value("${info.app.version:dev}") String currentVersion
    ) {
        this(releaseCheck, currentVersion, registry(
            setupService, env,
            trSessions, boursoSessions, caisseEpargneSessions, revolutSessions, bourseDirectSessions,
            degiroSessions, amundiSessions, amexSessions, fortuneoSessions, corumSessions, sofidySessions,
            cmesSessions, cicSessions
        ), defaultWebClient(PROBE_TIMEOUT), PROBE_TIMEOUT, OVERALL_DEADLINE);
    }

    InstanceStatusService(ReleaseCheckService releaseCheck, String currentVersion,
                          List<SidecarTarget> sidecars, WebClient webClient,
                          Duration probeTimeout, Duration overallDeadline) {
        this.releaseCheck = releaseCheck;
        this.currentVersion = currentVersion == null || currentVersion.isBlank() ? "dev" : currentVersion.trim();
        this.sidecars = List.copyOf(sidecars);
        this.webClient = webClient;
        this.probeTimeout = probeTimeout;
        this.overallDeadline = overallDeadline;
    }

    public InstanceStatusResponse status() {
        long deadline = System.nanoTime() + overallDeadline.toNanos();
        CompletableFuture<ReleaseUpdate> update = releaseCheck.checkAsync(currentVersion);
        List<SidecarIssue> issues = issuesOrEmpty(deadline);
        awaitRelease(update, deadline);
        return new InstanceStatusResponse(currentVersion, completedUpdate(update), issues);
    }

    List<SidecarTarget> targets() {
        return sidecars;
    }

    private static ReleaseUpdate completedUpdate(CompletableFuture<ReleaseUpdate> update) {
        if (!update.isDone() || update.isCancelled() || update.isCompletedExceptionally()) {
            return null;
        }
        return update.join();
    }

    private static void awaitRelease(CompletableFuture<?> update, long deadlineNanos) {
        if (update.isDone()) {
            return;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return;
        }
        try {
            update.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            // Leave the fetch running so it can fill the 6 h cache.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private List<SidecarIssue> issuesOrEmpty(long deadlineNanos) {
        try {
            return probeInUse(deadlineNanos);
        } catch (RuntimeException ex) {
            log.warn("instance-status.probe.failed", ex);
            return List.of();
        }
    }

    private List<SidecarIssue> probeInUse(long deadlineNanos) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<SidecarProbe> probes = sidecars.stream().map(target -> start(target, executor)).toList();
            if (!startCounts(probes, executor)) {
                log.info("instance-status.counts.busy");
                return List.of();
            }
            List<CompletableFuture<Optional<SidecarIssue>>> futures = probes.stream()
                .map(SidecarProbe::result)
                .toList();
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining < 0) {
                remaining = 0;
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException ex) {
                futures.forEach(future -> future.cancel(true));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
            } catch (ExecutionException ex) {
                log.warn("instance-status.probe.failed", ex);
            }
            List<SidecarIssue> issues = new ArrayList<>();
            probes.forEach(probe -> outcome(probe).ifPresent(issues::add));
            issues.sort(Comparator.comparing(SidecarIssue::sidecar));
            return List.copyOf(issues);
        } finally {
            // close() waits for in-flight counts and probes and would blow the 3s bound.
            executor.shutdownNow();
        }
    }

    private boolean startCounts(List<SidecarProbe> probes, ExecutorService executor) {
        if (!counting.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    countSessions(probes);
                } finally {
                    counting.set(false);
                }
            });
        } catch (RuntimeException ex) {
            counting.set(false);
            throw ex;
        }
        return true;
    }

    /** The count future completes later; the probe starts as soon as it does, so probes run in parallel. */
    private SidecarProbe start(SidecarTarget target, ExecutorService executor) {
        CompletableFuture<Long> count = new CompletableFuture<>();
        return new SidecarProbe(target, count,
            count.thenApplyAsync(sessions -> sessions > 0 ? probe(target) : Optional.empty(), executor));
    }

    /**
     * Counts one sidecar after another: one pooled DB connection at a time, because an interrupted
     * JDBC query keeps its connection. A count that fails is treated as not in use; one cut off by
     * the deadline stays incomplete and is reported as a timeout. For the same reason only one run
     * is in flight per instance: the next request must not start another while this one is blocked.
     */
    private static void countSessions(List<SidecarProbe> probes) {
        for (SidecarProbe probe : probes) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                probe.count().complete(probe.target().sessions().getAsLong());
            } catch (RuntimeException ex) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.warn("instance-status.sidecar.count_failed name={}", probe.target().name(), ex);
                probe.count().complete(0L);
            }
        }
    }

    private static Optional<SidecarIssue> outcome(SidecarProbe probe) {
        CompletableFuture<Optional<SidecarIssue>> result = probe.result();
        if (result.isDone() && !result.isCompletedExceptionally() && !result.isCancelled()) {
            return result.join();
        }
        if (!probe.count().isDone()) {
            // Unknown whether the sidecar is in use: report nothing rather than a guess.
            log.info("instance-status.sidecar.count_timeout name={}", probe.target().name());
            return Optional.empty();
        }
        if (probe.count().isCompletedExceptionally() || probe.count().join() == 0) {
            return Optional.empty();
        }
        return Optional.of(issue(probe.target(), SidecarIssueCode.SIDECAR_UNREACHABLE));
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
            .timeout(probeTimeout)
            .block(probeTimeout);
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
        Environment env,
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
        CmesSessionRepository cmesSessions,
        CicSessionRepository cicSessions
    ) {
        return List.of(
            new SidecarTarget("bourso-auth", BASE_PYTHON,
                () -> setupService.boursoAuthUrl(propertyUrl(env, "bourso-auth")), boursoSessions::count),
            new SidecarTarget("sofidy-auth", BASE_PYTHON,
                () -> propertyUrl(env, "sofidy-auth"), sofidySessions::count),
            new SidecarTarget("cmes-auth", BASE_PYTHON,
                () -> propertyUrl(env, "cmes-auth"), cmesSessions::count),
            new SidecarTarget("cic-auth", BASE_PYTHON,
                () -> propertyUrl(env, "cic-auth"), cicSessions::count),
            new SidecarTarget("degiro-auth", BASE_PYTHON,
                () -> propertyUrl(env, "degiro-auth"), degiroSessions::count),
            new SidecarTarget("tr-auth", BASE_CHROMIUM,
                () -> propertyUrl(env, "tr-auth"), trSessions::count),
            new SidecarTarget("caisse-epargne-auth", BASE_CHROMIUM,
                () -> propertyUrl(env, "caisse-epargne-auth"), caisseEpargneSessions::count),
            new SidecarTarget("bourse-direct-auth", BASE_CHROMIUM,
                () -> propertyUrl(env, "bourse-direct-auth"), bourseDirectSessions::count),
            new SidecarTarget("amundi-auth", BASE_CHROMIUM,
                () -> propertyUrl(env, "amundi-auth"), amundiSessions::count),
            new SidecarTarget("fortuneo-auth", BASE_CHROMIUM,
                () -> propertyUrl(env, "fortuneo-auth"), fortuneoSessions::count),
            new SidecarTarget("corum-auth", BASE_CHROMIUM,
                () -> propertyUrl(env, "corum-auth"), corumSessions::count),
            new SidecarTarget("amex-auth", null,
                () -> propertyUrl(env, "amex-auth"), amexSessions::count),
            new SidecarTarget("revolut-auth", null,
                () -> propertyUrl(env, "revolut-auth"), revolutSessions::count)
        );
    }

    private static String propertyUrl(Environment env, String name) {
        String value = env.getProperty("app." + name + ".url");
        return value == null ? "" : value;
    }

    static WebClient defaultWebClient(Duration timeout) {
        // A 3xx is not a healthy sidecar; following it would hide a mispointed URL.
        HttpClient reactor = HttpClient.create()
            .followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
            .responseTimeout(timeout);
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(reactor))
            .build();
    }

    private record HealthResponse(int status, byte[] body) {}

    private record SidecarProbe(
        SidecarTarget target,
        CompletableFuture<Long> count,
        CompletableFuture<Optional<SidecarIssue>> result
    ) {}
}
