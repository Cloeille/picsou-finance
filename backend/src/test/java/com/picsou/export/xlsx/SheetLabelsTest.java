package com.picsou.export.xlsx;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SheetLabelsTest {

    @Test
    void missingKey_fallsBackToEnglish() {
        SheetLabels labels = SheetLabels.of(Map.of());

        assertThat(labels.get(LabelKey.QUANTITY)).isEqualTo("Quantity");
        assertThat(labels.get(LabelKey.PNL_PERCENT)).isEqualTo("Gain / loss (%)");
    }

    @Test
    void suppliedKey_overridesTheDefault_inAnyCasing() {
        SheetLabels labels = SheetLabels.of(Map.of(
            "quantity", "Quantité",
            "PNL_PERCENT", "Gain / perte (%)",
            "average-buy-in", "Prix de revient"
        ));

        assertThat(labels.get(LabelKey.QUANTITY)).isEqualTo("Quantité");
        assertThat(labels.get(LabelKey.PNL_PERCENT)).isEqualTo("Gain / perte (%)");
        assertThat(labels.get(LabelKey.AVERAGE_BUY_IN)).isEqualTo("Prix de revient");
    }

    @Test
    void unknownKey_isIgnoredRatherThanRejected() {
        // A client running ahead of the server must not fail the export.
        SheetLabels labels = SheetLabels.of(Map.of("somethingWeDoNotKnowAbout", "?"));

        assertThat(labels.get(LabelKey.QUANTITY)).isEqualTo("Quantity");
    }

    @Test
    void controlCharacters_areStripped_soAHeaderCannotSplitTheGrid() {
        SheetLabels labels = SheetLabels.of(Map.of("quantity", "  Quan\ntité\t "));

        assertThat(labels.get(LabelKey.QUANTITY)).isEqualTo("Quantité");
    }

    @Test
    void overlongLabel_isCapped() {
        SheetLabels labels = SheetLabels.of(Map.of("quantity", "x".repeat(500)));

        assertThat(labels.get(LabelKey.QUANTITY)).hasSize(120);
    }

    @Test
    void blankOrNullLabel_fallsBackRatherThanPrintingAnEmptyHeader() {
        Map<String, String> supplied = new HashMap<>();
        supplied.put("quantity", "   ");
        supplied.put("ticker", null);

        SheetLabels labels = SheetLabels.of(supplied);

        assertThat(labels.get(LabelKey.QUANTITY)).isEqualTo("Quantity");
        assertThat(labels.get(LabelKey.TICKER)).isEqualTo("Ticker");
    }

    @Test
    void everyKeyResolves_soNoSheetCanPrintANullHeader() {
        SheetLabels labels = SheetLabels.english();

        for (LabelKey key : LabelKey.values()) {
            assertThat(labels.get(key)).as(key.name()).isNotBlank();
        }
    }
}
