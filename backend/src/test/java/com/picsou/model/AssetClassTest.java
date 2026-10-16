package com.picsou.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetClassTest {

    @Test
    void aRetirementPlanCountsAsAnInvestment() {
        // Same bucket as an assurance-vie: market exposure, and incoming transfers are
        // contributions the allocation view tracks.
        assertThat(AssetClass.of(AccountType.PER)).isEqualTo(AssetClass.INVESTMENT);
        assertThat(AssetClass.of(AccountType.PER).tracksContributions()).isTrue();
    }
}
