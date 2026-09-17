package com.picsou.service;

import com.picsou.adapter.CoinGeckoPriceProvider;
import com.picsou.adapter.YahooFinancePriceProvider;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityRef;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.port.EtfCompositionProvider;
import com.picsou.dto.FundFacts;
import com.picsou.model.DistributionPolicy;
import com.picsou.model.Replication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityInsightServiceTest {

    @Mock YahooFinancePriceProvider yahoo;
    @Mock CoinGeckoPriceProvider coinGecko;

    private SecurityInsightService serviceWith(EtfCompositionProvider... providers) {
        return new SecurityInsightService(List.of(providers), yahoo, coinGecko);
    }

    private static EtfCompositionProvider fakeProvider(boolean supports, EtfComposition comp) {
        return new EtfCompositionProvider() {
            @Override public boolean supports(SecurityRef ref) { return supports; }
            @Override public Optional<EtfComposition> fetch(SecurityRef ref) {
                return Optional.ofNullable(comp);
            }
        };
    }

    private static EtfComposition composition(List<WeightedSlice> companies,
                                              List<WeightedSlice> countries,
                                              List<WeightedSlice> sectors) {
        return new EtfComposition(companies, countries, sectors, "Boursorama", LocalDate.of(2026, 4, 30));
    }

    @Test
    void crypto_isDetectedFromCoinGecko_andHasNoComposition() {
        when(coinGecko.supports("BTC")).thenReturn(true);
        var service = serviceWith();

        SecurityInsightResponse r = service.getInsight("BTC", "Bitcoin");

        assertThat(r.assetType()).isEqualTo("CRYPTO");
        assertThat(r.composition()).isNull();
        verify(yahoo, never()).getInstrumentType("BTC");
    }

    @Test
    void equity_mapsToStock_withNoComposition() {
        when(coinGecko.supports("MC.PA")).thenReturn(false);
        when(yahoo.getInstrumentType("MC.PA")).thenReturn(Optional.of("EQUITY"));
        var service = serviceWith();

        SecurityInsightResponse r = service.getInsight("MC.PA", "LVMH");

        assertThat(r.assetType()).isEqualTo("STOCK");
        assertThat(r.composition()).isNull();
    }

    @Test
    void unknown_whenInstrumentTypeMissing() {
        when(coinGecko.supports("XYZ")).thenReturn(false);
        when(yahoo.getInstrumentType("XYZ")).thenReturn(Optional.empty());
        var service = serviceWith();

        assertThat(service.getInsight("XYZ", null).assetType()).isEqualTo("UNKNOWN");
    }

    @Test
    void etf_returnsCompositionFromFirstResolvingProvider() {
        when(coinGecko.supports("NQSE")).thenReturn(false);
        when(yahoo.getInstrumentType("NQSE")).thenReturn(Optional.of("ETF"));

        var comp = composition(
            List.of(new WeightedSlice("NVIDIA Corp", new BigDecimal("8.29"))),
            List.of(new WeightedSlice("US", new BigDecimal("97.25"))),
            List.of(new WeightedSlice("technology", new BigDecimal("57.42")))
        );
        var service = serviceWith(fakeProvider(true, comp));

        SecurityInsightResponse r = service.getInsight("NQSE", "iShares NASDAQ 100");

        assertThat(r.assetType()).isEqualTo("ETF");
        assertThat(r.composition()).isNotNull();
        assertThat(r.composition().source()).isEqualTo("Boursorama");
        assertThat(r.composition().companies()).extracting(WeightedSlice::label).containsExactly("NVIDIA Corp");
        assertThat(r.composition().countries()).extracting(WeightedSlice::label).containsExactly("US");
        assertThat(r.composition().sectors()).extracting(WeightedSlice::label).containsExactly("technology");
    }

    @Test
    void etf_withCountriesAndSectorsButNoCompanies_stillReturnsComposition() {
        when(coinGecko.supports("PUST")).thenReturn(false);
        when(yahoo.getInstrumentType("PUST")).thenReturn(Optional.of("ETF"));

        var comp = composition(
            List.of(),
            List.of(new WeightedSlice("US", new BigDecimal("96.88"))),
            List.of(new WeightedSlice("technology", new BigDecimal("53.65")))
        );
        var service = serviceWith(fakeProvider(true, comp));

        SecurityInsightResponse r = service.getInsight("PUST", "Amundi PEA Nasdaq-100");

        assertThat(r.composition()).isNotNull();
        assertThat(r.composition().companies()).isEmpty();
        assertThat(r.composition().countries()).isNotEmpty();
    }

    @Test
    void etf_withProviderReturningAllEmptyBars_hasNullComposition() {
        when(coinGecko.supports("EMPT")).thenReturn(false);
        when(yahoo.getInstrumentType("EMPT")).thenReturn(Optional.of("ETF"));
        var emptyComp = composition(List.of(), List.of(), List.of());
        var service = serviceWith(fakeProvider(true, emptyComp));

        assertThat(service.getInsight("EMPT", "x").composition()).isNull();
    }

    @Test
    void etf_withNoResolvingProvider_hasNullComposition() {
        when(coinGecko.supports("CW8")).thenReturn(false);
        when(yahoo.getInstrumentType("CW8")).thenReturn(Optional.of("ETF"));
        var service = serviceWith(fakeProvider(false, null));

        SecurityInsightResponse r = service.getInsight("CW8", "Amundi MSCI World");

        assertThat(r.assetType()).isEqualTo("ETF");
        assertThat(r.composition()).isNull();
    }

    @Test
    void etf_withFirstProviderReturningEmpty_fallsThroughToSecond() {
        when(coinGecko.supports("IWDA")).thenReturn(false);
        when(yahoo.getInstrumentType("IWDA")).thenReturn(Optional.of("ETF"));

        var emptyProvider = fakeProvider(true, null); // Optional.empty()
        var realComp = composition(
            List.of(new WeightedSlice("Apple", new BigDecimal("4.50"))),
            List.of(), List.of()
        );
        var service = serviceWith(emptyProvider, fakeProvider(true, realComp));

        assertThat(service.getInsight("IWDA", "iShares").composition()).isNotNull();
        assertThat(service.getInsight("IWDA", "iShares").composition().companies())
            .extracting(WeightedSlice::label).containsExactly("Apple");
    }

    @Test
    void result_isCached_acrossCalls() {
        when(coinGecko.supports("MC.PA")).thenReturn(false);
        when(yahoo.getInstrumentType("MC.PA")).thenReturn(Optional.of("EQUITY"));
        var service = serviceWith();

        service.getInsight("MC.PA", "LVMH");
        service.getInsight("MC.PA", "LVMH");

        verify(yahoo, times(1)).getInstrumentType("MC.PA");
    }

    @Test
    void theFeeSurvivesEvenWhenAnotherProviderSuppliedTheBreakdown() {
        when(coinGecko.supports("CW8.PA")).thenReturn(false);
        when(yahoo.getInstrumentType("CW8.PA")).thenReturn(Optional.of("ETF"));

        EtfCompositionProvider slicesOnly = fakeProvider(true, composition(
            List.of(), List.of(new WeightedSlice("US", new BigDecimal("97.25"))), List.of()));
        EtfCompositionProvider factsOnly = fakeProvider(true, new EtfComposition(
            List.of(), List.of(), List.of(), "justETF", null,
            new FundFacts(new BigDecimal("0.38"), DistributionPolicy.ACCUMULATING,
                Replication.SYNTHETIC, "LU", "justETF")));

        SecurityInsightResponse response =
            serviceWith(slicesOnly, factsOnly).getInsight("CW8.PA", null, "LU1681043599");

        // Boursorama has the deeper breakdown and no fee; justETF has the fee. First-wins would
        // have thrown the fee away, which is the same trap resolveEquity already avoids.
        assertThat(response.composition().countries()).extracting(WeightedSlice::label).containsExactly("US");
        assertThat(response.composition().facts().terPercent()).isEqualByComparingTo("0.38");
        assertThat(response.composition().source()).contains("Boursorama").contains("justETF");
    }

    @Test
    void factsWithoutABreakdownAreStillWorthReturning() {
        when(coinGecko.supports("ODD")).thenReturn(false);
        when(yahoo.getInstrumentType("ODD")).thenReturn(Optional.of("ETF"));

        EtfCompositionProvider factsOnly = fakeProvider(true, new EtfComposition(
            List.of(), List.of(), List.of(), "justETF", null,
            new FundFacts(new BigDecimal("0.07"), null, null, null, "justETF")));

        SecurityInsightResponse response = serviceWith(factsOnly).getInsight("ODD", null, "IE00B4L5Y983");

        // The fee scanner wants it; the fund simply reports as unclassified until a breakdown
        // turns up.
        assertThat(response.composition()).isNotNull();
        assertThat(response.composition().facts().terPercent()).isEqualByComparingTo("0.07");
        assertThat(response.composition().countries()).isEmpty();
    }
}
