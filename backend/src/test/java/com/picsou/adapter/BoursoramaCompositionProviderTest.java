package com.picsou.adapter;

import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityRef;
import com.picsou.dto.WeightedSlice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoursoramaCompositionProviderTest {

    @Mock BoursoramaClient client;

    private String fixture(String name) {
        try (var in = getClass().getResourceAsStream("/boursorama/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parsesPhysicalEtf_companiesCountriesSectors() {
        EtfComposition c = BoursoramaCompositionProvider.parse(fixture("nqse-composition.html"));

        assertThat(c.source()).isEqualTo("Boursorama");
        assertThat(c.asOf()).isEqualTo(LocalDate.of(2026, 5, 27));

        assertThat(c.companies()).extracting(WeightedSlice::label)
            .containsExactly("NVIDIA Corp", "Apple Inc", "Microsoft Corp");
        assertThat(c.companies().get(0).percent()).isEqualByComparingTo("8.29");

        // Countries mapped to ISO keys, value preserved
        assertThat(c.countries()).extracting(WeightedSlice::label).containsExactly("US", "NL", "CA");
        assertThat(c.countries().get(0).percent()).isEqualByComparingTo("97.25");

        // Sectors mapped to stable keys
        assertThat(c.sectors()).extracting(WeightedSlice::label)
            .containsExactly("technology", "communication_services", "consumer_cyclical", "healthcare");
    }

    @Test
    void parsesSyntheticEtf_dropsSwapLine_keepsCountriesAndSectors() {
        EtfComposition c = BoursoramaCompositionProvider.parse(fixture("pust-composition.html"));

        assertThat(c.companies()).isEmpty(); // only the swap line, filtered out
        assertThat(c.countries()).extracting(WeightedSlice::label).containsExactly("US", "NL", "CA");
        assertThat(c.sectors()).extracting(WeightedSlice::label)
            .containsExactly("technology", "communication_services", "energy");
        assertThat(c.asOf()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void parsesSymbolFromRedirectLocation() {
        assertThat(BoursoramaCompositionProvider.symbolFromLocation("/cours/1zNQSE/")).hasValue("1zNQSE");
        assertThat(BoursoramaCompositionProvider.symbolFromLocation("https://www.boursorama.com/cours/1rTPUST/"))
            .hasValue("1rTPUST");
        assertThat(BoursoramaCompositionProvider.symbolFromLocation("/recherche/?query=ZZZ")).isEmpty();
    }

    @Test
    void bareTicker_stripsExchangeSuffix() {
        assertThat(BoursoramaCompositionProvider.bareTicker("PUST.PA")).isEqualTo("PUST");
        assertThat(BoursoramaCompositionProvider.bareTicker("NQSE.DE")).isEqualTo("NQSE");
        assertThat(BoursoramaCompositionProvider.bareTicker("CW8")).isEqualTo("CW8");
    }

    @Test
    void parse_skipsSlicesWithNonNumericValue() {
        // Second entry has a non-numeric value → must be skipped, not emitted as 0.00%.
        String html = "<html>\"id\":\"regional\",\"amChartData\":"
            + "[{\"name\":\"Etats-Unis\",\"value\":97.25},{\"name\":\"Pays-Bas\",\"value\":\"N/A\"}]</html>";
        EtfComposition c = BoursoramaCompositionProvider.parse(html);
        assertThat(c.countries()).extracting(WeightedSlice::label).containsExactly("US");
        assertThat(c.countries().get(0).percent()).isEqualByComparingTo("97.25");
    }

    @Test
    void emptyHtml_yieldsEmptyComposition() {
        EtfComposition c = BoursoramaCompositionProvider.parse("<html></html>");
        assertThat(c.companies()).isEmpty();
        assertThat(c.countries()).isEmpty();
        assertThat(c.sectors()).isEmpty();
        assertThat(c.asOf()).isNull();
    }

    /**
     * The reason the ETF look-through found so little.
     *
     * <p>Boursorama's search resolves an ISIN to the same symbol as the ticker, while
     * {@code OpenFigiIsinConverter.pickBest} prefers US OTC listings for non-US ISINs — so the
     * ticker stored for a European fund is frequently one Boursorama has never heard of. Asking
     * by ISIN when we have one is what makes those funds resolve at all.
     */
    @Test
    void resolvesByIsinWhenOneIsKnown() {
        BoursoramaCompositionProvider provider = new BoursoramaCompositionProvider(client);
        when(client.resolveSymbol("LU1681043599")).thenReturn(Optional.of("1rTCW8"));
        lenient().when(client.get(any(), any())).thenReturn(Optional.empty());

        provider.fetch(new SecurityRef("MWRDF", "Amundi MSCI World", "LU1681043599"));

        verify(client).resolveSymbol("LU1681043599");
    }

    @Test
    void fallsBackToTheTickerWhenNoIsinIsKnown() {
        BoursoramaCompositionProvider provider = new BoursoramaCompositionProvider(client);
        when(client.resolveSymbol("PUST.PA")).thenReturn(Optional.of("1rTPUST"));
        lenient().when(client.get(any(), any())).thenReturn(Optional.empty());

        provider.fetch(SecurityRef.of("PUST.PA", "Amundi Nasdaq"));

        verify(client).resolveSymbol("PUST.PA");
    }

    @Test
    void aSecurityWithNeitherIdentifierIsNotAttempted() {
        BoursoramaCompositionProvider provider = new BoursoramaCompositionProvider(client);

        assertThat(provider.supports(new SecurityRef(null, "nameless", null))).isFalse();
        assertThat(provider.fetch(new SecurityRef(null, "nameless", null))).isEmpty();
    }
}
