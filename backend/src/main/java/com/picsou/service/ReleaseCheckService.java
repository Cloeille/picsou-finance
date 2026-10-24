package com.picsou.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.dto.ReleaseUpdate;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Latest GitHub release, cached in memory for 6 hours including failures so a dead
 * network is not one outbound call per admin page load. The fetch runs on its own
 * virtual thread: a caller that times out or cancels does not abort it. Disabled
 * means no call at all.
 */
@Service
public class ReleaseCheckService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseCheckService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String USER_AGENT = "picsou-finance";
    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    static final String GITHUB_API = "https://api.github.com";
    static final String REPOSITORY = "Cloeille/picsou-finance";
    private static final String RELEASE_HTML_PREFIX = "https://github.com/" + REPOSITORY + "/releases/";

    private final boolean enabled;
    private final String githubApiUrl;
    private final Clock clock;
    private final Duration timeout;
    private final WebClient webClient;

    private volatile CachedRelease cache;
    private final AtomicReference<CompletableFuture<Release>> inFlight = new AtomicReference<>();

    @Autowired
    public ReleaseCheckService(@Value("${app.update-check.enabled:true}") boolean enabled) {
        this(enabled, GITHUB_API, Clock.systemUTC(), DEFAULT_TIMEOUT, defaultWebClient(DEFAULT_TIMEOUT));
    }

    ReleaseCheckService(boolean enabled, String githubApiUrl, Clock clock, Duration timeout, WebClient webClient) {
        this.enabled = enabled;
        this.githubApiUrl = githubApiUrl;
        this.clock = clock;
        this.timeout = timeout;
        this.webClient = webClient;
    }

    /**
     * @return a future completed immediately when disabled, current is not plain semver,
     *         or the cache is fresh; otherwise the shared in-flight fetch. Cancelling the
     *         returned future does not cancel that fetch.
     */
    public CompletableFuture<ReleaseUpdate> checkAsync(String currentVersion) {
        if (!enabled || semver(currentVersion) == null) {
            return CompletableFuture.completedFuture(null);
        }
        CachedRelease snapshot = cache;
        if (fresh(snapshot, clock.instant())) {
            return CompletableFuture.completedFuture(toUpdate(snapshot.release(), currentVersion));
        }
        CompletableFuture<ReleaseUpdate> returned = new CompletableFuture<>();
        fetchShared().whenComplete((release, error) -> {
            if (!returned.isDone()) {
                returned.complete(error == null ? toUpdate(release, currentVersion) : null);
            }
        });
        return returned;
    }

    private static ReleaseUpdate toUpdate(Release latest, String currentVersion) {
        if (latest == null || !isNewer(latest.tag(), currentVersion)) {
            return null;
        }
        return new ReleaseUpdate(latest.tag(), latest.url());
    }

    private CompletableFuture<Release> fetchShared() {
        while (true) {
            CachedRelease snapshot = cache;
            if (fresh(snapshot, clock.instant())) {
                return CompletableFuture.completedFuture(snapshot.release());
            }
            CompletableFuture<Release> current = inFlight.get();
            if (current != null && !current.isDone()) {
                return current;
            }
            if (current != null) {
                inFlight.compareAndSet(current, null);
                continue;
            }
            CompletableFuture<Release> created = new CompletableFuture<>();
            if (inFlight.compareAndSet(null, created)) {
                // Own virtual thread: cancelling or interrupting the caller must not abort this fetch.
                Thread.startVirtualThread(() -> runFetch(created));
                return created;
            }
        }
    }

    private void runFetch(CompletableFuture<Release> created) {
        Release release = null;
        try {
            release = fetch();
        } catch (Exception ex) {
            log.info("instance-status.release.fetch_failed err={}", ex.toString());
        } finally {
            cache = new CachedRelease(clock.instant(), release);
            created.complete(release);
            inFlight.compareAndSet(created, null);
        }
    }

    private static boolean fresh(CachedRelease snapshot, Instant now) {
        return snapshot != null
            && Duration.between(snapshot.fetchedAt(), now).compareTo(CACHE_TTL) < 0;
    }

    private Release fetch() throws Exception {
        JsonNode node = webClient.get()
            .uri(latestUri())
            .header("Accept", ACCEPT)
            .header("User-Agent", USER_AGENT)
            .exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) {
                    return response.releaseBody().then(Mono.error(
                        new IllegalStateException("github release status " + response.statusCode().value())));
                }
                return response.bodyToMono(JsonNode.class);
            })
            .timeout(timeout)
            .block(timeout);
        if (node == null) {
            throw new IllegalStateException("empty github release");
        }
        String tag = text(node, "tag_name");
        String url = text(node, "html_url");
        if (tag == null || url == null || !url.startsWith(RELEASE_HTML_PREFIX)) {
            throw new IllegalStateException("github release missing tag_name or html_url");
        }
        return new Release(tag, url);
    }

    private URI latestUri() {
        String base = githubApiUrl == null ? "" : githubApiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/repos/" + REPOSITORY + "/releases/latest");
    }

    static boolean isNewer(String latest, String current) {
        int[] left = semver(latest);
        int[] right = semver(current);
        if (left == null || right == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (left[i] != right[i]) {
                return left[i] > right[i];
            }
        }
        return false;
    }

    private static int[] semver(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = SEMVER.matcher(version.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    static WebClient defaultWebClient(Duration timeout) {
        // A redirect must not move the check off the configured GitHub API host.
        HttpClient reactor = HttpClient.create()
            .followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
            .responseTimeout(timeout);
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(reactor))
            .build();
    }

    private record Release(String tag, String url) {}

    private record CachedRelease(Instant fetchedAt, Release release) {}
}
