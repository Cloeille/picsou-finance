package com.picsou.port;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankConnectorPortTest {

    @Test
    void parseInstitutionId_nameAndCountry_splitsBoth() {
        var parsed = BankConnectorPort.parseInstitutionId("LHV Pank::EE");

        assertThat(parsed.name()).isEqualTo("LHV Pank");
        assertThat(parsed.country()).isEqualTo("EE");
    }

    @Test
    void parseInstitutionId_blankCountrySegment_returnsBlankCountry() {
        // "Name::" — a trailing blank country must not be silently coerced by
        // split()'s default trailing-empty-removal into "no separator at all".
        // Falling back to a concrete default (if any) is the caller's decision.
        var parsed = BankConnectorPort.parseInstitutionId("Some Bank::");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEmpty();
    }

    @Test
    void parseInstitutionId_noSeparator_returnsBlankCountry() {
        var parsed = BankConnectorPort.parseInstitutionId("Some Bank");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEmpty();
    }

    @Test
    void parseInstitutionId_threeSegmentId_readsCountryFromTheSecondSegment() {
        // Current id format is "name::country::psuType". Reading the LAST segment
        // would hand callers "business" as a country code.
        var parsed = BankConnectorPort.parseInstitutionId("Swan::FR::business");

        assertThat(parsed.name()).isEqualTo("Swan");
        assertThat(parsed.country()).isEqualTo("FR");
    }

    @Test
    void parseInstitutionId_threeSegmentBlankCountry_returnsBlankCountry() {
        var parsed = BankConnectorPort.parseInstitutionId("Some Bank::::personal");

        assertThat(parsed.name()).isEqualTo("Some Bank");
        assertThat(parsed.country()).isEmpty();
    }

    @Test
    void parseInstitutionId_nullInput_returnsBlankNameAndCountry_ratherThanThrowing() {
        var parsed = BankConnectorPort.parseInstitutionId(null);

        assertThat(parsed.name()).isEmpty();
        assertThat(parsed.country()).isEmpty();
    }
}
