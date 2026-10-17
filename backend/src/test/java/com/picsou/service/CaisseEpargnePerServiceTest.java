package com.picsou.service;

import com.picsou.adapter.OpenFigiIsinConverter;
import com.picsou.exception.SyncException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.port.SymbolCatalogPort;
import com.picsou.repository.AccountHoldingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaisseEpargnePerServiceTest {

    @Mock AccountHoldingRepository holdingRepository;
    @Mock OpenFigiIsinConverter isinConverter;
    @Mock SymbolCatalogPort symbolCatalog;
    @Mock SecurityIdentityService identityService;
    @Mock PriceService priceService;

    @InjectMocks CaisseEpargnePerService service;

    @Test
    void prepare_mixedEuroFundAndUcReconcilesAndKeepsPocketSeparate() {
        LocalDate valuationDate = LocalDate.of(2026, 10, 13);
        LocalDate navDate = LocalDate.of(2026, 10, 12);
        CaisseEpargnePort.PerDetails details = new CaisseEpargnePort.PerDetails(
            new BigDecimal("275.00"),
            new BigDecimal("75.00"),
            valuationDate,
            java.util.List.of(new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Core Fund", new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("200"), navDate, 1
            ))
        );
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core"));
        when(symbolCatalog.hasQuote("IWDA.AS")).thenReturn(true);

        CaisseEpargnePerService.Prepared prepared = service.prepare(details, "EUR");

        assertThat(prepared.balance()).isEqualByComparingTo("275.00");
        assertThat(prepared.euroFundBalance()).isEqualByComparingTo("75.00");
        assertThat(prepared.valuationDate()).isEqualTo(valuationDate);
        assertThat(prepared.positions()).singleElement().satisfies(position -> {
            assertThat(position.ticker()).isEqualTo("IWDA.AS");
            assertThat(position.isin()).isEqualTo("IE00B4L5Y983");
            assertThat(position.name()).isEqualTo("Core Fund");
            assertThat(position.quantity()).isEqualByComparingTo("2");
            assertThat(position.unitPrice()).isEqualByComparingTo("100");
            assertThat(position.value()).isEqualByComparingTo("200");
            assertThat(position.priceDate()).isEqualTo(navDate);
        });
        assertThat(prepared.isinByTicker()).containsEntry("IWDA.AS", "IE00B4L5Y983");
        assertThat(prepared.verifiedTickers()).containsExactly("IWDA.AS");
        verify(isinConverter).resolve("IE00B4L5Y983");
        verify(symbolCatalog).hasQuote("IWDA.AS");
        verifyNoInteractions(holdingRepository, identityService, priceService);
    }

    @Test
    void prepare_groupsCompartmentsByIsinAndUsesWeightedBankNav() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core"));
        when(symbolCatalog.hasQuote("IWDA.AS")).thenReturn(true);

        CaisseEpargnePort.PerPosition older = new CaisseEpargnePort.PerPosition(
            "IE00B4L5Y983", "Core Fund", new BigDecimal("2"), new BigDecimal("100"),
            new BigDecimal("200"), LocalDate.of(2026, 10, 11), 1);
        CaisseEpargnePort.PerPosition newer = new CaisseEpargnePort.PerPosition(
            "IE00B4L5Y983", "Core Fund", new BigDecimal("3"), new BigDecimal("110"),
            new BigDecimal("330"), LocalDate.of(2026, 10, 12), 2);
        CaisseEpargnePerService.Prepared prepared = service.prepare(details("530", "0", older, newer), "EUR");
        CaisseEpargnePerService.Prepared reversed = service.prepare(details("530", "0", newer, older), "EUR");

        assertThat(prepared.positions()).singleElement().satisfies(position -> {
            assertThat(position.ticker()).isEqualTo("IWDA.AS");
            assertThat(position.quantity()).isEqualByComparingTo("5");
            assertThat(position.value()).isEqualByComparingTo("530");
            assertThat(position.unitPrice()).isEqualByComparingTo("106");
            assertThat(position.priceDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        });
        assertThat(reversed.positions()).singleElement().satisfies(position -> {
            assertThat(position.quantity()).isEqualByComparingTo("5");
            assertThat(position.value()).isEqualByComparingTo("530");
            assertThat(position.unitPrice()).isEqualByComparingTo("106");
            assertThat(position.priceDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        });
        verify(isinConverter, times(2)).resolve("IE00B4L5Y983");
    }

    @Test
    void prepare_differentIsinsResolvingToSameTickerFallBackToDistinctRawIsins() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("DUP", "Fund A"));
        when(isinConverter.resolve("FR0000120271"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("DUP", "Fund B"));
        when(symbolCatalog.hasQuote("DUP")).thenReturn(true);

        CaisseEpargnePerService.Prepared prepared = service.prepare(details(
            "300", "0",
            new CaisseEpargnePort.PerPosition("IE00B4L5Y983", "Fund A", BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("100"), LocalDate.of(2026, 10, 12), 1),
            new CaisseEpargnePort.PerPosition("FR0000120271", "Fund B", new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("200"), LocalDate.of(2026, 10, 12), 1)
        ), "EUR");

        assertThat(prepared.positions()).extracting(CaisseEpargnePerService.PreparedPosition::ticker)
            .containsExactly("IE00B4L5Y983", "FR0000120271");
        assertThat(prepared.positions()).extracting(CaisseEpargnePerService.PreparedPosition::value)
            .containsExactly(new BigDecimal("100"), new BigDecimal("200"));
        assertThat(prepared.verifiedTickers()).isEmpty();
        assertThat(prepared.isinByTicker()).isEmpty();
    }

    @Test
    void prepare_unverifiedOrUnavailableResolutionRetainsBankValueUnderRawIsin() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("UNLISTED", "Fund A"));
        when(symbolCatalog.hasQuote("UNLISTED")).thenReturn(false);
        when(isinConverter.resolve("FR0000120271"))
            .thenThrow(new IllegalStateException("resolver outage"));
        when(isinConverter.resolve("US0378331005"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("BROKEN", "Fund C"));
        when(symbolCatalog.hasQuote("BROKEN")).thenThrow(new IllegalStateException("catalog outage"));

        CaisseEpargnePerService.Prepared prepared = service.prepare(details(
            "600", "0",
            new CaisseEpargnePort.PerPosition("IE00B4L5Y983", "Fund A", BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("100"), LocalDate.of(2026, 10, 12), 1),
            new CaisseEpargnePort.PerPosition("FR0000120271", "Fund B", new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("200"), LocalDate.of(2026, 10, 12), 1),
            new CaisseEpargnePort.PerPosition("US0378331005", "Fund C", new BigDecimal("3"),
                new BigDecimal("100"), new BigDecimal("300"), LocalDate.of(2026, 10, 12), 1)
        ), "EUR");

        assertThat(prepared.positions()).extracting(CaisseEpargnePerService.PreparedPosition::ticker)
            .containsExactly("IE00B4L5Y983", "FR0000120271", "US0378331005");
        assertThat(prepared.positions()).extracting(CaisseEpargnePerService.PreparedPosition::value)
            .containsExactly(new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("300"));
        assertThat(prepared.verifiedTickers()).isEmpty();
        verify(symbolCatalog).hasQuote("UNLISTED");
        verify(symbolCatalog).hasQuote("BROKEN");
        service.backfill(prepared);
        verifyNoInteractions(holdingRepository, identityService, priceService);
    }

    @Test
    void apply_rawIsinFallbackPersistsBankValueWithoutTreatingNavAsMarketHistory() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("UNLISTED", "Fund"));
        when(symbolCatalog.hasQuote("UNLISTED")).thenReturn(false);
        CaisseEpargnePerService.Prepared prepared = service.prepare(details(
            "100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), 1
            )
        ), "EUR");
        Account account = Account.builder().id(8L).build();

        service.apply(account, prepared, Instant.parse("2026-10-14T08:00:00Z"));

        ArgumentCaptor<List<AccountHolding>> saved = ArgumentCaptor.forClass(List.class);
        verify(holdingRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(holding -> {
            assertThat(holding.getTicker()).isEqualTo("IE00B4L5Y983");
            assertThat(holding.getCurrentPrice()).isEqualByComparingTo("100");
            assertThat(holding.getProviderValueEur()).isEqualByComparingTo("100");
            assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
        });
        verifyNoInteractions(identityService);
        verify(priceService, never()).backfillHistoricalPrices(anySet(), any(LocalDate.class));
    }

    @Test
    void prepare_rejectsIncompleteAndInconsistentValuationsBeforeAnyResolutionOrWrite() {
        LocalDate valuationDate = LocalDate.of(2026, 10, 13);
        CaisseEpargnePort.PerPosition valid = new CaisseEpargnePort.PerPosition(
            "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
            LocalDate.of(2026, 10, 12), 1
        );
        List<CaisseEpargnePort.PerDetails> invalidDetails = List.of(
            new CaisseEpargnePort.PerDetails(new BigDecimal("100"), new BigDecimal("100"), valuationDate, null),
            new CaisseEpargnePort.PerDetails(null, BigDecimal.ZERO, valuationDate, List.of()),
            new CaisseEpargnePort.PerDetails(BigDecimal.ZERO, null, valuationDate, List.of()),
            new CaisseEpargnePort.PerDetails(BigDecimal.ZERO, BigDecimal.ZERO, null, List.of()),
            details("101", "0", valid),
            details("99.97", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("99.97"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "ie00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("200", "0", valid, valid),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                LocalDate.of(2026, 10, 14), 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, null, new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), null
            )),
            details("0", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE.negate(), new BigDecimal("100"), new BigDecimal("-100"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("0", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ZERO, new BigDecimal("-1"), BigDecimal.ZERO,
                LocalDate.of(2026, 10, 12), 1
            )),
            details("0", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-1"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "x".repeat(101), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), 1
            )),
            details("100", "0", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                LocalDate.of(2026, 10, 12), -1
            ))
        );

        invalidDetails.forEach(detail -> assertInvalid(detail, "EUR"));
        assertInvalid(details("100", "0", valid), "USD");
        assertInvalid(details("-1", "0"), "EUR");
        assertInvalid(details("0", "-1"), "EUR");

        verifyNoInteractions(isinConverter, symbolCatalog, holdingRepository, identityService, priceService);
    }

    @Test
    void prepare_acceptsBothReconciliationDifferencesAtTwoCents() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "Fund"));
        when(symbolCatalog.hasQuote("IWDA.AS")).thenReturn(true);

        CaisseEpargnePerService.Prepared prepared = service.prepare(new CaisseEpargnePort.PerDetails(
            new BigDecimal("100.06"), new BigDecimal("0.02"), LocalDate.of(2026, 10, 13),
            List.of(new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Fund", BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100.02"),
                LocalDate.of(2026, 10, 12), 0
            ))
        ), "EUR");

        assertThat(prepared.balance()).isEqualByComparingTo("100.06");
        assertThat(prepared.euroFundBalance()).isEqualByComparingTo("0.02");
        assertThat(prepared.positions()).singleElement().satisfies(position -> {
            assertThat(position.value()).isEqualByComparingTo("100.02");
            assertThat(position.unitPrice()).isEqualByComparingTo("100.02");
        });
    }

    @Test
    void prepare_nullLegacyDetailPreservesExistingBalancesAndHoldings() {
        Account account = Account.builder()
            .id(8L).currentBalance(new BigDecimal("400")).cashBalance(new BigDecimal("25")).build();

        CaisseEpargnePerService.Prepared prepared = service.prepare(null, "EUR");
        service.apply(account, prepared, Instant.parse("2026-10-14T08:00:00Z"));

        assertThat(prepared).isNull();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("400");
        assertThat(account.getCashBalance()).isEqualByComparingTo("25");
        verifyNoInteractions(holdingRepository, isinConverter, symbolCatalog, identityService, priceService);
    }

    @Test
    void prepare_explicitEmptyPortfolioClearsOldHoldingsButKeepsEuroPocketAsCash() {
        CaisseEpargnePerService.Prepared prepared = service.prepare(details("500", "500"), "EUR");
        Account account = Account.builder().id(8L).build();

        service.apply(account, prepared, Instant.parse("2026-10-14T08:00:00Z"));

        assertThat(prepared.positions()).isEmpty();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("500");
        assertThat(account.getCashBalance()).isEqualByComparingTo("500");
        verify(holdingRepository).deleteByAccountId(8L);
        ArgumentCaptor<List<AccountHolding>> saved = ArgumentCaptor.forClass(List.class);
        verify(holdingRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).isEmpty();
        verifyNoInteractions(isinConverter, symbolCatalog);
    }

    @Test
    void apply_repeatedCompleteSnapshotDeletesAndRecreatesExactlyOncePerCall() {
        CaisseEpargnePerService.Prepared prepared = new CaisseEpargnePerService.Prepared(
            new BigDecimal("275"), new BigDecimal("75"), LocalDate.of(2026, 10, 13),
            List.of(new CaisseEpargnePerService.PreparedPosition(
                "IWDA.AS", "Core Fund", new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("200"), LocalDate.of(2026, 10, 12), "IE00B4L5Y983"
            )), Map.of("IWDA.AS", "IE00B4L5Y983"), Set.of("IWDA.AS")
        );
        Account account = Account.builder().id(8L).build();
        Instant syncedAt = Instant.parse("2026-10-14T08:00:00Z");

        service.apply(account, prepared, syncedAt);
        service.apply(account, prepared, syncedAt);

        verify(holdingRepository, times(2)).deleteByAccountId(8L);
        verify(holdingRepository, times(2)).saveAll(any());
        verify(holdingRepository, times(4)).flush();
        verifyNoInteractions(identityService);
    }

    @Test
    void apply_replacesHoldingsAndStoresTheEuroFundAsAccountCash() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "iShares Core"));
        when(symbolCatalog.hasQuote("IWDA.AS")).thenReturn(true);
        CaisseEpargnePerService.Prepared prepared = service.prepare(details(
            "275", "75", new CaisseEpargnePort.PerPosition(
                "IE00B4L5Y983", "Core Fund", new BigDecimal("2"), new BigDecimal("100"),
                new BigDecimal("200"), LocalDate.of(2026, 10, 12), 1
            )
        ), "EUR");
        Account account = Account.builder().id(8L).build();
        Instant syncedAt = Instant.parse("2026-10-14T08:00:00Z");

        service.apply(account, prepared, syncedAt);

        assertThat(account.getCurrentBalance()).isEqualByComparingTo("275");
        assertThat(account.getCashBalance()).isEqualByComparingTo("75");
        verify(holdingRepository).deleteByAccountId(8L);
        verify(holdingRepository, times(2)).flush();
        ArgumentCaptor<List<AccountHolding>> saved = ArgumentCaptor.forClass(List.class);
        verify(holdingRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(holding -> {
            assertThat(holding.getAccount()).isSameAs(account);
            assertThat(holding.getTicker()).isEqualTo("IWDA.AS");
            assertThat(holding.getName()).isEqualTo("Core Fund");
            assertThat(holding.getQuantity()).isEqualByComparingTo("2");
            assertThat(holding.getCurrentPrice()).isEqualByComparingTo("100");
            assertThat(holding.getQuoteCurrency()).isEqualTo("EUR");
            assertThat(holding.getProviderValueEur()).isEqualByComparingTo("200");
            assertThat(holding.getProviderPriceDate()).isEqualTo(LocalDate.of(2026, 10, 12));
            assertThat(holding.getProviderPnlEur()).isNull();
            assertThat(holding.getAverageBuyIn()).isNull();
            assertThat(holding.getLastSyncedAt()).isEqualTo(syncedAt);
        });
        verifyNoInteractions(identityService);
        verify(priceService, never()).backfillHistoricalPrices(anySet(), any(LocalDate.class));
    }

    @Test
    void backfill_recordsIdentityAfterApplyAndContinuesMarketWorkWhenIdentityWriteFails() {
        CaisseEpargnePerService.Prepared prepared = new CaisseEpargnePerService.Prepared(
            new BigDecimal("200"), BigDecimal.ZERO, LocalDate.of(2026, 10, 13), List.of(),
            Map.of("IWDA.AS", "IE00B4L5Y983"), Set.of("IWDA.AS")
        );
        LocalDate from = LocalDate.now().minusMonths(12);
        org.mockito.Mockito.doThrow(new IllegalStateException("identity store unavailable"))
            .when(identityService).record(prepared.isinByTicker());

        assertThatCode(() -> service.backfill(prepared)).doesNotThrowAnyException();

        verify(identityService).record(prepared.isinByTicker());
        verify(priceService).backfillHistoricalPrices(Set.of("IWDA.AS"), from);
    }

    @Test
    void backfill_usesOnlyVerifiedTickersAndToleratesMarketOutage() {
        CaisseEpargnePerService.Prepared prepared = new CaisseEpargnePerService.Prepared(
            new BigDecimal("200"), BigDecimal.ZERO, LocalDate.of(2026, 10, 13), List.of(),
            Map.of("IWDA.AS", "IE00B4L5Y983"), Set.of("IWDA.AS")
        );
        LocalDate from = LocalDate.now().minusMonths(12);
        when(priceService.backfillHistoricalPrices(Set.of("IWDA.AS"), from))
            .thenThrow(new IllegalStateException("market unavailable"));

        assertThatCode(() -> service.backfill(prepared)).doesNotThrowAnyException();

        verify(priceService).backfillHistoricalPrices(Set.of("IWDA.AS"), from);
    }

    @Test
    void backfill_excludesRawIsinFallbacksFromMarketHistoryRequests() {
        when(isinConverter.resolve("IE00B4L5Y983"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("IWDA.AS", "Fund A"));
        when(symbolCatalog.hasQuote("IWDA.AS")).thenReturn(true);
        when(isinConverter.resolve("FR0000120271"))
            .thenReturn(new OpenFigiIsinConverter.TickerResult("UNLISTED", "Fund B"));
        when(symbolCatalog.hasQuote("UNLISTED")).thenReturn(false);
        CaisseEpargnePerService.Prepared prepared = service.prepare(details(
            "300", "0",
            new CaisseEpargnePort.PerPosition("IE00B4L5Y983", "Fund A", BigDecimal.ONE,
                new BigDecimal("100"), new BigDecimal("100"), LocalDate.of(2026, 10, 12), 1),
            new CaisseEpargnePort.PerPosition("FR0000120271", "Fund B", new BigDecimal("2"),
                new BigDecimal("100"), new BigDecimal("200"), LocalDate.of(2026, 10, 12), 1)
        ), "EUR");
        LocalDate from = LocalDate.now().minusMonths(12);

        service.backfill(prepared);

        assertThat(prepared.verifiedTickers()).containsExactly("IWDA.AS");
        verify(priceService).backfillHistoricalPrices(Set.of("IWDA.AS"), from);
    }

    private static CaisseEpargnePort.PerDetails details(
        String balance,
        String euroFundBalance,
        CaisseEpargnePort.PerPosition... positions
    ) {
        return new CaisseEpargnePort.PerDetails(
            new BigDecimal(balance), new BigDecimal(euroFundBalance),
            LocalDate.of(2026, 10, 13), List.of(positions)
        );
    }

    private void assertInvalid(CaisseEpargnePort.PerDetails detail, String currency) {
        assertThatThrownBy(() -> service.prepare(detail, currency))
            .isInstanceOf(SyncException.class)
            .satisfies(exception -> assertThat(((SyncException) exception).getCode())
                .isEqualTo("UPSTREAM_FORMAT_CHANGED"));
    }
}
