package com.picsou.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

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
        // An individual PER holds unit-linked lines plus a euro fund, the same shape as a policy.
        assertThat(AccountType.PER.isInvestment()).isTrue();
    }

    @Test
    void aRetirementPlanIsWealthNotDebt() {
        assertThat(AccountType.PER.isLiability()).isFalse();
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

    @Test
    void cashAccountsAreLedgers() {
        assertThat(AccountType.CHECKING.isLedger()).isTrue();
        assertThat(AccountType.LIVRET_A.isLedger()).isTrue();
        assertThat(AccountType.CREDIT_CARD.isLedger()).isTrue();
        assertThat(AccountType.LOAN.isLedger()).isTrue();
        assertThat(AccountType.OTHER.isLedger()).isTrue();
    }

    @Test
    void investmentAndValuedAccountsAreNotLedgers() {
        // Positions price an investment account; a valuation prices the other three. Summing
        // their transactions would replace that value.
        assertThat(Arrays.stream(AccountType.values()).filter(t -> !t.isLedger()))
            .containsExactlyInAnyOrder(AccountType.PEA, AccountType.COMPTE_TITRES, AccountType.CRYPTO,
                AccountType.ASSURANCE_VIE, AccountType.PER, AccountType.EMPLOYEE_SAVINGS,
                AccountType.REAL_ESTATE, AccountType.SCPI);
    }
}
