package com.picsou.adapter;

import com.picsou.dto.EtfComposition;
import com.picsou.dto.FundFacts;
import com.picsou.dto.SecurityRef;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.DistributionPolicy;
import com.picsou.model.Replication;
import com.picsou.port.EtfCompositionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a fund's fee, distribution policy and index breakdown from justETF, by ISIN.
 *
 * <p>Second in line behind Boursorama, which publishes up to ten slices per axis with no
 * residual; justETF's server-rendered page gives the top four plus an explicit "Other". Ordering
 * it first would lower every sector score for no gain in truth. What it adds that no other source
 * here has is the fee and the distribution policy, and a fallback for funds Boursorama misses.
 *
 * <p>Yahoo's {@code quoteSummary} would have been the generic answer and does not work: it
 * returns {@code 401 Invalid Crumb}, and the cookie handshake that is supposed to mint one
 * answers "Too Many Requests". Measured, not assumed — see the ADR.
 *
 * <p>Parsing keys on {@code data-testid} rather than the English labels beside them. The labels
 * move with the locale of the URL; the testids do not.
 */
@Component
@Order(200)
public class JustEtfProvider implements EtfCompositionProvider {

    private static final Logger log = LoggerFactory.getLogger(JustEtfProvider.class);
    private static final String SOURCE = "justETF";
    private static final int TOP_COMPANIES = 10;

    /**
     * The node that says which fund the page is about.
     *
     * <p>Load-bearing: justETF answers an ISIN it does not know with {@code 200} and its ETF
     * screener, not a {@code 404}. "The page loaded" is not "the page is about this security" —
     * the same trap Boursorama sets, with a friendlier face.
     */
    private static final String ISIN_TESTID = "etf-profile-header_isin-value";

    private static final Pattern AS_OF =
        Pattern.compile("data-testid=\"tl_etf-holdings_reference-date\"[^>]*>\\s*As of\\s*([0-9/]+)");
    private static final DateTimeFormatter AS_OF_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    private final JustEtfClient client;

    public JustEtfProvider(JustEtfClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(SecurityRef ref) {
        return ref != null && OpenFigiIsinConverter.isIsin(ref.isin());
    }

    @Override
    public Optional<EtfComposition> fetch(SecurityRef ref) {
        if (!supports(ref)) return Optional.empty();
        try {
            return client.profile(ref.isin()).flatMap(html -> parse(html, ref.isin()));
        } catch (Exception ex) {
            log.warn("justETF fetch failed for {}: {}", ref.isin(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** The whole testable core: no I/O, so the fixtures exercise exactly what production runs. */
    static Optional<EtfComposition> parse(String html, String isin) {
        if (html == null || isin == null) return Optional.empty();
        if (!html.contains("data-testid=\"" + ISIN_TESTID + "\">" + isin + "<")) {
            log.debug("justETF: page is not about {} — refusing it", isin);
            return Optional.empty();
        }

        List<WeightedSlice> countries = axis(html, "countries", JustEtfLabels::countryKey);
        List<WeightedSlice> sectors = axis(html, "sectors", JustEtfLabels::sectorKey);
        List<WeightedSlice> companies = companies(html);
        FundFacts facts = facts(html);

        if (countries.isEmpty() && sectors.isEmpty() && companies.isEmpty() && facts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EtfComposition(
            companies, countries, sectors, SOURCE, asOf(html), facts.isEmpty() ? null : facts));
    }

    // --- parsing ----------------------------------------------------------

    private static FundFacts facts(String html) {
        return new FundFacts(
            percent(value(html, "tl_etf-basics_value_ter").orElse(null)),
            value(html, "tl_etf-basics_value_distribution-policy")
                .map(v -> v.toLowerCase().startsWith("accum")
                    ? DistributionPolicy.ACCUMULATING : DistributionPolicy.DISTRIBUTING)
                .orElse(null),
            value(html, "tl_etf-basics_value_replication")
                .map(v -> v.toLowerCase().startsWith("synth")
                    ? Replication.SYNTHETIC : Replication.PHYSICAL)
                .orElse(null),
            value(html, "tl_etf-basics_value_domicile-country")
                .map(JustEtfLabels::countryKey).orElse(null),
            SOURCE);
    }

    /**
     * One axis of the breakdown.
     *
     * <p>The residual row is dropped rather than stored. justETF names it, so the share it
     * covers is known to be unallocated — keeping it as a slice would claim a distribution we do
     * not have, and the diversification service counts what is not placed as unclassified.
     */
    private static List<WeightedSlice> axis(String html, String axis,
                                            java.util.function.Function<String, String> toKey) {
        Pattern row = Pattern.compile(
            "data-testid=\"tl_etf-holdings_" + axis + "_value_name\"[^>]*>([^<]*)<"
                + ".*?data-testid=\"tl_etf-holdings_" + axis + "_value_percentage\"[^>]*>([^<]*)<",
            Pattern.DOTALL);

        List<WeightedSlice> slices = new ArrayList<>();
        Matcher m = row.matcher(html);
        while (m.find()) {
            String label = HtmlUtils.htmlUnescape(m.group(1)).trim();
            if (JustEtfLabels.isOther(label)) continue;
            String key = toKey.apply(label);
            BigDecimal pct = percent(m.group(2));
            if (key != null && pct != null) slices.add(new WeightedSlice(key, pct));
        }
        return slices;
    }

    private static List<WeightedSlice> companies(String html) {
        Pattern row = Pattern.compile(
            "data-testid=\"etf-holdings_top-holdings_row\">(.*?)</tr>", Pattern.DOTALL);
        Pattern pct = Pattern.compile(
            "data-testid=\"tl_etf-holdings_top-holdings_value_percentage\"[^>]*>([^<]*)<");
        Pattern name = Pattern.compile(">([^<>]{2,80})</a>|<td[^>]*>\\s*([^<>]{2,80}?)\\s*</td>");

        List<WeightedSlice> out = new ArrayList<>();
        Matcher rows = row.matcher(html);
        while (rows.find() && out.size() < TOP_COMPANIES) {
            String cell = rows.group(1);
            Matcher p = pct.matcher(cell);
            Matcher n = name.matcher(cell);
            if (!p.find() || !n.find()) continue;
            String label = HtmlUtils.htmlUnescape(n.group(1) != null ? n.group(1) : n.group(2)).trim();
            BigDecimal value = percent(p.group(1));
            if (!label.isEmpty() && value != null) out.add(new WeightedSlice(label, value));
        }
        return out;
    }

    private static Optional<String> value(String html, String testId) {
        Matcher m = Pattern.compile("data-testid=\"" + Pattern.quote(testId) + "\"[^>]*>\\s*([^<]*?)\\s*<")
            .matcher(html);
        if (!m.find()) return Optional.empty();
        String raw = HtmlUtils.htmlUnescape(m.group(1)).trim();
        return raw.isEmpty() || "-".equals(raw) ? Optional.empty() : Optional.of(raw);
    }

    /** "0.38% p.a." and "69.70%" alike; null when there is no number to read. */
    private static BigDecimal percent(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("(-?[0-9]+(?:[.,][0-9]+)?)").matcher(raw);
        if (!m.find()) return null;
        try {
            return new BigDecimal(m.group(1).replace(',', '.')).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate asOf(String html) {
        Matcher m = AS_OF.matcher(html);
        if (!m.find()) return null;
        try {
            return LocalDate.parse(m.group(1), AS_OF_FORMAT);
        } catch (Exception ex) {
            return null;
        }
    }
}
