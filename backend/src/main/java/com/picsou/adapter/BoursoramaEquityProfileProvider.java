package com.picsou.adapter;

import com.picsou.dto.EquityProfile;
import com.picsou.port.EquityProfileProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a listed company's country from its Boursorama quote page.
 *
 * <p>The country comes from the <strong>ISIN</strong>, not from the page's visible text: the
 * analytics block carries {@code "fv_code_isin":"US0378331005_AAPL"}, and the first two
 * characters of an ISIN are its ISO country of issuance. That works for a Paris listing and a
 * US one alike, and it recovers an identifier ingestion throws away — holdings store a Yahoo
 * ticker, never the ISIN it was converted from.
 *
 * <p>This provider deliberately does <em>not</em> return a sector. The same block carries
 * {@code fv_secteur_activite}, but it reads {@code n-d} for anything outside Euronext and, where
 * present, gives a sub-industry ("Chimie de base") that does not merge with the taxonomy ETF
 * slices use. {@code YahooFinancePriceProvider} answers that half.
 *
 * <p>The country is the issuer's <em>domicile</em>, which is not where its revenue comes from —
 * Airbus is NL, Accenture IE. See the ADR; the flag on {@link EquityProfile} carries it so the
 * UI can say so.
 */
@Component
public class BoursoramaEquityProfileProvider implements EquityProfileProvider {

    private static final Logger log = LoggerFactory.getLogger(BoursoramaEquityProfileProvider.class);
    private static final String SOURCE = "Boursorama";

    private static final Pattern ISIN = Pattern.compile("\"fv_code_isin\"\\s*:\\s*\"([A-Z]{2}[A-Z0-9]{9}\\d)");
    /** ISO 3166-1 alpha-2, plus the two supranational prefixes ISINs use. */
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    private final BoursoramaClient client;

    public BoursoramaEquityProfileProvider(BoursoramaClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(String ticker) {
        return ticker != null && !ticker.isBlank();
    }

    @Override
    public Optional<EquityProfile> fetch(String ticker) {
        if (!supports(ticker)) return Optional.empty();
        try {
            Optional<String> symbol = client.resolveSymbol(ticker);
            if (symbol.isEmpty()) {
                log.debug("Boursorama: no symbol resolved for {}", ticker);
                return Optional.empty();
            }
            Optional<String> html = client.get("/cours/{s}/", symbol.get());
            if (html.isEmpty()) return Optional.empty();
            return countryOf(html.get(), symbol.get())
                .map(country -> new EquityProfile(null, country, SOURCE, true));
        } catch (Exception ex) {
            log.warn("Boursorama equity profile fetch failed for {}: {}", ticker, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The ISO country of the ISIN on the page, provided the page is actually about the symbol
     * that was asked for.
     *
     * <p>That guard is not paranoia: a wrong symbol does <em>not</em> 404 here. Requesting one
     * serves a generic news-listing page with a 200, and parsing whatever ISIN happens to appear
     * on it would file a position under some unrelated company's country. "The page loaded" is
     * not "the page is about this security".
     */
    static Optional<String> countryOf(String html, String symbol) {
        if (html == null || symbol == null) return Optional.empty();
        if (!html.contains("\"fv_symb_societe\":\"" + symbol + "\"")) {
            return Optional.empty();
        }
        Matcher m = ISIN.matcher(html);
        if (!m.find()) return Optional.empty();
        String country = m.group(1).substring(0, 2).toUpperCase(Locale.ROOT);
        return COUNTRY_CODE.matcher(country).matches() ? Optional.of(country) : Optional.empty();
    }
}
