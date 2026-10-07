package com.picsou.dto;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CardNature;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResponseCardNatureTest {

    private static Account card() {
        return Account.builder().id(5L).name("Card").type(AccountType.CREDIT_CARD)
            .cardNature(CardNature.DEFERRED_DEBIT).build();
    }

    @Test
    void from_exposesTheCardNature() {
        assertThat(AccountResponse.from(card(), BigDecimal.ZERO).cardNature())
            .isEqualTo(CardNature.DEFERRED_DEBIT);
    }

    @Test
    void from_leavesTheNatureNullForOtherAccounts() {
        Account checking = Account.builder().id(6L).name("Courant").type(AccountType.CHECKING).build();
        assertThat(AccountResponse.from(checking, BigDecimal.ZERO).cardNature()).isNull();
    }

    @Test
    void everyCopyingFactoryKeepsTheCardNature() {
        AccountResponse base = AccountResponse.from(card(), BigDecimal.ZERO);
        assertThat(base.withRealEstate(null).cardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(base.withDebt(null).cardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(base.withOpenedAt(null).cardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(base.withSavingsConfig(null).cardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(base.withViewer(null, true).cardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
        assertThat(base.withScpi(null).cardNature()).isEqualTo(CardNature.DEFERRED_DEBIT);
    }
}
