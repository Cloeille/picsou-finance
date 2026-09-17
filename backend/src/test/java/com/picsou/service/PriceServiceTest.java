package com.picsou.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.picsou.model.PriceSnapshot;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins two contracts.
 *
 * <p><b>The resolution chain.</b> A price the provider cannot deliver right now must fall back to
 * the last one recorded in {@code price_snapshot}, and a failed attempt must not be retried on
 * every read. Without either, one rate-limited morning blanked the largest positions of an
 * account — which then reported a large loss, because the value side dropped those assets while
 * the cost side kept them — and every page render answered the rate limit with more requests.
 *
 * <p><b>The per-ticker guard in {@code backfillHistoricalPrices}</b>, which exists for two reasons
 * that are easy to undo by accident. It must <b>continue</b>: this runs from
 * {@code PriceBackfillRunner}, an {@code ApplicationRunner}, so an escaping exception fails Spring
 * Boot startup outright. And it must log at <b>ERROR</b>: the price providers swallow expected
 * upstream failures and return an empty map, so anything thrown here is a genuine bug. Logging it
 * at WARN would re-hide precisely what {@code CoinGeckoPriceProvider} was changed to rethrow.
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock PriceProviderPort priceProvider;
    @Mock PriceSnapshotRepository priceSnapshotRepository;

    @InjectMocks PriceService priceService;

    private ListAppender<ILoggingEvent> logs;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PriceService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    /**
     * refreshPrices must honor the 15-minute cache TTL: GET /prices is polled
     * by the frontend on an interval, so serving fresh cache entries (instead
     * of re-fetching upstream every call) is what keeps an open dashboard tab
     * from hammering Yahoo/CoinGecko.
     */
    @Test
    void refreshPrices_servesFreshCacheWithoutUpstreamCall() {
        when(priceProvider.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        Map<String, BigDecimal> first = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(first).containsEntry("AAPL", new BigDecimal("150"));

        Map<String, BigDecimal> second = priceService.refreshPrices(Set.of("AAPL"));
        assertThat(second).containsEntry("AAPL", new BigDecimal("150"));

        verify(priceProvider, times(1)).getPricesEur(anySet());
        verify(priceSnapshotRepository, times(1)).save(any());
    }

    @Test
    void refreshPrices_fetchesOnlyMissingTickers() {
        when(priceProvider.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("150")));
        lenient().when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());
        priceService.refreshPrices(Set.of("AAPL"));

        when(priceProvider.getPricesEur(Set.of("MSFT"))).thenReturn(Map.of("MSFT", new BigDecimal("410")));

        Map<String, BigDecimal> result = priceService.refreshPrices(Set.of("AAPL", "MSFT", "EUR"));

        assertThat(result)
            .containsEntry("AAPL", new BigDecimal("150"))
            .containsEntry("MSFT", new BigDecimal("410"))
            .containsEntry("EUR", BigDecimal.ONE);
        verify(priceProvider).getPricesEur(Set.of("MSFT"));
    }

    private PriceSnapshot snapshot(String ticker, LocalDate date, String price) {
        return PriceSnapshot.builder().ticker(ticker).date(date).priceEur(new BigDecimal(price)).build();
    }

    @Test
    void price_fallsBackToTheLastRecordedOne_whenTheProviderReturnsNothing() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(priceProvider.supports("BTC")).thenReturn(true);
        when(priceProvider.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("BTC")), any(), any()))
            .thenReturn(List.of(snapshot("BTC", yesterday, "54619")));

        assertThat(priceService.getPriceEur("BTC")).isEqualByComparingTo("54619");
    }

    @Test
    void quote_carriesTheRecordedDate_andSaysItIsNotLive() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(priceProvider.supports("SOL")).thenReturn(true);
        when(priceProvider.getPricesEur(Set.of("SOL"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("SOL")), any(), any()))
            .thenReturn(List.of(snapshot("SOL", yesterday, "63.20")));

        PriceService.Quote quote = priceService.getCryptoQuote("SOL");

        // The client shows the figure and marks it -- both halves need the date and the flag.
        assertThat(quote).isNotNull();
        assertThat(quote.price()).isEqualByComparingTo("63.20");
        assertThat(quote.asOf()).isEqualTo(yesterday);
        assertThat(quote.live()).isFalse();
    }

    @Test
    void quote_fromTheProvider_isLiveAndDatedToday() {
        when(priceProvider.supports("BTC")).thenReturn(true);
        when(priceProvider.getPricesEur(Set.of("BTC"))).thenReturn(Map.of("BTC", new BigDecimal("54619")));

        PriceService.Quote quote = priceService.getCryptoQuote("BTC");

        assertThat(quote.live()).isTrue();
        assertThat(quote.asOf()).isEqualTo(LocalDate.now());
        verifyNoInteractions(priceSnapshotRepository);
    }

    @Test
    void fallback_onlyLooksBackAWeek() {
        when(priceProvider.supports("BTC")).thenReturn(true);
        when(priceProvider.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(any(), any(), any())).thenReturn(List.of());

        assertThat(priceService.getPriceEur("BTC")).isNull();

        // The age limit is enforced by the query, so that is where it has to be asserted. A
        // month-old crypto price is not a stale number, it is a wrong one.
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        verify(priceSnapshotRepository).findRecentByTickers(any(), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(LocalDate.now().minusDays(7));
    }

    @Test
    void aFailedLookupIsNotRetriedOnEveryRead() {
        when(priceProvider.supports("BTC")).thenReturn(true);
        when(priceProvider.getPricesEur(Set.of("BTC"))).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(any(), any(), any())).thenReturn(List.of());

        priceService.getPriceEur("BTC");
        priceService.getPriceEur("BTC");
        priceService.getPriceEur("BTC");

        // Three reads, one attempt. The opposite -- a request per read -- is what kept an
        // instance rate-limited for hours after a momentary 429.
        verify(priceProvider, times(1)).getPricesEur(Set.of("BTC"));
    }

    @Test
    void aSetOfTickersCostsOneProviderCall() {
        Set<String> tickers = Set.of("BTC", "ETH", "SOL");
        tickers.forEach(t -> when(priceProvider.supports(t)).thenReturn(true));
        when(priceProvider.getPricesEur(any())).thenReturn(Map.of(
            "BTC", new BigDecimal("54619"),
            "ETH", new BigDecimal("1619"),
            "SOL", new BigDecimal("63.20")));

        assertThat(priceService.getCryptoQuotes(tickers)).containsOnlyKeys("BTC", "ETH", "SOL");

        verify(priceProvider, times(1)).getPricesEur(any());
    }

    @Test
    void cryptoLookupOfAnUnmappedTicker_neverReachesTheRecordedPrices() {
        when(priceProvider.supports("STX")).thenReturn(false);

        assertThat(priceService.getCryptoQuote("STX")).isNull();

        // price_snapshot is keyed by ticker alone, exactly like the cache: reading it here would
        // hand back the share price of the equity trading under the same symbol.
        verifyNoInteractions(priceSnapshotRepository);
        verify(priceProvider, org.mockito.Mockito.never()).getPricesEur(any());
    }

    @Test
    void refreshCryptoQuotes_valuesFromTheRecordedPrice_withoutRerecordingItAsToday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        when(priceProvider.supports("ATOM")).thenReturn(true);
        when(priceProvider.getPricesEur(any())).thenReturn(Map.of());
        when(priceSnapshotRepository.findRecentByTickers(eq(Set.of("ATOM")), any(), any()))
            .thenReturn(List.of(snapshot("ATOM", yesterday, "1.065")));

        Map<String, PriceService.Quote> quotes = priceService.refreshCryptoQuotes(Set.of("ATOM"));

        assertThat(quotes.get("ATOM").price()).isEqualByComparingTo("1.065");
        assertThat(quotes.get("ATOM").live()).isFalse();
        // Writing the fallback back under today's date would launder a stale price into a fresh
        // one and let the fallback walk itself forward indefinitely.
        verify(priceSnapshotRepository, org.mockito.Mockito.never()).save(any());
    }

    /** A row every {@code stepDays} from {@code from} up to today, ordered as the query returns. */
    private List<PriceSnapshot> dailyHistory(String ticker, LocalDate from, int stepDays) {
        List<PriceSnapshot> rows = new java.util.ArrayList<>();
        for (LocalDate date = from; !date.isAfter(LocalDate.now()); date = date.plusDays(stepDays)) {
            rows.add(snapshot(ticker, date, "50000"));
        }
        return rows;
    }

    @Test
    void backfill_skipsATickerWhoseHistoryIsAlreadyThere() {
        LocalDate from = LocalDate.now().minusMonths(12);
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("BTC")), any(), any()))
            .thenReturn(dailyHistory("BTC", from, 1));

        assertThat(priceService.backfillHistoricalPrices(Set.of("BTC"), from)).isZero();

        // This runs at every boot, once per held ticker, against a free tier that counts
        // requests: re-fetching twelve months only to discard every row as a duplicate is what
        // exhausted the rate limit seconds after startup.
        verify(priceProvider, org.mockito.Mockito.never()).getHistoricalPricesEur(any(), any(), any());
    }

    @Test
    void backfill_toleratesTheGapsClosedMarketsLeave() {
        // Equities have no weekend rows, and an Easter or Christmas week stretches that further.
        // Treating those as holes would re-request every stock's full year at every boot.
        LocalDate from = LocalDate.now().minusMonths(12);
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("AAPL")), any(), any()))
            .thenReturn(dailyHistory("AAPL", from, 5));

        assertThat(priceService.backfillHistoricalPrices(Set.of("AAPL"), from)).isZero();

        verify(priceProvider, org.mockito.Mockito.never()).getHistoricalPricesEur(any(), any(), any());
    }

    @Test
    void backfill_refillsAHoleInTheMiddleOfAnExistingHistory() {
        // The failure the edge-probe version could not see: an instance offline for months has
        // history on both sides of the outage, so probing the two ends declares it covered and
        // the hole is never filled — the history chart then flat-lines across it forever.
        LocalDate from = LocalDate.now().minusMonths(12);
        List<PriceSnapshot> withHole = new java.util.ArrayList<>();
        withHole.addAll(dailyHistory("BTC", from, 1).subList(0, 30));
        withHole.add(snapshot("BTC", LocalDate.now(), "54619"));
        when(priceSnapshotRepository.findByTickerInAndDateBetween(eq(Set.of("BTC")), any(), any()))
            .thenReturn(withHole);
        when(priceProvider.supports("BTC")).thenReturn(true);
        when(priceProvider.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenReturn(Map.of(from.plusDays(60), new BigDecimal("51000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        assertThat(priceService.backfillHistoricalPrices(Set.of("BTC"), from)).isEqualTo(1);
    }

    @Test
    void backfill_continuesPastAFailingTicker_andLogsItAtError() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        // BTC blows up with a genuine bug; ETH must still be backfilled.
        when(priceProvider.getHistoricalPricesEur(eq("BTC"), any(), any()))
            .thenThrow(new IllegalStateException("a real bug"));
        when(priceProvider.getHistoricalPricesEur(eq("ETH"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("3000")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        int saved = assertThatNoStartupFailure(() ->
            priceService.backfillHistoricalPrices(new java.util.LinkedHashSet<>(List.of("BTC", "ETH")), from));

        assertThat(saved).isEqualTo(1);
        verify(priceSnapshotRepository).save(any());

        assertThat(eventsAt(Level.ERROR)).hasSize(2);
        assertThat(eventsAt(Level.ERROR).get(0).getFormattedMessage()).contains("BTC");
        assertThat(eventsAt(Level.WARN)).isEmpty();
        assertThat(eventsAt(Level.ERROR).get(1).getFormattedMessage())
            .contains("1 of 2 tickers failing");
    }

    @Test
    void backfill_savesPricesReturnedByThePort() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        when(priceProvider.getHistoricalPricesEur(eq("AAPL"), any(), any()))
            .thenReturn(Map.of(from, new BigDecimal("200")));
        when(priceSnapshotRepository.findByTickerAndDate(any(), any())).thenReturn(Optional.empty());

        assertThat(priceService.backfillHistoricalPrices(Set.of("AAPL"), from)).isEqualTo(1);
    }

    @Test
    void getPriceEur_cachesAMiss_soAnUnresolvableTickerIsFetchedOnce() {
        when(priceProvider.supports("MWRDF")).thenReturn(false);
        when(priceProvider.getPricesEur(Set.of("MWRDF"))).thenReturn(Map.of());

        assertThat(priceService.getPriceEur("MWRDF")).isNull();
        assertThat(priceService.getPriceEur("MWRDF")).isNull();
        assertThat(priceService.getPriceEur("MWRDF")).isNull();

        verify(priceProvider, times(1)).getPricesEur(Set.of("MWRDF"));
    }

    @Test
    void getPriceEur_stillCachesAndReturnsHits() {
        when(priceProvider.supports("AAPL")).thenReturn(false);
        when(priceProvider.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("200")));

        assertThat(priceService.getPriceEur("AAPL")).isEqualByComparingTo("200");
        assertThat(priceService.getPriceEur("AAPL")).isEqualByComparingTo("200");

        verify(priceProvider, times(1)).getPricesEur(Set.of("AAPL"));
    }

    /**
     * A cash balance converts with an FX rate. Yahoo answers chart/USD with the ProShares Ultra
     * Semiconductors ETF (~89 USD a share), so routing a bare currency code through the price
     * path valued a 1 000 USD account at ~76 000 EUR and wrote that into its daily snapshots.
     */
    @Test
    void toEur_convertsACashBalanceWithTheFxRate_neverWithAChartSymbol() {
        when(priceProvider.getFxRateToEur("USD")).thenReturn(new BigDecimal("0.86"));

        assertThat(priceService.toEur(new BigDecimal("1000"), "USD", null)).isEqualByComparingTo("860");

        verify(priceProvider, times(0)).getPricesEur(anySet());
    }

    @Test
    void toEur_returnsTheBalanceUnconverted_andLogsAtError_whenNoRateIsAvailable() {
        when(priceProvider.getFxRateToEur("USD")).thenReturn(null);

        assertThat(priceService.toEur(new BigDecimal("1000"), "USD", null)).isEqualByComparingTo("1000");

        assertThat(eventsAt(Level.ERROR)).anySatisfy(e ->
            assertThat(e.getFormattedMessage()).contains("USD").contains("UNCONVERTED"));
    }

    @Test
    void toEur_stillPricesAnAccountThatIsOneAsset_throughItsTicker() {
        when(priceProvider.supports("AAPL")).thenReturn(false);
        when(priceProvider.getPricesEur(Set.of("AAPL"))).thenReturn(Map.of("AAPL", new BigDecimal("200")));

        assertThat(priceService.toEur(new BigDecimal("3"), "USD", "AAPL")).isEqualByComparingTo("600");

        verify(priceProvider, times(0)).getFxRateToEur(any());
    }

    /** Runs the backfill and fails loudly if it throws — the ApplicationRunner contract. */
    private int assertThatNoStartupFailure(java.util.function.Supplier<Integer> backfill) {
        var result = new int[1];
        assertThatCode(() -> result[0] = backfill.get())
            .as("backfill must never propagate; PriceBackfillRunner would fail Spring Boot startup")
            .doesNotThrowAnyException();
        return result[0];
    }
}
