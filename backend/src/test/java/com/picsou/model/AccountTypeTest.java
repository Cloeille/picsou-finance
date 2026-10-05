package com.picsou.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTypeTest {

    @Test
    void investmentTypesHoldPositions() {
        assertThat(AccountType.PEA.isInvestment()).isTrue();
        assertThat(AccountType.COMPTE_TITRES.isInvestment()).isTrue();
        assertThat(AccountType.CRYPTO.isInvestment()).isTrue();
        // No connector syncs a life-insurance policy, so entering unit-linked lines by hand is
        // the only route to instrument-level detail — which the sector breakdown depends on.
        assertThat(AccountType.ASSURANCE_VIE.isInvestment()).isTrue();
    }

    @Test
    void scpiStaysABalanceBecauseItsSharesHaveNoQuote() {
        // Pricing SCPI shares per line would take the "held but unpriced" branch of
        // AccountService.valuation and report the account as worth nothing.
        assertThat(AccountType.SCPI.isInvestment()).isFalse();
    }

    @Test
    void balanceTypesAreNotInvestments() {
        assertThat(AccountType.CHECKING.isInvestment()).isFalse();
        assertThat(AccountType.LIVRET_A.isInvestment()).isFalse();
        assertThat(AccountType.REAL_ESTATE.isInvestment()).isFalse();
        assertThat(AccountType.LOAN.isInvestment()).isFalse();
        assertThat(AccountType.EMPLOYEE_SAVINGS.isInvestment()).isFalse();
    }
}
