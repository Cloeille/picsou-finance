package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.picsou.model.PropertyKind;
import com.picsou.model.ValuationConfidence;
import com.picsou.exception.ValuationProviderException;
import com.picsou.port.PropertyValuationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Values a property from Cerema's DV3F market indicators, built on DGFiP transaction data.
 *
 * <p>Free, no authentication, Licence Ouverte 2.0 — the reason a self-hosted Picsou can
 * estimate property value at all. Commercial alternatives (PriceHubble, Homiwoo, Yanport)
 * are quote-only or start around €200/month, which no per-user self-hosted install can carry.
 *
 * <p>Two properties of this source drive the design:
 *
 * <ul>
 *   <li><b>It is coarse.</b> The figure is a commune-wide median €/m², so it knows nothing
 *       about the specific street, floor or condition. Caller-side adjustments and the
 *       q25/q75 band exist to keep that honest rather than to hide it.
 *   <li><b>It is best-effort infrastructure.</b> Still on a {@code -preprod} host, with no
 *       documented rate limits and observed transient 503s. Every failure path here returns
 *       empty instead of throwing, and the service keeps the previous valuation.
 * </ul>
 */
@Component
public class CeremaDv3fValuationProvider implements PropertyValuationPort {

    private static final Logger log = LoggerFactory.getLogger(CeremaDv3fValuationProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public static final String PROVIDER_NAME = "CEREMA_DV3F";

    /**
     * Departments DVF does not cover.
     *
     * <p>Alsace and Moselle keep the <i>livre foncier</i> land registry rather than the
     * DGFiP one, and Mayotte is absent too. The API answers {@code count: 0} there — which
     * would otherwise look like "no recent sales" instead of "structurally no data" — so
     * they are refused up front and the user is told to value manually.
     */
    private static final Set<String> UNCOVERED_DEPARTMENTS = Set.of("57", "67", "68", "976");

    /** Below this many transactions the median is noise. */
    private static final int LOW_CONFIDENCE_SAMPLE = 15;
    private static final int MEDIUM_CONFIDENCE_SAMPLE = 50;

    /** Room-count series exist for apartments up to 5; bigger flats fold into the last bucket. */
    private static final int MAX_ROOM_SERIES = 5;
    /** Minimum sample before preferring the narrow room-count series over the commune-wide one. */
    private static final int MIN_ROOM_SERIES_SAMPLE = 20;

    private final WebClient webClient;

    @org.springframework.beans.factory.annotation.Autowired
    public CeremaDv3fValuationProvider(
        @Value("${app.valuation.cerema.base-url:https://apidf-preprod.cerema.fr}") String baseUrl
    ) {
        this(WebClient.builder()
            .baseUrl(baseUrl)
            // One response carries every vintage back to 2010 with ~200 indicator columns each:
            // ~265 KB for an ordinary commune, just past WebClient's 256 KB default. Over that
            // limit the body is never assembled, so *every* commune failed -- and it surfaced
            // as "no comparable transactions" rather than as an error.
            .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
            .defaultHeader("Accept", "application/json")
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    CeremaDv3fValuationProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(ValuationInput input) {
        if (input == null || input.kind() == null || !input.kind().isEstimable()) {
            return false;
        }
        if (input.countryCode() != null && !"FR".equalsIgnoreCase(input.countryCode())) {
            return false;
        }
        if (input.inseeCode() == null || input.inseeCode().isBlank()) {
            return false;
        }
        if (input.livingArea() == null || input.livingArea().signum() <= 0) {
            return false;
        }
        return !UNCOVERED_DEPARTMENTS.contains(input.departmentCode());
    }

    @Override
    public Optional<ValuationResult> estimate(ValuationInput input) {
        if (!supports(input)) {
            return Optional.empty();
        }

        // Commune first — it is the most local scale this source offers. Small communes often
        // have too few sales for a stable median, so fall back to the department rather than
        // reporting a figure derived from a handful of transactions.
        Optional<ValuationResult> communeResult =
            fetchAndBuild("communes", input.inseeCode(), input, false);
        if (communeResult.isPresent()) {
            return communeResult;
        }

        String department = input.departmentCode();
        if (department == null) {
            return Optional.empty();
        }
        log.debug("No usable commune median for INSEE {} — falling back to department {}",
            input.inseeCode(), department);
        return fetchAndBuild("departements", department, input, true);
    }

    private Optional<ValuationResult> fetchAndBuild(String scale, String code,
                                                    ValuationInput input, boolean degraded) {
        JsonNode payload = fetch(scale, code);
        JsonNode results = payload == null ? null : payload.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }

        // Results come back oldest-first; walk backwards so the freshest usable vintage wins.
        for (int i = results.size() - 1; i >= 0; i--) {
            Optional<ValuationResult> built = build(results.get(i), input, scale, degraded);
            if (built.isPresent()) {
                return built;
            }
        }
        return Optional.empty();
    }

    private JsonNode fetch(String scale, String code) {
        try {
            return webClient.get()
                .uri(uri -> uri.path("/indicateurs/dv3f/prix/annuel/")
                    .queryParam("echelle", scale)
                    .queryParam("code", code)
                    .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(TIMEOUT)
                .block();
        } catch (RuntimeException ex) {
            // Raised rather than swallowed: the caller keeps the previous valuation either
            // way, but the user is told the source was unreachable instead of being told
            // their commune has no sales -- a distinction that cost real debugging time.
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof WebClientResponseException http) {
                log.warn("Cerema DV3F {}={} failed: HTTP {}", scale, code, http.getStatusCode().value());
            } else if (cause instanceof WebClientRequestException
                || cause instanceof java.util.concurrent.TimeoutException) {
                log.warn("Cerema DV3F unreachable for {}={} (timeout {}s)", scale, code, TIMEOUT.toSeconds());
            } else {
                log.warn("Cerema DV3F {}={} failed", scale, code, ex);
            }
            throw new ValuationProviderException(
                "Cerema DV3F " + scale + "=" + code + " failed", ex);
        }
    }

    private Optional<ValuationResult> build(JsonNode row, ValuationInput input,
                                            String scale, boolean degraded) {
        String series = seriesFor(input, row);
        if (series == null) {
            return Optional.empty();
        }

        BigDecimal median = decimal(row, "pxm2_median_" + series);
        if (median == null || median.signum() <= 0) {
            return Optional.empty();
        }
        Integer sample = integer(row, "nbtrans_" + series);
        BigDecimal area = input.livingArea();

        BigDecimal q25 = decimal(row, "pxm2_q25_" + series);
        BigDecimal q75 = decimal(row, "pxm2_q75_" + series);

        ValuationConfidence confidence = confidenceFor(sample, degraded);

        return Optional.of(new ValuationResult(
            scale(median.multiply(area)),
            q25 != null ? scale(q25.multiply(area)) : null,
            q75 != null ? scale(q75.multiply(area)) : null,
            median,
            sample,
            confidence,
            shortValue(row, "annee"),
            scale
        ));
    }

    /**
     * Picks the DV3F series code for this property.
     *
     * <p>Houses only have the aggregate {@code cod111}. Apartments additionally expose
     * per-room-count series ({@code cod121x1}..{@code cod121x5}) whose median surfaces —
     * 25, 45, 66, 85, 112 m² — track room count closely, so using them is markedly better
     * than a commune-wide flat median that mixes studios with five-room apartments. Only
     * worth it when that narrower bucket still has a real sample behind it.
     */
    private String seriesFor(ValuationInput input, JsonNode row) {
        if (input.kind() == PropertyKind.HOUSE) {
            return "cod111";
        }
        if (input.kind() != PropertyKind.APARTMENT) {
            return null;
        }
        Short rooms = input.rooms();
        if (rooms != null && rooms > 0) {
            int bucket = Math.min(rooms, MAX_ROOM_SERIES);
            String roomSeries = "cod121x" + bucket;
            Integer roomSample = integer(row, "nbtrans_" + roomSeries);
            BigDecimal roomMedian = decimal(row, "pxm2_median_" + roomSeries);
            if (roomMedian != null && roomMedian.signum() > 0
                && roomSample != null && roomSample >= MIN_ROOM_SERIES_SAMPLE) {
                return roomSeries;
            }
        }
        return "cod121";
    }

    private ValuationConfidence confidenceFor(Integer sample, boolean degraded) {
        if (degraded) {
            // A department-wide median spans very different markets — never call that high
            // confidence however many transactions back it.
            return ValuationConfidence.LOW;
        }
        if (sample == null || sample < LOW_CONFIDENCE_SAMPLE) {
            return ValuationConfidence.LOW;
        }
        return sample < MEDIUM_CONFIDENCE_SAMPLE ? ValuationConfidence.MEDIUM : ValuationConfidence.HIGH;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        JsonNode node = row.get(field);
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private static Integer integer(JsonNode row, String field) {
        JsonNode node = row.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    /** {@code annee} comes back as a JSON string, not a number. */
    private static Short shortValue(JsonNode row, String field) {
        JsonNode node = row.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return Short.valueOf(node.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
