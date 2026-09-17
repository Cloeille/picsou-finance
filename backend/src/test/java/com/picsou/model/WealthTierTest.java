package com.picsou.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class WealthTierTest {

    /**
     * The compiler already refuses an unmapped {@link AccountType} — {@code WealthTier.of} is an
     * exhaustive switch with no default. This pins the runtime half: that none of the arms
     * returns null, which a future refactor to a Map would silently allow.
     */
    @ParameterizedTest
    @EnumSource(AccountType.class)
    void everyAccountTypeMapsToATier(AccountType type) {
        assertThat(WealthTier.of(type)).isNotNull();
    }

    @Test
    void frenchPassbooksAndCurrentAccountsAreTheSafetyNet() {
        assertThat(WealthTier.of(AccountType.CHECKING)).isEqualTo(WealthTier.SAFETY_NET);
        assertThat(WealthTier.of(AccountType.LIVRET_A)).isEqualTo(WealthTier.SAFETY_NET);
        assertThat(WealthTier.of(AccountType.LEP)).isEqualTo(WealthTier.SAFETY_NET);
        assertThat(WealthTier.of(AccountType.PEL)).isEqualTo(WealthTier.SAFETY_NET);
    }

    @Test
    void everyEquityWrapperLandsInEquity() {
        assertThat(WealthTier.of(AccountType.PEA)).isEqualTo(WealthTier.EQUITY);
        assertThat(WealthTier.of(AccountType.COMPTE_TITRES)).isEqualTo(WealthTier.EQUITY);
        assertThat(WealthTier.of(AccountType.EMPLOYEE_SAVINGS)).isEqualTo(WealthTier.EQUITY);
        // The pyramid counts life insurance as listed equity: a euro-fund-only policy is the
        // exception, and it is what the per-account override exists for.
        assertThat(WealthTier.of(AccountType.ASSURANCE_VIE)).isEqualTo(WealthTier.EQUITY);
    }

    @Test
    void scpiIsPropertyRatherThanABrokerageLine() {
        assertThat(WealthTier.of(AccountType.SCPI)).isEqualTo(WealthTier.REAL_ESTATE);
        assertThat(WealthTier.of(AccountType.REAL_ESTATE)).isEqualTo(WealthTier.REAL_ESTATE);
    }

    @Test
    void otherIsTheAlternativeBucket() {
        assertThat(WealthTier.of(AccountType.OTHER)).isEqualTo(WealthTier.ALTERNATIVE);
    }
}
