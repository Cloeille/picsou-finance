package com.picsou.config;

import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.service.PriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The startup backfill must keep the crypto split {@code SchedulerService.refreshPrices} has:
 * a coin held in a CRYPTO account goes to CoinGecko or nowhere, never to Yahoo under a symbol
 * that may belong to a listed company.
 */
@ExtendWith(MockitoExtension.class)
class PriceBackfillRunnerTest {

    @Mock PriceService priceService;
    @Mock AccountHoldingRepository holdingRepository;

    @InjectMocks PriceBackfillRunner runner;

    @Test
    void run_backfillsCryptoHoldingsCryptoOnly_andTheRestThroughTheGenericRoute() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of("STX", "BTC"));
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("STX", "BTC", "AAPL"));

        runner.run(null);

        verify(priceService).backfillHistoricalPrices(eq(Set.of("BTC", "STX")), any(), eq(true));
        verify(priceService).backfillHistoricalPrices(eq(Set.of("AAPL")), any(), eq(false));
    }

    @Test
    void run_matchesCryptoAndOtherTickersCaseInsensitively() {
        // account_holding.ticker has no case constraint: a wallet's "stx" and a broker's "STX"
        // are the same symbol to the routing, so the subtraction must see them as one.
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of("stx"));
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of("STX", "aapl"));

        runner.run(null);

        verify(priceService).backfillHistoricalPrices(eq(Set.of("STX")), any(), eq(true));
        verify(priceService).backfillHistoricalPrices(eq(Set.of("AAPL")), any(), eq(false));
    }

    @Test
    void run_doesNothingWithoutHoldings() {
        when(holdingRepository.findDistinctTickersByAccountType(AccountType.CRYPTO)).thenReturn(Set.of());
        when(holdingRepository.findDistinctTickers()).thenReturn(Set.of());

        runner.run(null);

        verifyNoInteractions(priceService);
    }
}
