package com.picsou.adapter;

import com.picsou.port.MerchantLogoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Fetches brand logos from DuckDuckGo's public icon service
 * ({@code https://icons.duckduckgo.com/ip3/{domain}.ico}) — no API key, and the more
 * privacy-aligned choice for a self-hosted finance app (ADR 2026-06-09).
 *
 * <p>The {@code logoDomain} always originates from the bundled, seeded
 * {@link com.picsou.model.MerchantBrand} table — never from user input — so there is no
 * SSRF surface here. The fetch is best-effort: a 5s timeout, a 1&nbsp;MB body cap, and a
 * catch-all that maps every failure to {@link Optional#empty()} so a flaky upstream can
 * never break a page or block the request thread for long.
 */
@Component
public class DuckDuckGoLogoProvider implements MerchantLogoPort {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoLogoProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BYTES = 1024 * 1024; // 1 MB — logos are a few KB; this is a safety cap

    private final WebClient webClient;

    public DuckDuckGoLogoProvider() {
        this.webClient = WebClient.builder()
            .baseUrl("https://icons.duckduckgo.com")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BYTES))
            .build();
    }

    @Override
    public Optional<LogoImage> fetch(String logoDomain) {
        if (logoDomain == null || logoDomain.isBlank()) {
            return Optional.empty();
        }
        String domain = UriUtils.encodePathSegment(logoDomain.trim().toLowerCase(), StandardCharsets.UTF_8);
        try {
            ResponseEntity<byte[]> response = webClient.get()
                .uri("/ip3/{domain}.ico", domain)
                .retrieve()
                .toEntity(byte[].class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                return Optional.empty();
            }
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            MediaType ct = response.getHeaders().getContentType();
            String contentType = ct != null ? ct.toString() : MediaType.IMAGE_PNG_VALUE;
            return Optional.of(new LogoImage(body, contentType));
        } catch (Exception ex) {
            log.debug("Logo fetch failed for {}: {}", logoDomain, ex.getMessage());
            return Optional.empty();
        }
    }
}
