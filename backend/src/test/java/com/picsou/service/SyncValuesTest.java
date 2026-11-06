package com.picsou.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SyncValuesTest {

    @ParameterizedTest
    @CsvSource({
        // the absolute floor covers a few lines
        "100.05, 100.00, 3, true",
        "100.06, 100.00, 3, false",
        // one cent per line beyond that, in both directions
        "99.90, 100.00, 10, true",
        "100.11, 100.00, 10, false",
        // never relative to the total: a large contract gets no extra room
        "1000001.00, 1000000.00, 10, false",
    })
    void linesClose_allowsOneCentPerRoundedLineAboveTheAbsoluteFloor(
        String actual, String expected, int lines, boolean close
    ) {
        assertThat(SyncValues.linesClose(new BigDecimal(actual), new BigDecimal(expected), lines))
            .isEqualTo(close);
    }
}
