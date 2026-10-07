package com.picsou.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bit of Boursorama every adapter needs: turning a ticker into Boursorama's own symbol, and
 * fetching a page.
 *
 * <p>Extracted when a second adapter appeared. The search endpoint answers with a {@code 302}
 * whose {@code Location} carries the symbol, the redirect is deliberately not followed, and the
 * exchange suffix has to be stripped first — three details that were about to exist twice, which
 * is two places to repair the day Boursorama changes.
 */
@Component
public class BoursoramaClient {

    private static final Logger log = LoggerFactory.getLogger(BoursoramaClient.class);
    private static final String HOST = "https://www.boursorama.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern SYMBOL = Pattern.compile("/cours/([^/?]+)/");

    private final WebClient webClient;

    public BoursoramaClient() {
        this(WebClient.builder()
            .baseUrl(HOST)
            // Read the 302 Location ourselves instead of following the redirect.
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .defaultHeader("Accept-Language", "fr-FR")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    BoursoramaClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /** Boursorama's internal symbol for a ticker (NQSE &rarr; 1zNQSE), or empty. */
    public Optional<String> resolveSymbol(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String location = webClient.get()
            .uri(b -> b.path("/recherche/").queryParam("query", bareTicker(ticker)).build())
            .exchangeToMono(resp -> Mono.justOrEmpty(resp.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION)))
            .timeout(TIMEOUT)
            .onErrorResume(e -> Mono.empty())
            .block();
        return symbolFromLocation(location);
    }

    /** The body of a page, or empty on any failure — callers never see an exception from here. */
    public Optional<String> get(String uriTemplate, Object... vars) {
        try {
            String body = webClient.get().uri(uriTemplate, vars)
                .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
        } catch (Exception ex) {
            log.debug("Boursorama GET {} failed: {}", uriTemplate, ex.getMessage());
            return Optional.empty();
        }
    }

    static Optional<String> symbolFromLocation(String location) {
        if (location == null) return Optional.empty();
        Matcher m = SYMBOL.matcher(location);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Strip the exchange suffix from a ticker ("PUST.PA" &rarr; "PUST"). */
    static String bareTicker(String ticker) {
        int dot = ticker.indexOf('.');
        return dot > 0 ? ticker.substring(0, dot) : ticker;
    }
}
