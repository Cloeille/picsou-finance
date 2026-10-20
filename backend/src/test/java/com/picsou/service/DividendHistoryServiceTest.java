package com.picsou.service;

import com.picsou.adapter.YahooCooldown;
import com.picsou.model.DividendEvent;
import com.picsou.model.DividendSource;
import com.picsou.model.DividendSourceStatus;
import com.picsou.port.DividendHistory;
import com.picsou.port.DividendHistoryPort;
import com.picsou.port.NasdaqDividendPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.DividendSourceRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DividendHistoryServiceTest {

    /** The service clock. Test data is dated from it, never from the wall clock. */
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Mock DividendSourceRepository repository;
    @Mock DividendHistoryPort historyPort;
    @Mock NasdaqDividendPort nasdaq;
    @Mock PriceProviderPort prices;

    private final CountingTxManager txManager = new CountingTxManager();
    private YahooCooldown cooldown;

    @BeforeEach
    void echoSaves() {
        cooldown = new YahooCooldown(Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.findByTicker(any())).thenReturn(Optional.empty());
        lenient().when(repository.findByTickerIn(any())).thenReturn(List.of());
        lenient().when(nasdaq.fetchDividends(any())).thenReturn(List.of());
    }

    @Test
    void refreshStale_skipsTheWholePassWhileYahooCooldownIsActive() {
        cooldown.arm(new HttpHeaders());

        int refreshed = service().refreshStale(List.of("TTE.PA"), 40);

        assertThat(refreshed).isZero();
        verify(historyPort, never()).fetchDividendHistory(any());
        verify(repository, never()).findByTickerIn(any());
    }

    @Test
    void refreshStale_refreshesOnlyWhatIsDueAndStopsAtTheCap() {
        Instant now = NOW;
        DividendSource fresh = source("FRESH", DividendSourceStatus.OK, now.minus(Duration.ofDays(2)));
        DividendSource stale = source("STALE", DividendSourceStatus.OK, now.minus(Duration.ofDays(8)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(fresh, stale));
        when(historyPort.fetchDividendHistory("STALE"))
            .thenReturn(new DividendHistory("EUR", List.of(point("2026-06-01", "0.50"))));
        when(prices.getFxRateToEur("EUR")).thenReturn(BigDecimal.ONE);

        int refreshed = service().refreshStale(List.of("FRESH", "STALE", "OTHER"), 1);

        assertThat(refreshed).isEqualTo(1);
        verify(historyPort, never()).fetchDividendHistory("FRESH");
        verify(historyPort).fetchDividendHistory("STALE");
        verify(historyPort, never()).fetchDividendHistory("OTHER");
    }

    @Test
    void aSuccessFromTheLastSundayPassIsDueAgainOnTheNextOne() {
        // refreshedAt is stamped after the fetch, so the next Sunday finds it a little under
        // seven days old. A 7-day threshold skipped it and refreshed every other week.
        DividendSource lastSunday = source("A", DividendSourceStatus.OK,
            NOW.minus(Duration.ofDays(7)).plusSeconds(2));
        DividendSource midweek = source("B", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(5)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(lastSunday, midweek));
        when(historyPort.fetchDividendHistory(any())).thenReturn(new DividendHistory("EUR", List.of()));

        service().refreshStale(List.of("A", "B"), 10);

        verify(historyPort).fetchDividendHistory("A");
        verify(historyPort, never()).fetchDividendHistory("B");
    }

    @Test
    void refreshTimestampsComeFromTheInjectedClock() {
        when(historyPort.fetchDividendHistory("TTE.PA")).thenReturn(new DividendHistory("EUR", List.of()));

        service().refreshStale(List.of("TTE.PA"), 10);

        org.mockito.ArgumentCaptor<DividendSource> saved = org.mockito.ArgumentCaptor.forClass(DividendSource.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRefreshedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getLastAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void aFailureIsRetriedInThreeDaysWhereASuccessWaitsSix() {
        Instant now = NOW;
        DividendSource failedYesterday = source("A", DividendSourceStatus.FAILED, now.minus(Duration.ofDays(10)));
        failedYesterday.setLastAttemptAt(now.minus(Duration.ofDays(1)));
        DividendSource failedLastWeek = source("B", DividendSourceStatus.FAILED, now.minus(Duration.ofDays(10)));
        failedLastWeek.setLastAttemptAt(now.minus(Duration.ofDays(4)));
        DividendSource okThreeDaysAgo = source("C", DividendSourceStatus.OK, now.minus(Duration.ofDays(3)));
        DividendSource okEightDaysAgo = source("D", DividendSourceStatus.OK, now.minus(Duration.ofDays(8)));
        DividendSource neverFetched = source("E", DividendSourceStatus.NEVER_FETCHED, null);
        when(repository.findByTickerIn(any()))
            .thenReturn(List.of(failedYesterday, failedLastWeek, okThreeDaysAgo, okEightDaysAgo, neverFetched));
        when(historyPort.fetchDividendHistory(any()))
            .thenReturn(new DividendHistory("EUR", List.of()));

        service().refreshStale(List.of("A", "B", "C", "D", "E"), 10);

        verify(historyPort, never()).fetchDividendHistory("A");
        verify(historyPort).fetchDividendHistory("B");
        verify(historyPort, never()).fetchDividendHistory("C");
        verify(historyPort).fetchDividendHistory("D");
        verify(historyPort).fetchDividendHistory("E");
    }

    @Test
    void aFailedFetchKeepsThePreviousEvents() {
        DividendEvent kept = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 6, 1)).amount(new BigDecimal("0.90")).amountEur(new BigDecimal("0.90"))
            .build();
        DividendSource existing = source("TTE.PA", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(8)));
        Instant refreshedAt = existing.getRefreshedAt();
        existing.setId(4L);
        existing.setEvents(new ArrayList<>(List.of(kept)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(existing));
        when(repository.findByTicker("TTE.PA")).thenReturn(Optional.of(existing));
        when(historyPort.fetchDividendHistory("TTE.PA")).thenThrow(new RuntimeException("yahoo 502"));

        service().refreshStale(List.of("TTE.PA"), 10);

        assertThat(existing.getEvents()).containsExactly(kept);
        assertThat(existing.getStatus()).isEqualTo(DividendSourceStatus.FAILED);
        assertThat(existing.getRefreshedAt()).isEqualTo(refreshedAt);
        assertThat(existing.getLastError()).contains("502");
        assertThat(existing.getLastAttemptAt()).isNotNull();
    }

    @Test
    void aMissingFxRateFailsAndKeepsThePreviousEvents() {
        DividendEvent kept = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 6, 1)).amount(new BigDecimal("1")).amountEur(new BigDecimal("0.92"))
            .build();
        DividendSource existing = source("AAPL", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(8)));
        Instant refreshedAt = existing.getRefreshedAt();
        existing.setId(8L);
        existing.setCurrency("USD");
        existing.setEvents(new ArrayList<>(List.of(kept)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(existing));
        when(repository.findByTicker("AAPL")).thenReturn(Optional.of(existing));
        when(historyPort.fetchDividendHistory("AAPL")).thenReturn(new DividendHistory(
            "USD", List.of(point("2026-08-01", "1.10"))));
        when(prices.getFxRateToEur("USD")).thenReturn(null);

        service().refreshStale(List.of("AAPL"), 10);

        assertThat(existing.getStatus()).isEqualTo(DividendSourceStatus.FAILED);
        assertThat(existing.getEvents()).containsExactly(kept);
        assertThat(existing.getRefreshedAt()).isEqualTo(refreshedAt);
        assertThat(existing.getLastError()).contains("USD");
    }

    @Test
    void aSuccessStoresAmountEurAndReplacesThePreviousEvents() {
        DividendSource existing = source("TTE.PA", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(8)));
        existing.setId(4L);
        existing.setEvents(new ArrayList<>(List.of(DividendEvent.builder()
            .exDate(LocalDate.of(2025, 1, 1)).amount(new BigDecimal("1")).amountEur(new BigDecimal("1")).build())));
        when(repository.findByTickerIn(any())).thenReturn(List.of(existing));
        when(repository.findByTicker("TTE.PA")).thenReturn(Optional.of(existing));
        when(historyPort.fetchDividendHistory("TTE.PA")).thenReturn(new DividendHistory(
            "USD", List.of(point("2026-06-15", "2.00"))));
        when(prices.getFxRateToEur("USD")).thenReturn(new BigDecimal("0.50"));

        service().refreshStale(List.of("TTE.PA"), 10);

        assertThat(existing.getStatus()).isEqualTo(DividendSourceStatus.OK);
        assertThat(existing.getCurrency()).isEqualTo("USD");
        assertThat(existing.getEvents()).hasSize(1);
        assertThat(existing.getEvents().get(0).getExDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(existing.getEvents().get(0).getAmount()).isEqualByComparingTo("2.00");
        assertThat(existing.getEvents().get(0).getAmountEur()).isEqualByComparingTo("1.00000000");
        verify(repository).saveAndFlush(existing);
    }

    @Test
    void eachTickerGetsItsOwnTransaction() {
        when(historyPort.fetchDividendHistory(any())).thenReturn(new DividendHistory("EUR", List.of()));

        service().refreshStale(List.of("A", "B"), 10);

        assertThat(txManager.started).isEqualTo(2);
    }

    @Test
    void nasdaqIsNotCalledForATickerWithAnExchangeSuffix() {
        when(historyPort.fetchDividendHistory("TTE.PA"))
            .thenReturn(new DividendHistory("EUR", List.of(point("2026-06-01", "1"))));
        when(prices.getFxRateToEur("EUR")).thenReturn(BigDecimal.ONE);

        service().refreshStale(List.of("TTE.PA"), 10);

        verify(nasdaq, never()).fetchDividends(any());
    }

    @Test
    void aNasdaqFailureKeepsTheYahooEvents() {
        DividendEvent repeated = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 8, 1))
            .amount(new BigDecimal("0.90"))
            .amountEur(new BigDecimal("0.81"))
            .paymentDate(LocalDate.of(2026, 8, 15))
            .build();
        DividendEvent declared = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 11, 19))
            .amount(new BigDecimal("0.98"))
            .amountEur(new BigDecimal("0.88"))
            .paymentDate(LocalDate.of(2026, 12, 10))
            .declared(true)
            .build();
        DividendEvent olderThanWindow = DividendEvent.builder()
            .exDate(LocalDate.of(2020, 1, 1))
            .amount(new BigDecimal("0.10"))
            .amountEur(new BigDecimal("0.09"))
            .build();
        DividendSource existing = source("AAPL", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(8)));
        existing.setId(4L);
        existing.setEvents(new ArrayList<>(List.of(repeated, declared, olderThanWindow)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(existing));
        when(repository.findByTicker("AAPL")).thenReturn(Optional.of(existing));
        when(historyPort.fetchDividendHistory("AAPL")).thenReturn(new DividendHistory(
            "USD", List.of(point("2026-08-01", "1.10"))));
        when(prices.getFxRateToEur("USD")).thenReturn(new BigDecimal("0.90"));
        when(nasdaq.fetchDividends("AAPL")).thenThrow(new RuntimeException("nasdaq 500"));

        service().refreshStale(List.of("AAPL"), 10);

        assertThat(existing.getStatus()).isEqualTo(DividendSourceStatus.OK);
        assertThat(existing.getEvents()).extracting(DividendEvent::getExDate)
            .containsExactlyInAnyOrder(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 19));
        assertThat(eventOn(existing, LocalDate.of(2026, 8, 1))).satisfies(event -> {
            assertThat(event.getAmount()).isEqualByComparingTo("1.10");
            assertThat(event.getAmountEur()).isEqualByComparingTo("0.99");
            assertThat(event.getPaymentDate()).isEqualTo(LocalDate.of(2026, 8, 15));
            assertThat(event.isDeclared()).isFalse();
        });
        assertThat(eventOn(existing, LocalDate.of(2026, 11, 19))).satisfies(event -> {
            assertThat(event.getAmount()).isEqualByComparingTo("0.98");
            assertThat(event.getPaymentDate()).isEqualTo(LocalDate.of(2026, 12, 10));
            assertThat(event.isDeclared()).isTrue();
        });
    }

    @Test
    void emptyNasdaqRowsKeepTheYahooEvents() {
        DividendEvent repeated = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 8, 1))
            .amount(new BigDecimal("0.40"))
            .amountEur(new BigDecimal("0.40"))
            .paymentDate(LocalDate.of(2026, 8, 20))
            .build();
        DividendEvent declared = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 11, 19))
            .amount(new BigDecimal("0.51"))
            .amountEur(new BigDecimal("0.51"))
            .paymentDate(LocalDate.of(2026, 12, 1))
            .declared(true)
            .build();
        DividendSource existing = source("KO", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(8)));
        existing.setId(5L);
        existing.setEvents(new ArrayList<>(List.of(repeated, declared)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(existing));
        when(repository.findByTicker("KO")).thenReturn(Optional.of(existing));
        when(historyPort.fetchDividendHistory("KO")).thenReturn(new DividendHistory(
            "USD", List.of(point("2026-08-01", "0.50"))));
        when(prices.getFxRateToEur("USD")).thenReturn(BigDecimal.ONE);
        when(nasdaq.fetchDividends("KO")).thenReturn(List.of());

        service().refreshStale(List.of("KO"), 10);

        assertThat(existing.getStatus()).isEqualTo(DividendSourceStatus.OK);
        assertThat(existing.getEvents()).extracting(DividendEvent::getExDate)
            .containsExactlyInAnyOrder(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 19));
        assertThat(eventOn(existing, LocalDate.of(2026, 8, 1))).satisfies(event -> {
            assertThat(event.getAmount()).isEqualByComparingTo("0.50");
            assertThat(event.getPaymentDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        });
        assertThat(eventOn(existing, LocalDate.of(2026, 11, 19)).isDeclared()).isTrue();
    }

    @Test
    void aNasdaqRefreshThatOmitsADateKeepsTheStoredPaymentDate() {
        DividendEvent repeated = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 8, 1))
            .amount(new BigDecimal("0.90"))
            .amountEur(new BigDecimal("0.81"))
            .paymentDate(LocalDate.of(2026, 8, 15))
            .build();
        DividendEvent withdrawn = DividendEvent.builder()
            .exDate(LocalDate.of(2026, 11, 19))
            .amount(new BigDecimal("0.98"))
            .amountEur(new BigDecimal("0.88"))
            .paymentDate(LocalDate.of(2026, 12, 10))
            .declared(true)
            .build();
        DividendSource existing = source("AAPL", DividendSourceStatus.OK, NOW.minus(Duration.ofDays(8)));
        existing.setId(6L);
        existing.setEvents(new ArrayList<>(List.of(repeated, withdrawn)));
        when(repository.findByTickerIn(any())).thenReturn(List.of(existing));
        when(repository.findByTicker("AAPL")).thenReturn(Optional.of(existing));
        when(historyPort.fetchDividendHistory("AAPL")).thenReturn(new DividendHistory(
            "USD", List.of(point("2026-08-01", "1.10"))));
        when(prices.getFxRateToEur("USD")).thenReturn(new BigDecimal("0.90"));
        when(nasdaq.fetchDividends("AAPL")).thenReturn(List.of(new com.picsou.port.NasdaqDividend(
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15), null, null,
            new BigDecimal("1.00"), "USD")));

        service().refreshStale(List.of("AAPL"), 10);

        assertThat(existing.getEvents()).extracting(DividendEvent::getExDate)
            .containsExactlyInAnyOrder(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 1));
        assertThat(eventOn(existing, LocalDate.of(2026, 8, 1)).getPaymentDate())
            .isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(eventOn(existing, LocalDate.of(2026, 12, 1))).satisfies(event -> {
            assertThat(event.isDeclared()).isTrue();
            assertThat(event.getPaymentDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        });
    }

    @Test
    void aFutureYahooExDateIsStoredDeclaredWithNoPaymentDate() {
        when(historyPort.fetchDividendHistory("TTE.PA")).thenReturn(new DividendHistory(
            "EUR", List.of(point("2026-09-15", "0.85"), point("2026-12-15", "0.90"))));
        when(prices.getFxRateToEur("EUR")).thenReturn(BigDecimal.ONE);

        service().refreshStale(List.of("TTE.PA"), 10);

        org.mockito.ArgumentCaptor<DividendSource> saved = org.mockito.ArgumentCaptor.forClass(DividendSource.class);
        verify(repository).save(saved.capture());
        DividendSource source = saved.getValue();

        assertThat(source.getEvents()).extracting(DividendEvent::getExDate)
            .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 12, 15));
        assertThat(eventOn(source, LocalDate.of(2026, 9, 15)).isDeclared()).isFalse();
        assertThat(eventOn(source, LocalDate.of(2026, 12, 15))).satisfies(event -> {
            assertThat(event.isDeclared()).isTrue();
            assertThat(event.getPaymentDate()).isNull();
        });
        verify(nasdaq, never()).fetchDividends(any());
    }

    @Test
    void aNasdaqRowWithNoCurrencyIsSkippedWhenYahooGaveNoneEither() {
        // Yahoo answered empty (no currency), so a Nasdaq row without one cannot be priced:
        // it is skipped, not stored 1:1 as euros. A row that states EUR is still stored.
        when(historyPort.fetchDividendHistory("AAPL")).thenReturn(new DividendHistory(null, List.of()));
        when(nasdaq.fetchDividends("AAPL")).thenReturn(List.of(
            new com.picsou.port.NasdaqDividend(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 12, 10),
                null, null, new BigDecimal("0.98"), null),
            new com.picsou.port.NasdaqDividend(LocalDate.of(2027, 2, 19), null,
                null, null, new BigDecimal("0.50"), "EUR")));

        service().refreshStale(List.of("AAPL"), 10);

        org.mockito.ArgumentCaptor<DividendSource> saved = org.mockito.ArgumentCaptor.forClass(DividendSource.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getExDate()).isEqualTo(LocalDate.of(2027, 2, 19));
            assertThat(event.getAmountEur()).isEqualByComparingTo("0.50");
        });
        verify(prices, never()).getFxRateToEur(any());
    }

    @Test
    void aTickerInTheFailureLogIsSanitized() {
        String ticker = "AAPL\nINJECTED";
        when(historyPort.fetchDividendHistory(ticker)).thenThrow(new RuntimeException("yahoo\n502"));
        Logger logger = (Logger) LoggerFactory.getLogger(DividendHistoryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            service().refreshStale(List.of(ticker), 10);

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("\n");
                assertThat(event.getFormattedMessage()).contains("AAPL?INJECTED");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    private DividendHistoryService service() {
        return new DividendHistoryService(
            repository, historyPort, nasdaq, prices, cooldown, new TransactionTemplate(txManager),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static DividendSource source(String ticker, DividendSourceStatus status, Instant refreshedAt) {
        return DividendSource.builder()
            .ticker(ticker)
            .status(status)
            .refreshedAt(refreshedAt)
            .events(new ArrayList<>())
            .build();
    }

    private static DividendHistory.Point point(String date, String amount) {
        return new DividendHistory.Point(LocalDate.parse(date), new BigDecimal(amount));
    }

    private static DividendEvent eventOn(DividendSource source, LocalDate exDate) {
        return source.getEvents().stream()
            .filter(event -> exDate.equals(event.getExDate()))
            .findFirst()
            .orElseThrow();
    }

    private static final class CountingTxManager implements PlatformTransactionManager {
        int started;

        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            started++;
            return new SimpleTransactionStatus();
        }

        @Override public void commit(TransactionStatus status) {}

        @Override public void rollback(TransactionStatus status) {}
    }
}
