package com.picsou.service;

import com.picsou.dto.ScpiPositionRequest;
import com.picsou.dto.ScpiPositionResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.DividendPolicy;
import com.picsou.model.FamilyMember;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiValuationStatus;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.ScpiPositionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A SCPI share is paper property. Its net-worth figure is the withdrawal price times the
 * share count — including a fraction bought by a scheduled plan or a reinvested dividend.
 * The subscription price is what was paid, never what could be withdrawn.
 */
@ExtendWith(MockitoExtension.class)
class ScpiPositionServiceTest {

    private static final FamilyMember ALICE = FamilyMember.builder().id(1L).displayName("Alice").build();

    @Mock AccountRepository accountRepository;
    @Mock ScpiPositionRepository positionRepository;
    @InjectMocks ScpiPositionService service;

    @Test
    void scpiIsNotRecomputedFromBuySell() {
        assertThat(AccountType.SCPI.isInvestment()).isFalse();
    }

    @Test
    void save_valuesTheAccountAtWithdrawalPriceTimesFractionalShares() {
        Account account = scpiAccount("0");
        when(accountRepository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(account));
        when(positionRepository.findByAccountIdAndMemberId(10L, 1L)).thenReturn(Optional.empty());
        when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScpiPositionResponse result = service.save(10L, 1L, request("12.345678", "1135", "1000.50"));

        assertThat(result.valuationStatus()).isEqualTo(ScpiValuationStatus.OK);
        assertThat(result.withdrawalValueEur()).isEqualByComparingTo("12351.850839");
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("12351.850839");
        assertThat(account.getCurrentBalance()).isNotEqualByComparingTo(new BigDecimal("12.345678").multiply(new BigDecimal("1135")));
    }

    @Test
    void save_withoutWithdrawalPrice_keepsThePreviousBalance() {
        Account account = scpiAccount("8000");
        account.setCurrency("USD");
        when(accountRepository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(account));
        when(positionRepository.findByAccountIdAndMemberId(10L, 1L)).thenReturn(Optional.empty());
        when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScpiPositionResponse result = service.save(10L, 1L, request("10", "1135", null));

        assertThat(result.valuationStatus()).isEqualTo(ScpiValuationStatus.PRICE_INCOMPLETE);
        assertThat(account.getCurrentBalance()).isEqualByComparingTo("8000");
        assertThat(account.getCurrency()).isEqualTo("EUR");
        verify(accountRepository).save(account);
        ArgumentCaptor<ScpiPosition> saved = ArgumentCaptor.forClass(ScpiPosition.class);
        verify(positionRepository).save(saved.capture());
        assertThat(saved.getValue().getSubscriptionPriceEur()).isEqualByComparingTo("1135");
    }

    @Test
    void save_onAPhysicalProperty_isRejected() {
        Account house = Account.builder()
            .id(10L).name("Maison").type(AccountType.REAL_ESTATE).currency("EUR")
            .currentBalance(new BigDecimal("400000")).member(ALICE)
            .build();
        when(accountRepository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(house));

        assertThatThrownBy(() -> service.save(10L, 1L, request("1", "1000", "880")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SCPI");
        verify(positionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    private static Account scpiAccount(String balance) {
        return Account.builder()
            .id(10L).name("CORUM Origin").type(AccountType.SCPI).currency("EUR")
            .currentBalance(new BigDecimal(balance)).member(ALICE)
            .build();
    }

    private static ScpiPositionRequest request(String shares, String subscription, String withdrawal) {
        return new ScpiPositionRequest(
            "FR0012345678",
            "CORUM",
            new BigDecimal(shares),
            subscription == null ? null : new BigDecimal(subscription),
            withdrawal == null ? null : new BigDecimal(withdrawal),
            DividendPolicy.REINVEST,
            null
        );
    }
}
