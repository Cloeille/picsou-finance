package com.picsou.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.model.PreciousMetal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreciousMetalPriceProviderTest {

    @Mock YahooFinancePriceProvider yahoo;

    private final Logger providerLogger = (Logger) LoggerFactory.getLogger(PreciousMetalPriceProvider.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        providerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        providerLogger.detachAppender(logs);
    }

    private List<String> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String fixture() {
        try (var in = PreciousMetalPriceProviderTest.class.getResourceAsStream("/loomis/quotes.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The day after the fixture's quotes, so they are fresh whatever the calendar says. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    private static final LocalDate FIXTURE_QUOTE_DATE = LocalDate.of(2026, 11, 5);

    private PreciousMetalPriceProvider provider(Supplier<Optional<String>> page) {
        return provider(page, TODAY);
    }

    private PreciousMetalPriceProvider provider(Supplier<Optional<String>> page, LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new PreciousMetalPriceProvider(yahoo, page, clock);
    }

    /** One priced product row, in the shape of the live page. */
    private static String row(String slug, String quoteDate, String price) {
        return """
            <div class="raterow"><div class="col col_date">Cotation du <span class="color asc">%s</span> :</div>
            <div class="col col_price">%s</div>
            <a href="https://www.loomis-fxgs.fr/produit/%s/">Détail produit</a></div>
            """.formatted(quoteDate, price, slug);
    }

    // ── parser ──────────────────────────────────────────────────────────

    @Test
    void parseQuotes_readsPricedProductsFromTheFixture() {
        Map<String, BigDecimal> quotes = PreciousMetalPriceProvider.parseQuotes(fixture(), TODAY);

        assertThat(quotes)
            .containsEntry("20-francs-napoleon", new BigDecimal("709.90"))
            // &nbsp; as thousands separator
            .containsEntry("krugerrand-sud-afrique", new BigDecimal("3875.00"))
            // Two nbsp: thousands separator and the one before the euro sign.
            .containsEntry("lingot", new BigDecimal("118000.00"));
    }

    @Test
    void parseQuotes_aSuspendedQuoteIsAbsentNotZero() {
        assertThat(PreciousMetalPriceProvider.parseQuotes(fixture(), TODAY))
            .doesNotContainKey("10-dollars")
            .hasSize(3);
    }

    @Test
    void parseQuotes_pageWithoutRowsGivesNothing_andSaysSo() {
        assertThat(PreciousMetalPriceProvider.parseQuotes("<html><body>maintenance</body></html>", TODAY)).isEmpty();

        assertThat(warnings()).singleElement().asString().contains("Loomis FXGS").contains("no readable quote");
    }

    @Test
    void parseQuotes_dropsAQuoteDatedMoreThanAWeekBack_andWarns() {
        String html = row("20-francs-napoleon", "01/10/2026", "709,90&nbsp;€")
            + row("krugerrand-sud-afrique", "05/11/2026", "3&nbsp;875,00&nbsp;€");

        assertThat(PreciousMetalPriceProvider.parseQuotes(html, TODAY))
            .containsOnlyKeys("krugerrand-sud-afrique");

        assertThat(warnings()).singleElement().asString()
            .contains("Loomis FXGS").contains("20-francs-napoleon").contains("2026-10-01");
    }

    @Test
    void parseQuotes_aWeekOldQuoteIsStillFresh_aDayOlderIsNot() {
        String html = row("20-francs-napoleon", "05/11/2026", "709,90&nbsp;€");

        assertThat(PreciousMetalPriceProvider.parseQuotes(html, FIXTURE_QUOTE_DATE.plusDays(7)))
            .containsKey("20-francs-napoleon");
        assertThat(PreciousMetalPriceProvider.parseQuotes(html, FIXTURE_QUOTE_DATE.plusDays(8))).isEmpty();
    }

    @Test
    void parseQuotes_aRowWithoutAReadableDateKeepsItsQuote() {
        String html = row("20-francs-napoleon", "n/a", "709,90&nbsp;€")
            + row("krugerrand-sud-afrique", "31/02/2026", "3&nbsp;875,00&nbsp;€");

        assertThat(PreciousMetalPriceProvider.parseQuotes(html, TODAY))
            .containsOnlyKeys("20-francs-napoleon", "krugerrand-sud-afrique");
        assertThat(warnings()).isEmpty();
    }

    @Test
    void getPricesEur_aFrozenPageLeavesCoinsUnpriced() {
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()), FIXTURE_QUOTE_DATE.plusDays(30));

        assertThat(provider.getPricesEur(Set.of("GOLD:NAPOLEON-20F", "GOLD:KRUGERRAND"))).isEmpty();
    }

    @Test
    void parseQuotes_aPageWithQuotesLogsNothing() {
        PreciousMetalPriceProvider.parseQuotes(fixture(), TODAY);

        assertThat(warnings()).isEmpty();
    }

    @Test
    void parseFrenchAmount_acceptsEveryThousandsSeparatorFlavour() {
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount(" 3&nbsp;875,00&nbsp;€ "))
            .contains(new BigDecimal("3875.00"));
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("3\u00A0875,00\u00A0€"))
            .contains(new BigDecimal("3875.00"));
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("3\u202F875,00\u202F€"))
            .contains(new BigDecimal("3875.00"));
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("3 875,00 €"))
            .contains(new BigDecimal("3875.00"));
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("709,90 €"))
            .contains(new BigDecimal("709.90"));
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("1 234 567,5 €"))
            .contains(new BigDecimal("1234567.5"));
    }

    @Test
    void parseFrenchAmount_rejectsGarbageAndZero() {
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("Cotation suspendue")).isEmpty();
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("")).isEmpty();
        assertThat(PreciousMetalPriceProvider.parseFrenchAmount("0,00 €")).isEmpty();
    }

    // ── supports ────────────────────────────────────────────────────────

    @Test
    void supports_onlyTheRegistry_caseInsensitive() {
        PreciousMetalPriceProvider provider = provider(Optional::empty);

        assertThat(provider.supports("GOLD:GRAM")).isTrue();
        assertThat(provider.supports("gold:krugerrand")).isTrue();
        assertThat(provider.supports("GOLD:UNKNOWN")).isFalse();
        assertThat(provider.supports("AAPL")).isFalse();
        assertThat(provider.supports("BTC")).isFalse();
        assertThat(provider.supports(null)).isFalse();
        assertThat(provider.supportsCrypto("GOLD:GRAM")).isFalse();
    }

    // ── getPricesEur ────────────────────────────────────────────────────

    @Test
    void getPricesEur_mixesCoinsFromLoomisAndTheGramFromTheSpot() {
        when(yahoo.getPricesEur(Set.of("GC=F"))).thenReturn(Map.of("GC=F", new BigDecimal("3500.00")));
        AtomicInteger fetches = new AtomicInteger();
        PreciousMetalPriceProvider provider = provider(() -> {
            fetches.incrementAndGet();
            return Optional.of(fixture());
        });

        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of(
            "GOLD:GRAM", "GOLD:NAPOLEON-20F", "GOLD:KRUGERRAND", "GOLD:USD-10"));

        assertThat(prices).containsOnlyKeys("GOLD:GRAM", "GOLD:NAPOLEON-20F", "GOLD:KRUGERRAND");
        assertThat(prices.get("GOLD:NAPOLEON-20F")).isEqualByComparingTo("709.90");
        assertThat(prices.get("GOLD:KRUGERRAND")).isEqualByComparingTo("3875.00");
        // 3500.00 / 31.1034768, rounded half-up to the NUMERIC(20, 8) scale of price_snapshot.
        assertThat(prices.get("GOLD:GRAM")).isEqualTo(new BigDecimal("112.52761299"));
        // One page fetch prices every coin of the batch.
        assertThat(fetches).hasValue(1);
    }

    @Test
    void getPricesEur_keysEachPriceByTheRequestedSpelling() {
        when(yahoo.getPricesEur(Set.of("GC=F"))).thenReturn(Map.of("GC=F", new BigDecimal("3500.00")));
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()));

        // PriceService uppercases a ticker but never trims it, then reads the price back under
        // that same string: the canonical key would leave the position unpriced.
        Map<String, BigDecimal> prices = provider.getPricesEur(Set.of(
            " GOLD:GRAM ", " GOLD:KRUGERRAND", "GOLD:KRUGERRAND", "gold:napoleon-20f"));

        assertThat(prices).containsOnlyKeys(
            " GOLD:GRAM ", " GOLD:KRUGERRAND", "GOLD:KRUGERRAND", "gold:napoleon-20f");
        assertThat(prices.get(" GOLD:GRAM ")).isEqualTo(new BigDecimal("112.52761299"));
        assertThat(prices.get(" GOLD:KRUGERRAND")).isEqualByComparingTo("3875.00");
        assertThat(prices.get("GOLD:KRUGERRAND")).isEqualByComparingTo("3875.00");
        assertThat(prices.get("gold:napoleon-20f")).isEqualByComparingTo("709.90");
    }

    @Test
    void getPricesEur_gramOnly_neverFetchesTheCoinPage() {
        when(yahoo.getPricesEur(Set.of("GC=F"))).thenReturn(Map.of("GC=F", new BigDecimal("3499.99")));
        PreciousMetalPriceProvider provider = provider(() -> {
            throw new AssertionError("the coin page must not be fetched");
        });

        assertThat(provider.getPricesEur(Set.of("gold:gram")))
            .containsExactly(Map.entry("gold:gram", new BigDecimal("112.52729148")));
    }

    @Test
    void getPricesEur_coinsOnly_neverAsksYahoo() {
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()));

        assertThat(provider.getPricesEur(Set.of("GOLD:NAPOLEON-20F"))).hasSize(1);
        verifyNoInteractions(yahoo);
    }

    @Test
    void getPricesEur_ignoresTickersOutsideTheRegistry() {
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()));

        assertThat(provider.getPricesEur(Set.of("AAPL", "GOLD:NOPE"))).isEmpty();
        verifyNoInteractions(yahoo);
    }

    @Test
    void getPricesEur_aProductMissingFromThePageIsOmitted() {
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()));

        assertThat(provider.getPricesEur(Set.of("GOLD:PESO-50", "GOLD:NAPOLEON-20F")))
            .containsOnlyKeys("GOLD:NAPOLEON-20F");
    }

    @Test
    void getPricesEur_aFailedFetchReturnsAnEmptyMapWithoutThrowing() {
        PreciousMetalPriceProvider emptyBody = provider(Optional::empty);
        PreciousMetalPriceProvider throwing = provider(() -> {
            throw new IllegalStateException("boom");
        });

        assertThat(emptyBody.getPricesEur(Set.of("GOLD:NAPOLEON-20F"))).isEmpty();
        assertThatCode(() -> assertThat(throwing.getPricesEur(Set.of("GOLD:NAPOLEON-20F"))).isEmpty())
            .doesNotThrowAnyException();
    }

    @Test
    void getPricesEur_coinsSurviveASpotFailure_andTheGramSurvivesACoinFailure() {
        when(yahoo.getPricesEur(Set.of("GC=F"))).thenThrow(new IllegalStateException("yahoo down"));
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()));

        assertThat(provider.getPricesEur(Set.of("GOLD:GRAM", "GOLD:KRUGERRAND")))
            .containsOnlyKeys("GOLD:KRUGERRAND");
    }

    @Test
    void getPricesEur_aSpotThatYahooCouldNotQuoteLeavesTheGramOut() {
        when(yahoo.getPricesEur(Set.of("GC=F"))).thenReturn(Map.of());
        PreciousMetalPriceProvider provider = provider(Optional::empty);

        assertThat(provider.getPricesEur(Set.of("GOLD:GRAM"))).isEmpty();
    }

    // ── history ─────────────────────────────────────────────────────────

    @Test
    void historical_gramIsTheSpotHistoryPerGram_coinsAreEmpty() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);
        when(yahoo.getHistoricalPricesEur("GC=F", from, to)).thenReturn(Map.of(
            from, new BigDecimal("3500.00"), to, new BigDecimal("0")));
        PreciousMetalPriceProvider provider = provider(Optional::empty);

        assertThat(provider.getHistoricalPricesEur("GOLD:GRAM", from, to))
            .containsExactly(Map.entry(from, new BigDecimal("112.52761299")));
        assertThat(provider.getHistoricalPricesEur("GOLD:KRUGERRAND", from, to)).isEmpty();
        // Only the gram call reached Yahoo; the coin never did.
        verify(yahoo, times(1)).getHistoricalPricesEur(any(), any(), any());
    }

    @Test
    void intraday_gramIsTheSpotPerGram() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 5, 9, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 5, 17, 0);
        when(yahoo.getIntradayPricesEur("GC=F", from, to)).thenReturn(Map.of(from, new BigDecimal("3500.00")));
        PreciousMetalPriceProvider provider = provider(Optional::empty);

        assertThat(provider.getIntradayPricesEur("GOLD:GRAM", from, to))
            .containsExactly(Map.entry(from, new BigDecimal("112.52761299")));
    }

    @Test
    void intraday_aCoinIsItsCurrentQuoteHeldFlat_fromTheHourBeforeFrom() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 5, 9, 40);
        LocalDateTime to = LocalDateTime.of(2026, 11, 6, 9, 40);
        PreciousMetalPriceProvider provider = provider(() -> Optional.of(fixture()));

        Map<LocalDateTime, BigDecimal> series = provider.getIntradayPricesEur(" gold:napoleon-20f ", from, to);

        assertThat(series).hasSize(25);
        assertThat(series.keySet()).first().isEqualTo(LocalDateTime.of(2026, 11, 5, 9, 0));
        assertThat(series.keySet()).last().isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 0));
        assertThat(series.values()).allSatisfy(price -> assertThat(price).isEqualByComparingTo("709.90"));
        // HistoryService's hourly grid starts at `from` truncated to the hour and reads each hour
        // with floorEntry: every hour of it must find a price, the first one included.
        NavigableMap<LocalDateTime, BigDecimal> priced = new TreeMap<>(series);
        for (LocalDateTime hour = from.truncatedTo(ChronoUnit.HOURS); !hour.isAfter(to); hour = hour.plusHours(1)) {
            assertThat(priced.floorEntry(hour)).as(hour.toString()).isNotNull();
        }
        verifyNoInteractions(yahoo);
    }

    @Test
    void intraday_aCoinWithoutAQuoteHasNoSeries() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 5, 9, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 5, 17, 0);

        // PESO-50 is absent from the page; the page itself being unreachable gives nothing either.
        assertThat(provider(() -> Optional.of(fixture())).getIntradayPricesEur("GOLD:PESO-50", from, to)).isEmpty();
        assertThat(provider(Optional::empty).getIntradayPricesEur("GOLD:NAPOLEON-20F", from, to)).isEmpty();
        assertThat(provider(Optional::empty).getIntradayPricesEur("AAPL", from, to)).isEmpty();
    }

    @Test
    void history_upstreamFailureGivesAnEmptyMap() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);
        when(yahoo.getHistoricalPricesEur("GC=F", from, to)).thenThrow(new IllegalStateException("down"));

        assertThat(provider(Optional::empty).getHistoricalPricesEur("GOLD:GRAM", from, to)).isEmpty();
    }

    @Test
    void getFxRateToEur_delegatesToYahoo() {
        when(yahoo.getFxRateToEur("USD")).thenReturn(new BigDecimal("0.92"));

        assertThat(provider(Optional::empty).getFxRateToEur("USD")).isEqualByComparingTo("0.92");
    }

    // ── registry ────────────────────────────────────────────────────────

    @Test
    void registry_everyTickerHasAColonSoYahooNeverSeesIt() {
        YahooFinancePriceProvider realYahoo = YahooFinanceStubs.provider(url -> null);
        for (PreciousMetal metal : PreciousMetal.values()) {
            assertThat(metal.ticker()).startsWith("GOLD:");
            assertThat(realYahoo.supports(metal.ticker())).as(metal.ticker()).isFalse();
        }
        // The spot symbol itself is a valid Yahoo symbol: that is the only one we send.
        assertThat(realYahoo.supports("GC=F")).isTrue();
    }
}
