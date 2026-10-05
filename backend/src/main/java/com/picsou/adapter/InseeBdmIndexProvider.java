package com.picsou.adapter;

import com.picsou.model.PropertyKind;
import com.picsou.port.HousingPriceIndexPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Re-indexes property prices using INSEE's "indice des prix des logements anciens".
 *
 * <p>Free and unauthenticated. This matters because the transaction data underneath a
 * valuation always lags: the freshest DVF vintage can be a year or more old, and in a moving
 * market a raw median is stale by exactly that much. Carrying it forward on the official
 * index closes most of that gap.
 *
 * <p>Every failure path returns empty rather than throwing — losing the re-indexing makes an
 * estimate less precise, which must never be confused with making it fail.
 */
@Component
public class InseeBdmIndexProvider implements HousingPriceIndexPort {

    private static final Logger log = LoggerFactory.getLogger(InseeBdmIndexProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * Series identifiers, metropolitan France, raw (non-seasonally-adjusted).
     *
     * <p>Requesting several idBanks in one call — the documented {@code id1+id2} form —
     * answers 404 on this endpoint, so each series is fetched on its own.
     */
    private static final String IDBANK_APARTMENTS = "010567056";
    private static final String IDBANK_ALL = "010567058";
    private static final String IDBANK_HOUSES = "010567060";

    /** The index is quarterly and revised rarely; a day of caching is plenty. */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final WebClient webClient;
    private final Map<String, CachedSeries> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public InseeBdmIndexProvider(
        @Value("${app.valuation.insee.base-url:https://api.insee.fr/series/BDM/V1}") String baseUrl
    ) {
        this(WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Accept", "application/xml")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    InseeBdmIndexProvider(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Optional<BigDecimal> reindexRatio(YearMonth from, YearMonth to, PropertyKind kind) {
        if (from == null || to == null) {
            return Optional.empty();
        }
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }

        NavigableMap<Integer, BigDecimal> series = series(idBankFor(kind));
        if (series == null || series.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal fromValue = valueAtOrBefore(series, from);
        BigDecimal toValue = valueAtOrBefore(series, to);
        if (fromValue == null || toValue == null || fromValue.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(toValue.divide(fromValue, 6, RoundingMode.HALF_UP));
    }

    private static String idBankFor(PropertyKind kind) {
        if (kind == PropertyKind.HOUSE) {
            return IDBANK_HOUSES;
        }
        if (kind == PropertyKind.APARTMENT) {
            return IDBANK_APARTMENTS;
        }
        return IDBANK_ALL;
    }

    /**
     * Latest observation on or before the requested period.
     *
     * <p>"On or before" rather than exact: the most recent quarter is published with a lag,
     * so asking for the current one would otherwise always miss.
     */
    private static BigDecimal valueAtOrBefore(NavigableMap<Integer, BigDecimal> series, YearMonth period) {
        Map.Entry<Integer, BigDecimal> entry = series.floorEntry(quarterKey(period));
        return entry != null ? entry.getValue() : null;
    }

    /** Sortable {@code YYYYQ} key, e.g. 2026-Q1 becomes 20261. */
    private static int quarterKey(YearMonth period) {
        return period.getYear() * 10 + ((period.getMonthValue() - 1) / 3 + 1);
    }

    private NavigableMap<Integer, BigDecimal> series(String idBank) {
        CachedSeries cached = cache.get(idBank);
        if (cached != null && !cached.isExpired()) {
            return cached.values();
        }
        NavigableMap<Integer, BigDecimal> fetched = fetch(idBank);
        if (fetched != null) {
            cache.put(idBank, new CachedSeries(fetched, Instant.now()));
            return fetched;
        }
        // Serve a stale series rather than nothing: a slightly old index still beats
        // pretending prices have not moved since the DVF vintage.
        return cached != null ? cached.values() : null;
    }

    private NavigableMap<Integer, BigDecimal> fetch(String idBank) {
        String xml;
        try {
            xml = webClient.get()
                .uri("/data/SERIES_BDM/{idBank}", idBank)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .block();
        } catch (RuntimeException ex) {
            log.warn("INSEE index {} unavailable — estimates will skip re-indexing", idBank);
            log.debug("INSEE fetch failure detail", ex);
            return null;
        }
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            return parse(xml);
        } catch (Exception ex) {
            log.warn("INSEE index {} could not be parsed — estimates will skip re-indexing", idBank);
            log.debug("INSEE parse failure detail", ex);
            return null;
        }
    }

    /**
     * Pulls {@code <Obs TIME_PERIOD="YYYY-Qn" OBS_VALUE="..."/>} out of the SDMX-ML payload.
     *
     * <p>StAX rather than a binding library: the response is a deeply namespaced SDMX
     * structure of which two attributes are needed, and scanning for them avoids both a new
     * dependency and a schema that changes underneath us.
     */
    private static NavigableMap<Integer, BigDecimal> parse(String xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // This parses a response from a remote host: disable external entity resolution.
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        NavigableMap<Integer, BigDecimal> values = new TreeMap<>();
        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (!"Obs".equals(reader.getLocalName())) {
                    continue;
                }
                String period = reader.getAttributeValue(null, "TIME_PERIOD");
                String value = reader.getAttributeValue(null, "OBS_VALUE");
                Integer key = parsePeriod(period);
                if (key == null || value == null || value.isBlank()) {
                    continue;
                }
                try {
                    values.put(key, new BigDecimal(value));
                } catch (NumberFormatException ignored) {
                    // A single unparseable observation should not void the whole series.
                }
            }
        } finally {
            reader.close();
        }
        return values;
    }

    /** {@code 2026-Q1} to 20261. Returns null for annual or monthly periods. */
    private static Integer parsePeriod(String period) {
        if (period == null || period.length() != 7 || period.charAt(4) != '-' || period.charAt(5) != 'Q') {
            return null;
        }
        try {
            int year = Integer.parseInt(period.substring(0, 4));
            int quarter = Integer.parseInt(period.substring(6));
            if (quarter < 1 || quarter > 4) {
                return null;
            }
            return year * 10 + quarter;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record CachedSeries(NavigableMap<Integer, BigDecimal> values, Instant fetchedAt) {
        boolean isExpired() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) > 0;
        }
    }
}
