package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.port.WisePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Wise over HTTPS, on the fixed host {@code api.wise.com}. Redirects are refused and the
 * token travels only in the Authorization header.
 *
 * <p>One {@link #fetchJars} call shares a single {@link #TOTAL_BUDGET}: the profiles request
 * and every balances request after it run one after the other, and a slow Wise must not keep
 * the caller waiting past the 60 s the reverse proxy allows.
 */
@Component
public class WiseClient implements WisePort {

    static final String API_BASE = "https://api.wise.com";
    /** Whole {@link #fetchJars} call, kept under nginx's {@code proxy_read_timeout} of 60 s. */
    static final Duration TOTAL_BUDGET = Duration.ofSeconds(45);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(WiseClient.class);
    private static final int MAX_PROFILES_BODY = 256_000;
    private static final int MAX_BALANCES_BODY = 1_000_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String apiBase;
    private final WiseTransport transport;
    private final LongSupplier nanoTime;

    public WiseClient() {
        this(API_BASE);
    }

    /** Visible for tests: the production transport against a local server. */
    WiseClient(String apiBase) {
        this.apiBase = apiBase;
        this.nanoTime = System::nanoTime;
        HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        this.transport = (uri, authorization, maxBody, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
            try {
                HttpResponse<String> response = http.send(request, BoundedUtf8Body.handler(maxBody));
                return new WiseTransport.Response(response.statusCode(), response.body());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SyncException("Could not reach Wise. Try again in a moment.", ex);
            } catch (IOException ex) {
                if (BoundedUtf8Body.isTooLarge(ex)) {
                    throw new SyncException("Wise returned a response that is too large.", ex);
                }
                throw new SyncException("Could not reach Wise. Try again in a moment.", ex);
            }
        };
    }

    /** Visible for tests: a fake transport and a fake monotonic clock. */
    WiseClient(WiseTransport transport, LongSupplier nanoTime) {
        this.apiBase = API_BASE;
        this.transport = transport;
        this.nanoTime = nanoTime;
    }

    @Override
    public List<WiseJar> fetchJars(String apiToken) {
        String authorization = "Bearer " + requireToken(apiToken);
        long startedAt = nanoTime.getAsLong();
        List<WiseJson.Profile> profiles = WiseJson.profiles(
            get(URI.create(apiBase + "/v2/profiles"), authorization, MAX_PROFILES_BODY, "profiles", startedAt));
        List<WiseJar> jars = new ArrayList<>();
        for (WiseJson.Profile profile : profiles) {
            URI balances = URI.create(apiBase + "/v4/profiles/" + profile.id() + "/balances?types=SAVINGS");
            jars.addAll(WiseJson.jars(
                get(balances, authorization, MAX_BALANCES_BODY, "balances", startedAt), profile));
        }
        return jars;
    }

    /**
     * A Wise personal token is a UUID. Anything outside letters, digits and dashes is refused
     * before it can reach a header, so a pasted value cannot inject a second header line.
     */
    static String requireToken(String apiToken) {
        String token = apiToken == null ? "" : apiToken.trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_CHARS || !token.matches("[A-Za-z0-9-]+")) {
            throw new SyncException("This does not look like a Wise API token. Copy it again from Wise.",
                null, "INVALID_TOKEN");
        }
        return token;
    }

    private String get(URI uri, String authorization, int maxBody, String call, long startedAt) {
        Duration remaining = TOTAL_BUDGET.minusNanos(nanoTime.getAsLong() - startedAt);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new SyncException("Wise took too long to answer. Try again in a moment.");
        }
        Duration timeout = remaining.compareTo(REQUEST_TIMEOUT) < 0 ? remaining : REQUEST_TIMEOUT;
        WiseTransport.Response response = transport.get(uri, authorization, maxBody, timeout);
        int status = response.status();
        if (status == 401) {
            throw new SyncException(
                "Wise refused the API token. It may have been revoked. Create a new token in Wise and connect again.",
                null, "SESSION_EXPIRED");
        }
        if (status == 403) {
            throw new SyncException(
                "Wise refused access to this data. Check the token's permissions in Wise, or try again later.",
                null, "ACCESS_DENIED");
        }
        if (status == 429) {
            throw new SyncException("Wise is limiting requests right now. Try again in a few minutes.",
                null, "RATE_LIMITED");
        }
        if (status >= 300 && status < 400) {
            throw new SyncException("Wise redirected the request, which Picsou refuses.");
        }
        if (status < 200 || status >= 300) {
            log.warn("Wise {} request failed with HTTP {}", call, status);
            throw new SyncException("Wise could not complete the request (HTTP " + status + ").");
        }
        return response.body();
    }
}
