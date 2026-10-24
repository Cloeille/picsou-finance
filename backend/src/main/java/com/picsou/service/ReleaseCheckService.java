package com.picsou.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.ReleaseUpdate;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Latest GitHub release, cached in memory for 6 hours including failures so a dead
 * network is not one outbound call per admin page load. Disabled means no call at all.
 */
@Service
public class ReleaseCheckService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseCheckService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String USER_AGENT = "picsou-finance";
    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");

    private final boolean enabled;
    private final String githubApiUrl;
    private final String repository;
    private final Clock clock;
    private final Duration timeout;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile CachedRelease cache;

    @Autowired
    public ReleaseCheckService(
        @Value("${app.update-check.enabled:true}") boolean enabled,
        @Value("${app.update-check.github-api-url:https://api.github.com}") String githubApiUrl,
        @Value("${app.update-check.repository:Cloeille/picsou-finance}") String repository
    ) {
        this(enabled, githubApiUrl, repository, Clock.systemUTC(), DEFAULT_TIMEOUT, defaultWebClient());
    }

    ReleaseCheckService(boolean enabled, String githubApiUrl, String repository,
                        Clock clock, Duration timeout, WebClient webClient) {
        this.enabled = enabled;
        this.githubApiUrl = githubApiUrl;
        this.repository = repository;
        this.clock = clock;
        this.timeout = timeout;
        this.webClient = webClient;
    }

    /**
     * @return the newer release, or null when disabled, current is not plain semver,
     *         the check failed, or GitHub is not strictly newer
     */
    public ReleaseUpdate check(String currentVersion) {
        try {
            if (!enabled || semver(currentVersion) == null) {
                return null;
            }
            Release latest = cachedLatest();
            if (latest == null || !isNewer(latest.tag(), currentVersion)) {
                return null;
            }
            return new ReleaseUpdate(latest.tag(), latest.url());
        } catch (RuntimeException ex) {
            log.warn("instance-status.release.failed", ex);
            return null;
        }
    }

    private Release cachedLatest() {
        Instant now = clock.instant();
        CachedRelease snapshot = cache;
        if (fresh(snapshot, now)) {
            return snapshot.release();
        }
        synchronized (this) {
            now = clock.instant();
            snapshot = cache;
            if (fresh(snapshot, now)) {
                return snapshot.release();
            }
            Release release = null;
            try {
                release = fetch();
            } catch (Exception ex) {
                log.info("instance-status.release.fetch_failed err={}", ex.toString());
            }
            cache = new CachedRelease(clock.instant(), release);
            return release;
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
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(timeout)
            .block(timeout);
        if (node == null) {
            throw new IllegalStateException("empty github release");
        }
        String tag = text(node, "tag_name");
        String url = text(node, "html_url");
        if (tag == null || url == null) {
            throw new IllegalStateException("github release missing tag_name or html_url");
        }
        return new Release(tag, url);
    }

    private URI latestUri() {
        String base = githubApiUrl == null ? "" : githubApiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String repo = repository == null ? "" : repository.trim();
        return URI.create(base + "/repos/" + repo + "/releases/latest");
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

    private static WebClient defaultWebClient() {
        // A redirect must not move the check off the configured GitHub API host.
        HttpClient reactor = HttpClient.create()
            .followRedirect(false)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) DEFAULT_TIMEOUT.toMillis())
            .responseTimeout(DEFAULT_TIMEOUT);
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(reactor))
            .build();
    }

    private record Release(String tag, String url) {}

    private record CachedRelease(Instant fetchedAt, Release release) {}
}
