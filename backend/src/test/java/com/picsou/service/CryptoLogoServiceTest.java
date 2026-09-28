package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CryptoLogoServiceTest {

    @Mock CoinGeckoPriceProvider coinGecko;

    @Test
    void resolvesEveryTickerInOneBatchedCall() {
        when(coinGecko.getLogoUrls(Set.of("BTC", "ETH"))).thenReturn(Map.of(
            "BTC", "https://img/btc.png",
            "ETH", "https://img/eth.png"));

        CryptoLogoService service = new CryptoLogoService(coinGecko);

        Map<String, String> logos = service.getLogoUrls(Set.of("btc", "eth"));

        assertThat(logos).containsEntry("BTC", "https://img/btc.png")
            .containsEntry("ETH", "https://img/eth.png");
        verify(coinGecko, times(1)).getLogoUrls(Set.of("BTC", "ETH"));
    }

    @Test
    void aTickerTheProviderDoesNotKnowIsSimplyAbsent() {
        when(coinGecko.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/btc.png"));

        CryptoLogoService service = new CryptoLogoService(coinGecko);

        // The missing coin is a normal answer, not an error: the UI falls back to the ticker.
        assertThat(service.getLogoUrls(Set.of("BTC", "NOPE"))).containsOnlyKeys("BTC");
    }

    @Test
    void aMissIsRememberedSoAnUnmappedTickerIsNotAskedAboutOnEveryRender() {
        when(coinGecko.getLogoUrls(any())).thenReturn(Map.of());

        CryptoLogoService service = new CryptoLogoService(coinGecko);

        service.getLogoUrls(Set.of("NOPE"));
        service.getLogoUrls(Set.of("NOPE"));
        service.getLogoUrls(Set.of("NOPE"));

        // One call, not three: the negative entry is cached exactly like a hit.
        verify(coinGecko, times(1)).getLogoUrls(Set.of("NOPE"));
    }

    @Test
    void aSecondPageServedFromTheCacheCostsNoRequest() {
        when(coinGecko.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/btc.png"));

        CryptoLogoService service = new CryptoLogoService(coinGecko);
        service.getLogoUrls(Set.of("BTC"));
        service.getLogoUrls(Set.of("BTC"));

        verify(coinGecko, times(1)).getLogoUrls(any());
    }

    @Test
    void onlyTheUncachedTickersAreFetchedOnALaterPage() {
        when(coinGecko.getLogoUrls(Set.of("BTC"))).thenReturn(Map.of("BTC", "https://img/btc.png"));
        when(coinGecko.getLogoUrls(Set.of("ETH"))).thenReturn(Map.of("ETH", "https://img/eth.png"));

        CryptoLogoService service = new CryptoLogoService(coinGecko);
        service.getLogoUrls(Set.of("BTC"));

        // BTC is still cached when ETH joins the portfolio, so the second call carries only ETH
        // rather than re-asking for the pair.
        assertThat(service.getLogoUrls(Set.of("BTC", "ETH")))
            .containsEntry("BTC", "https://img/btc.png")
            .containsEntry("ETH", "https://img/eth.png");
        verify(coinGecko, never()).getLogoUrls(Set.of("BTC", "ETH"));
    }

    @Test
    void anEmptyOrBlankTickerSetNeverReachesTheProvider() {
        CryptoLogoService service = new CryptoLogoService(coinGecko);

        assertThat(service.getLogoUrls(Set.of())).isEmpty();
        assertThat(service.getLogoUrls(Set.of("  "))).isEmpty();
        assertThat(service.getLogoUrl(null)).isNull();
        assertThat(service.getLogoUrl("")).isNull();
        verify(coinGecko, never()).getLogoUrls(any());
    }

    @Test
    void getLogoUrlUppercasesBeforeLookingUp() {
        when(coinGecko.getLogoUrls(Set.of("BTC"))).thenReturn(Map.of("BTC", "https://img/btc.png"));

        CryptoLogoService service = new CryptoLogoService(coinGecko);

        assertThat(service.getLogoUrl("btc")).isEqualTo("https://img/btc.png");
    }

    @Test
    void aProviderReturningNothingYieldsAnEmptyMapRatherThanAFailure() {
        when(coinGecko.getLogoUrls(any())).thenReturn(Map.of());

        CryptoLogoService service = new CryptoLogoService(coinGecko);

        assertThat(service.getLogoUrls(Set.of("BTC"))).isEmpty();
    }
}
