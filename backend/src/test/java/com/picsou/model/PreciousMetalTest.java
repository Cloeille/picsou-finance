package com.picsou.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreciousMetalTest {

    @Test
    void registryOrderMatchesTheCatalogueContract() {
        assertThat(Arrays.stream(PreciousMetal.values()).map(PreciousMetal::ticker).toList()).containsExactly(
            "GOLD:GRAM", "GOLD:NAPOLEON-20F", "GOLD:NAPOLEON-10F", "GOLD:SWISS-20F", "GOLD:LATIN-UNION-20F",
            "GOLD:TUNISIA-20F", "GOLD:SOVEREIGN", "GOLD:SOVEREIGN-EII", "GOLD:HALF-SOVEREIGN",
            "GOLD:KRUGERRAND", "GOLD:USD-20", "GOLD:USD-10", "GOLD:USD-5", "GOLD:PESO-50", "GOLD:FLORIN-10",
            "GOLD:REICHSMARK-20");
    }

    @Test
    void onlyTheGramIsSpotPricedAndEveryCoinHasADistinctSlug() {
        List<PreciousMetal> coins = Arrays.stream(PreciousMetal.values())
            .filter(m -> m.unit() == PreciousMetal.Unit.COIN).toList();

        assertThat(PreciousMetal.GOLD_GRAM.unit()).isEqualTo(PreciousMetal.Unit.GRAM);
        assertThat(PreciousMetal.GOLD_GRAM.loomisSlug()).isNull();
        assertThat(coins).hasSize(PreciousMetal.values().length - 1);
        assertThat(coins).allSatisfy(c -> assertThat(c.loomisSlug()).isNotBlank());
        assertThat(new HashSet<>(coins.stream().map(PreciousMetal::loomisSlug).toList())).hasSameSizeAs(coins);
    }

    @Test
    void everyEntryHasANameAndAUniqueTicker() {
        assertThat(Arrays.stream(PreciousMetal.values()).map(PreciousMetal::displayName)).allSatisfy(n -> assertThat(n).isNotBlank());
        assertThat(Arrays.stream(PreciousMetal.values()).map(PreciousMetal::ticker).distinct().count())
            .isEqualTo(PreciousMetal.values().length);
    }

    @Test
    void everyTickerFitsTheAccountTickerLimit() {
        // A metals account can carry a catalogue ticker as its own ticker: AccountRequest caps it
        // at @Size(max = 20), accounts.ticker is VARCHAR(20) and the account form's zod schema
        // caps it at 20 too. A longer ticker could be picked for a holding but never saved on an account.
        assertThat(Arrays.stream(PreciousMetal.values()).map(PreciousMetal::ticker)).allSatisfy(ticker ->
            assertThat(ticker.length()).as("%s must fit the 20-character account ticker limit", ticker)
                .isLessThanOrEqualTo(20));
    }

    @Test
    void fromTicker_isCaseInsensitiveAndNullSafe() {
        assertThat(PreciousMetal.fromTicker("gold:napoleon-20f")).contains(PreciousMetal.NAPOLEON_20F);
        assertThat(PreciousMetal.fromTicker(" GOLD:GRAM ")).contains(PreciousMetal.GOLD_GRAM);
        assertThat(PreciousMetal.fromTicker("GOLD")).isEmpty();
        assertThat(PreciousMetal.fromTicker(null)).isEmpty();
    }

    @Test
    void canonicalTickerFor_metalsAccountTakesCatalogueTickersOnly() {
        assertThat(PreciousMetal.canonicalTickerFor(AccountType.PRECIOUS_METALS, " gold:gram ")).isEqualTo("GOLD:GRAM");
        assertThatThrownBy(() -> PreciousMetal.canonicalTickerFor(AccountType.PRECIOUS_METALS, "AAPL"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AAPL");
    }

    @Test
    void canonicalTickerFor_leavesCashMovementsAndOtherAccountTypesAlone() {
        assertThat(PreciousMetal.canonicalTickerFor(AccountType.PRECIOUS_METALS, null)).isNull();
        assertThat(PreciousMetal.canonicalTickerFor(AccountType.PRECIOUS_METALS, " ")).isEqualTo(" ");
        assertThat(PreciousMetal.canonicalTickerFor(AccountType.COMPTE_TITRES, "aapl")).isEqualTo("aapl");
    }
}
