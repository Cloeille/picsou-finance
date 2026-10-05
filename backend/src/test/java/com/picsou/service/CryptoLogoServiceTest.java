package com.picsou.service;

import com.picsou.port.LogoProviderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    @Mock LogoProviderPort logoProvider;

    /**
     * A clock the test moves by hand. The two cache TTLs are the whole point of this class, and
     * asserting on them through {@code Thread.sleep} would make the suite both slow and flaky.
     */
    private final MutableClock clock = new MutableClock();

    private CryptoLogoService service() {
        return new CryptoLogoService(logoProvider, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T10:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void resolvesEveryTickerInOneBatchedCall() {
        when(logoProvider.getLogoUrls(Set.of("BTC", "ETH"))).thenReturn(Map.of(
            "BTC", "https://img/btc.png",
            "ETH", "https://img/eth.png"));

        CryptoLogoService service = service();

        Map<String, String> logos = service.getLogoUrls(Set.of("btc", "eth"));

        assertThat(logos).containsEntry("BTC", "https://img/btc.png")
            .containsEntry("ETH", "https://img/eth.png");
        verify(logoProvider, times(1)).getLogoUrls(Set.of("BTC", "ETH"));
    }

    @Test
    void aTickerTheProviderDoesNotKnowIsSimplyAbsent() {
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/btc.png"));

        CryptoLogoService service = service();

        // The missing coin is a normal answer, not an error: the UI falls back to the ticker.
        assertThat(service.getLogoUrls(Set.of("BTC", "NOPE"))).containsOnlyKeys("BTC");
    }

    @Test
    void aMissIsRememberedSoAnUnmappedTickerIsNotAskedAboutOnEveryRender() {
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of());

        CryptoLogoService service = service();

        service.getLogoUrls(Set.of("NOPE"));
        service.getLogoUrls(Set.of("NOPE"));
        service.getLogoUrls(Set.of("NOPE"));

        // One call, not three: the negative entry is cached exactly like a hit.
        verify(logoProvider, times(1)).getLogoUrls(Set.of("NOPE"));
    }

    @Test
    void aSecondPageServedFromTheCacheCostsNoRequest() {
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/btc.png"));

        CryptoLogoService service = service();
        service.getLogoUrls(Set.of("BTC"));
        service.getLogoUrls(Set.of("BTC"));

        verify(logoProvider, times(1)).getLogoUrls(any());
    }

    @Test
    void onlyTheUncachedTickersAreFetchedOnALaterPage() {
        when(logoProvider.getLogoUrls(Set.of("BTC"))).thenReturn(Map.of("BTC", "https://img/btc.png"));
        when(logoProvider.getLogoUrls(Set.of("ETH"))).thenReturn(Map.of("ETH", "https://img/eth.png"));

        CryptoLogoService service = service();
        service.getLogoUrls(Set.of("BTC"));

        // BTC is still cached when ETH joins the portfolio, so the second call carries only ETH
        // rather than re-asking for the pair.
        assertThat(service.getLogoUrls(Set.of("BTC", "ETH")))
            .containsEntry("BTC", "https://img/btc.png")
            .containsEntry("ETH", "https://img/eth.png");
        verify(logoProvider, never()).getLogoUrls(Set.of("BTC", "ETH"));
    }

    @Test
    void anEmptyOrBlankTickerSetNeverReachesTheProvider() {
        CryptoLogoService service = service();

        assertThat(service.getLogoUrls(Set.of())).isEmpty();
        assertThat(service.getLogoUrls(Set.of("  "))).isEmpty();
        assertThat(service.getLogoUrls(null)).isEmpty();
        verify(logoProvider, never()).getLogoUrls(any());
    }

    @Test
    void aMissExpiresOnItsOwnShortClock_soAProviderOutageIsNotRememberedForADay() {
        // The bug this pins: an absent key is indistinguishable from "the provider did not
        // answer", so caching a miss for 24h turned one rate-limited render into blank marks on
        // every portfolio page until the next restart. A minute of silence, then ask again.
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of());
        CryptoLogoService service = service();

        service.getLogoUrls(Set.of("BTC"));
        clock.advanceSeconds(59);
        service.getLogoUrls(Set.of("BTC"));
        verify(logoProvider, times(1)).getLogoUrls(Set.of("BTC"));

        clock.advanceSeconds(2);
        service.getLogoUrls(Set.of("BTC"));
        verify(logoProvider, times(2)).getLogoUrls(Set.of("BTC"));
    }

    @Test
    void aHitKeepsItsLongClock_acrossTheWindowAMissWouldHaveExpired() {
        // The other half of the split: a resolved URL is not time-sensitive, so the short miss
        // TTL must not also shorten it into CoinGecko calls that learn nothing.
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/btc.png"));
        CryptoLogoService service = service();

        service.getLogoUrls(Set.of("BTC"));
        clock.advanceSeconds(23 * 3600);
        assertThat(service.getLogoUrls(Set.of("BTC"))).containsEntry("BTC", "https://img/btc.png");

        verify(logoProvider, times(1)).getLogoUrls(any());
    }

    @Test
    void aHitIsRefreshedOnceItsOwnDayIsUp() {
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/old.png"));
        CryptoLogoService service = service();
        service.getLogoUrls(Set.of("BTC"));

        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of("BTC", "https://img/new.png"));
        clock.advanceSeconds(24 * 3600 + 1);

        assertThat(service.getLogoUrls(Set.of("BTC"))).containsEntry("BTC", "https://img/new.png");
    }

    @Test
    void aProviderReturningNothingYieldsAnEmptyMapRatherThanAFailure() {
        when(logoProvider.getLogoUrls(any())).thenReturn(Map.of());

        CryptoLogoService service = service();

        assertThat(service.getLogoUrls(Set.of("BTC"))).isEmpty();
    }
}
