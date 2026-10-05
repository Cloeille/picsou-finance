package com.picsou.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.picsou.port.GeocodingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Geocodes French addresses through the IGN Géoplateforme service.
 *
 * <p>No API key, no account, 50 requests/second — which is what makes automatic property
 * valuation possible in a self-hosted app where every install would otherwise need its own
 * credentials.
 *
 * <p><b>Base URL matters.</b> This targets {@code data.geopf.fr} directly and not the older
 * {@code api-adresse.data.gouv.fr}: the latter was scheduled for decommissioning at the end
 * of January 2026 and now survives only as a cross-host 301. Any client that does not follow
 * redirects across hosts — or the day the redirect is switched off — would break silently.
 */
@Component
public class GeoplateformeGeocoder implements GeocodingPort {

    private static final Logger log = LoggerFactory.getLogger(GeoplateformeGeocoder.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_LIMIT = 10;

    private final WebClient webClient;

    @org.springframework.beans.factory.annotation.Autowired
    public GeoplateformeGeocoder(
        @Value("${app.geocoding.base-url:https://data.geopf.fr/geocodage}") String baseUrl
    ) {
        this(WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    GeoplateformeGeocoder(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Optional<GeocodeResult> geocode(String query) {
        List<GeocodeResult> results = search(query, 1);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public List<GeocodeResult> autocomplete(String query, int limit) {
        return search(query, Math.clamp(limit, 1, MAX_LIMIT));
    }

    private List<GeocodeResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            FeatureCollection response = webClient.get()
                .uri(uri -> buildUri(uri, query, limit))
                .retrieve()
                .bodyToMono(FeatureCollection.class)
                .timeout(TIMEOUT)
                .block();

            if (response == null || response.features == null) {
                return List.of();
            }
            List<GeocodeResult> results = new ArrayList<>();
            for (Feature feature : response.features) {
                GeocodeResult mapped = toResult(feature);
                if (mapped != null) {
                    results.add(mapped);
                }
            }
            return results;
        } catch (RuntimeException ex) {
            // A geocoding outage must not take a page down with it: the caller either falls
            // back to the stored coordinates or asks the user to confirm the address.
            logFailure(query, ex);
            return List.of();
        }
    }

    private java.net.URI buildUri(UriBuilder uri, String query, int limit) {
        return uri.path("/search")
            .queryParam("q", query)
            .queryParam("limit", limit)
            .queryParam("index", "address")
            .queryParam("returntruegeometry", "false")
            .build();
    }

    /**
     * Maps one GeoJSON feature.
     *
     * <p>Coordinates are GeoJSON order — longitude first, latitude second. Swapping them
     * puts French addresses in the Indian Ocean, and nothing downstream would notice.
     */
    private GeocodeResult toResult(Feature feature) {
        if (feature == null || feature.properties == null) {
            return null;
        }
        Properties p = feature.properties;
        BigDecimal longitude = null;
        BigDecimal latitude = null;
        if (feature.geometry != null && feature.geometry.coordinates != null
            && feature.geometry.coordinates.size() >= 2) {
            longitude = feature.geometry.coordinates.get(0);
            latitude = feature.geometry.coordinates.get(1);
        }
        if (p.citycode == null && latitude == null) {
            return null;
        }
        return new GeocodeResult(
            p.label,
            p.score,
            latitude,
            longitude,
            p.citycode,
            p.postcode,
            p.city,
            p.banId
        );
    }

    private void logFailure(String query, RuntimeException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof WebClientResponseException http) {
            log.warn("Geocoding failed for '{}': HTTP {} {}", query,
                http.getStatusCode().value(), http.getStatusText());
        } else if (cause instanceof WebClientRequestException || cause instanceof java.util.concurrent.TimeoutException) {
            log.warn("Geocoding unreachable for '{}' (timeout {}s)", query, TIMEOUT.toSeconds());
        } else {
            log.warn("Geocoding failed for '{}'", query, ex);
        }
    }

    // ─── Wire format ─────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class FeatureCollection {
        public List<Feature> features;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Feature {
        public Geometry geometry;
        public Properties properties;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Geometry {
        /** [longitude, latitude] in WGS84. */
        public List<BigDecimal> coordinates;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Properties {
        public String label;
        public BigDecimal score;
        /** INSEE commune code — the field the whole valuation chain hangs on. */
        public String citycode;
        public String postcode;
        public String city;
        public String banId;
    }
}
