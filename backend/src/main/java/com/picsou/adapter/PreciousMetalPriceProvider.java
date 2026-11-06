package com.picsou.adapter;

import com.picsou.model.PreciousMetal;
import com.picsou.port.PriceProviderPort;
import com.picsou.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prices the {@link PreciousMetal} registry.
 *
 * <p>The fine gram is the gold spot: Yahoo's {@code GC=F} future (USD per troy ounce, already
 * converted to EUR by the Yahoo adapter) divided by the grams in an ounce. Coins are quoted by
 * Loomis FXGS (formerly CPoR Devises), the French reference for gold coin prices; one public page
 * lists every product, so a batch of coins costs a single request.
 *
 * <p>Loomis publishes no reachable history, so coins have no historical or intraday series. A
 * suspended quote ({@code Cotation suspendue}) is absent from the result, never zero. Upstream
 * failures are logged and omitted, per the {@link PriceProviderPort} contract.
 */
@Component
public class PreciousMetalPriceProvider implements PriceProviderPort {

    private static final Logger log = LoggerFactory.getLogger(PreciousMetalPriceProvider.class);

    private static final String HOST = "https://www.loomis-fxgs.fr";
    private static final String QUOTES_PATH = "/fr/or/cotation-produits/";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    static final String SPOT_SYMBOL = "GC=F";
    /** Grams in one troy ounce. */
    static final BigDecimal GRAMS_PER_TROY_OUNCE = new BigDecimal("31.1034768");
    /** Matches the {@code NUMERIC(20, 8)} price columns. */
    static final int PRICE_SCALE = 8;

    private static final Pattern PRODUCT_LINK = Pattern.compile("/produit/([a-z0-9-]+)/");
    private static final Pattern PRICE_CELL =
        Pattern.compile("class=\"col col_price\">(.*?)</div>", Pattern.DOTALL);
    private static final Pattern ENTITY_SPACE =
        Pattern.compile("&(?:nbsp|#160|#xa0|#8239|#x202f|thinsp|#8201);", Pattern.CASE_INSENSITIVE);
    private static final Pattern FRENCH_AMOUNT = Pattern.compile("\\d+(?:,\\d+)?");

    private final YahooFinancePriceProvider yahoo;
    private final Supplier<Optional<String>> quotesPage;

    @Autowired
    public PreciousMetalPriceProvider(YahooFinancePriceProvider yahoo) {
        this(yahoo, WebClient.builder()
            .baseUrl(HOST)
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .defaultHeader("Accept-Language", "fr-FR")
            .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build());
    }

    // Package-private for tests — inject a WebClient backed by an ExchangeFunction.
    PreciousMetalPriceProvider(YahooFinancePriceProvider yahoo, WebClient webClient) {
        this(yahoo, () -> fetchPage(webClient));
    }

    // Package-private for tests — inject the page body directly.
    PreciousMetalPriceProvider(YahooFinancePriceProvider yahoo, Supplier<Optional<String>> quotesPage) {
        this.yahoo = yahoo;
        this.quotesPage = quotesPage;
    }

    private static Optional<String> fetchPage(WebClient webClient) {
        try {
            String body = webClient.get().uri(QUOTES_PATH)
                .retrieve().bodyToMono(String.class).timeout(TIMEOUT).block();
            return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
        } catch (Exception ex) {
            log.warn("Loomis FXGS quote page fetch failed: {}", LogSanitizer.safe(ex.getMessage()));
            return Optional.empty();
        }
    }

    @Override
    public boolean supports(String ticker) {
        return PreciousMetal.fromTicker(ticker).isPresent();
    }

    @Override
    public boolean supportsCrypto(String ticker) {
        return false;
    }

    /**
     * Prices keyed by the strings in {@code tickers}, as received: {@code PriceService} uppercases
     * a ticker but does not trim it, and reads the result back under that same string, so a
     * padded or lower-case spelling must find its price there rather than under the canonical key.
     */
    @Override
    public Map<String, BigDecimal> getPricesEur(Set<String> tickers) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (tickers == null || tickers.isEmpty()) return result;

        Set<PreciousMetal> coins = EnumSet.noneOf(PreciousMetal.class);
        boolean wantsGram = false;
        for (String ticker : tickers) {
            Optional<PreciousMetal> metal = PreciousMetal.fromTicker(ticker);
            if (metal.isEmpty()) continue;
            if (metal.get().unit() == PreciousMetal.Unit.GRAM) wantsGram = true;
            else coins.add(metal.get());
        }

        Map<PreciousMetal, BigDecimal> priced = new EnumMap<>(PreciousMetal.class);
        if (wantsGram) {
            try {
                BigDecimal ounce = yahoo.getPricesEur(Set.of(SPOT_SYMBOL)).get(SPOT_SYMBOL);
                if (ounce != null && ounce.signum() > 0) priced.put(PreciousMetal.GOLD_GRAM, perGram(ounce));
            } catch (Exception ex) {
                log.warn("Gold spot price failed: {}", LogSanitizer.safe(ex.getMessage()));
            }
        }

        if (!coins.isEmpty()) {
            Map<String, BigDecimal> bySlug = quotesBySlug();
            for (PreciousMetal coin : coins) {
                BigDecimal price = bySlug.get(coin.loomisSlug());
                if (price != null) priced.put(coin, price);
            }
        }

        for (String ticker : tickers) {
            PreciousMetal.fromTicker(ticker).map(priced::get).ifPresent(price -> result.put(ticker, price));
        }
        return result;
    }

    @Override
    public Map<LocalDate, BigDecimal> getHistoricalPricesEur(String ticker, LocalDate from, LocalDate to) {
        if (!isGram(ticker)) return Map.of();
        try {
            Map<LocalDate, BigDecimal> ounces = yahoo.getHistoricalPricesEur(SPOT_SYMBOL, from, to);
            return perGram(ounces);
        } catch (Exception ex) {
            log.warn("Gold spot history failed: {}", LogSanitizer.safe(ex.getMessage()));
            return Map.of();
        }
    }

    @Override
    public Map<LocalDateTime, BigDecimal> getIntradayPricesEur(String ticker, LocalDateTime from, LocalDateTime to) {
        if (!isGram(ticker)) return Map.of();
        try {
            return perGram(yahoo.getIntradayPricesEur(SPOT_SYMBOL, from, to));
        } catch (Exception ex) {
            log.warn("Gold spot intraday failed: {}", LogSanitizer.safe(ex.getMessage()));
            return Map.of();
        }
    }

    @Override
    public BigDecimal getFxRateToEur(String currency) {
        return yahoo.getFxRateToEur(currency);
    }

    private static boolean isGram(String ticker) {
        return PreciousMetal.fromTicker(ticker).filter(m -> m.unit() == PreciousMetal.Unit.GRAM).isPresent();
    }

    private static BigDecimal perGram(BigDecimal perOunce) {
        return perOunce.divide(GRAMS_PER_TROY_OUNCE, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static <K> Map<K, BigDecimal> perGram(Map<K, BigDecimal> perOunce) {
        Map<K, BigDecimal> grams = new LinkedHashMap<>();
        perOunce.forEach((key, value) -> {
            if (value != null && value.signum() > 0) grams.put(key, perGram(value));
        });
        return grams;
    }

    private Map<String, BigDecimal> quotesBySlug() {
        try {
            return quotesPage.get().map(PreciousMetalPriceProvider::parseQuotes).orElse(Map.of());
        } catch (Exception ex) {
            log.warn("Loomis FXGS quote page unusable: {}", LogSanitizer.safe(ex.getMessage()));
            return Map.of();
        }
    }

    /**
     * Product slug to EUR quote, from the Loomis quote page. A product with no price cell
     * (suspended quote) is absent.
     */
    static Map<String, BigDecimal> parseQuotes(String html) {
        Map<String, BigDecimal> quotes = new HashMap<>();
        String[] rows = html.split("<div class=\"raterow\">");
        // rows[0] is everything before the first product.
        for (int i = 1; i < rows.length; i++) {
            Matcher link = PRODUCT_LINK.matcher(rows[i]);
            Matcher price = PRICE_CELL.matcher(rows[i]);
            if (!link.find() || !price.find()) continue;
            parseFrenchAmount(price.group(1)).ifPresent(amount -> quotes.putIfAbsent(link.group(1), amount));
        }
        return quotes;
    }

    /** {@code "3&nbsp;875,00&nbsp;€"} (any space flavour, comma decimal) to {@code 3875.00}. */
    static Optional<BigDecimal> parseFrenchAmount(String cell) {
        String text = ENTITY_SPACE.matcher(cell).replaceAll("")
            .replaceAll("[\\s\\u00A0\\u202F\\u2009€]", "");
        if (!FRENCH_AMOUNT.matcher(text).matches()) return Optional.empty();
        BigDecimal amount = new BigDecimal(text.replace(',', '.'));
        return amount.signum() > 0 ? Optional.of(amount) : Optional.empty();
    }
}
