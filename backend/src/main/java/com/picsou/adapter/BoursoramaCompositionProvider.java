package com.picsou.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityRef;
import com.picsou.dto.WeightedSlice;
import com.picsou.port.EtfCompositionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches ETF composition from Boursorama's public tracker pages — no auth.
 *
 * Two-step flow:
 *  1. Resolve {@code ticker → Boursorama symbol} via the search endpoint, which
 *     302-redirects to {@code /cours/{SYMBOL}/}; the symbol is read from the
 *     Location header. The exchange suffix is stripped first (PUST.PA → PUST).
 *  2. Fetch {@code /bourse/trackers/cours/composition/{SYMBOL}/} and parse two
 *     inline amCharts JSON blocks (regional, sector) plus the {@code c-table-gauge}
 *     holdings table.
 *
 * French sector/country labels are normalised to stable keys via
 * {@link BoursoramaLabels}; the frontend translates them. The page layout is
 * unofficial and may change; failures are swallowed and surface as
 * "composition unavailable" upstream.
 */
@Component
// Ahead of justETF: up to ten slices per axis with no residual, against four plus an explicit
// remainder. Ordering justETF first would lower every sector score without adding truth.
@Order(100)
public class BoursoramaCompositionProvider implements EtfCompositionProvider {

    private static final Logger log = LoggerFactory.getLogger(BoursoramaCompositionProvider.class);
    private static final String SOURCE = "Boursorama";
    private static final int TOP_COMPANIES = 10;

    // Composition lives under /trackers for ETFs; /opcvm is a fallback for funds.
    private static final String[] COMPOSITION_PATHS = {
        "/bourse/trackers/cours/composition/{s}/",
        "/bourse/opcvm/cours/composition/{s}/"
    };

    // NOTE: the [^\]]* capture truncates the array if a label contains a literal ']';
    // readTree() then throws and toSlices() returns empty (fail-soft, acceptable).
    private static final Pattern AMCHART = Pattern.compile(
        "\"id\":\"(regional|sector)\".*?\"amChartData\":(\\[[^\\]]*\\])", Pattern.DOTALL);
    private static final Pattern HOLDING_ROW = Pattern.compile(
        "c-table-gauge__cell--header\">\\s*(.*?)\\s*</td>.*?data-gauge-current-step=\"([0-9.,]+)\"",
        Pattern.DOTALL);
    private static final Pattern AS_OF = Pattern.compile(
        "Date du portefeuille\\s*:\\s*(\\d{2})/(\\d{2})/(\\d{4})");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BoursoramaClient client;

    public BoursoramaCompositionProvider(BoursoramaClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(SecurityRef ref) {
        return ref != null && ref.preferredIdentifier() != null
            && !ref.preferredIdentifier().isBlank();
    }

    @Override
    public Optional<EtfComposition> fetch(SecurityRef ref) {
        if (!supports(ref)) return Optional.empty();
        // The ISIN when we have one. Boursorama's search resolves it to the same symbol as the
        // ticker (LU1681043599 and CW8 both redirect to /cours/1rTCW8/), and it is the only one
        // that works when OpenFIGI picked a US OTC ticker for a European fund -- which it prefers
        // to do, and which is why so many ETFs resolved to nothing here.
        String identifier = ref.preferredIdentifier();
        try {
            Optional<String> symbol = client.resolveSymbol(identifier);
            if (symbol.isEmpty()) {
                log.debug("Boursorama: no symbol resolved for {}", identifier);
                return Optional.empty();
            }
            Optional<String> html = fetchCompositionHtml(symbol.get());
            if (html.isEmpty()) {
                log.debug("Boursorama: no composition page for {} ({})", identifier, symbol.get());
                return Optional.empty();
            }
            return Optional.of(parse(html.get()));
        } catch (Exception ex) {
            log.warn("Boursorama composition fetch failed for {}: {}", identifier, ex.getMessage());
            return Optional.empty();
        }
    }

    // --- network ----------------------------------------------------------

    private Optional<String> fetchCompositionHtml(String symbol) {
        for (String template : COMPOSITION_PATHS) {
            Optional<String> html = client.get(template, symbol);
            if (html.isPresent() && html.get().contains("amChartData")) {
                return html;
            }
        }
        return Optional.empty();
    }

    // --- parsing (the testable core) --------------------------------------

    static Optional<String> symbolFromLocation(String location) {
        return BoursoramaClient.symbolFromLocation(location);
    }

    /** Strip the exchange suffix from a ticker ("PUST.PA" → "PUST"). */
    static String bareTicker(String ticker) {
        return BoursoramaClient.bareTicker(ticker);
    }

    static EtfComposition parse(String html) {
        List<WeightedSlice> countries = List.of();
        List<WeightedSlice> sectors = List.of();
        Matcher m = AMCHART.matcher(html);
        while (m.find()) {
            String id = m.group(1);
            if (id.equals("regional")) {
                countries = toSlices(m.group(2), BoursoramaLabels::countryKey);
            } else {
                sectors = toSlices(m.group(2), BoursoramaLabels::sectorKey);
            }
        }
        List<WeightedSlice> companies = parseHoldings(html);
        return new EtfComposition(companies, countries, sectors, SOURCE, parseAsOf(html));
    }

    private static List<WeightedSlice> toSlices(String jsonArray, Function<String, String> keyFn) {
        List<WeightedSlice> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(jsonArray);
            for (JsonNode node : arr) {
                String name = node.path("name").asText(null);
                JsonNode value = node.get("value");
                if (name == null || name.isBlank() || value == null || !value.isNumber()) continue;
                out.add(new WeightedSlice(keyFn.apply(name), scale(value.decimalValue())));
            }
        } catch (Exception ex) {
            log.debug("Boursorama amChart parse failed: {}", ex.getMessage());
        }
        return out;
    }

    private static List<WeightedSlice> parseHoldings(String html) {
        List<WeightedSlice> out = new ArrayList<>();
        Matcher m = HOLDING_ROW.matcher(html);
        while (m.find() && out.size() < TOP_COMPANIES) {
            String name = HtmlUtils.htmlUnescape(m.group(1)).trim();
            if (name.isEmpty() || isSwapLine(name)) continue;
            BigDecimal weight = parseWeight(m.group(2));
            if (weight == null) continue;
            out.add(new WeightedSlice(name, weight));
        }
        return out;
    }

    /** Synthetic ETFs list only their swap as a holding — not a real constituent. */
    private static boolean isSwapLine(String name) {
        String u = name.toUpperCase();
        return u.startsWith("TRS ") || u.contains("SWAP");
    }

    private static BigDecimal parseWeight(String raw) {
        try {
            return scale(new BigDecimal(raw.replace(",", ".")));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseAsOf(String html) {
        Matcher m = AS_OF.matcher(html);
        if (!m.find()) return null;
        try {
            return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (DateTimeException | NumberFormatException ex) {
            log.debug("Boursorama composition as-of date '{}' is not a valid date", m.group(0));
            return null;
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
