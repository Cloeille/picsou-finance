package com.picsou.adapter;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Fetches a justETF profile page by ISIN. No auth.
 *
 * <p>English locale on purpose: the {@code data-testid} attributes the parser reads are
 * locale-stable but the values behind them are not, and the label tables are written against
 * English ("Accumulating", "United States", "Technology").
 */
@Component
public class JustEtfClient {

    private static final String HOST = "https://www.justetf.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;

    public JustEtfClient() {
        this(WebClient.builder()
            .baseUrl(HOST)
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(true)))
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .defaultHeader("Accept-Language", "en")
            // The page is ~500 KB of chrome around ~15 fields.
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build());
    }

    // Package-private for tests.
    JustEtfClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /** The profile page for an ISIN, or empty when the fetch fails. */
    public Optional<String> profile(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        return Optional.ofNullable(webClient.get()
            .uri(b -> b.path("/en/etf-profile.html").queryParam("isin", isin).build())
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT)
            .onErrorResume(e -> Mono.empty())
            .block());
    }
}
