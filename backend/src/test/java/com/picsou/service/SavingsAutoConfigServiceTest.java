package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.repository.SavingsInterestConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavingsAutoConfigServiceTest {

    @Mock SavingsInterestConfigRepository repository;

    private SavingsAutoConfigService service;
    private Account account;

    @BeforeEach
    void setUp() {
        service = new SavingsAutoConfigService(repository);
        account = Account.builder().id(42L).name("Livret A").build();
    }

    private static ArgumentMatcher<BigDecimal> amount(BigDecimal expected) {
        return actual -> actual != null && actual.compareTo(expected) == 0;
    }

    private void acceptsTheInsert() {
        when(repository.insertIfAbsent(anyLong(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void configuresALivretAWithTheRegulatedRateAndCeiling() {
        when(repository.findByAccountId(42L)).thenReturn(Optional.empty());
        acceptsTheInsert();

        assertThat(service.configureIfMissing(account, AccountType.LIVRET_A)).isTrue();

        verify(repository).insertIfAbsent(eq(42L), eq("LIVRET_A"), argThat(amount(RegulatedRates.LIVRET_A)),
            eq("NET"), argThat(amount(new BigDecimal("22950.00"))));
    }

    @Test
    void configuresLepAndLdds() {
        when(repository.findByAccountId(42L)).thenReturn(Optional.empty());
        acceptsTheInsert();

        assertThat(service.configureIfMissing(account, AccountType.LEP)).isTrue();
        assertThat(service.configureIfMissing(account, AccountType.LDDS)).isTrue();

        verify(repository).insertIfAbsent(eq(42L), eq("LEP"), argThat(amount(RegulatedRates.LEP)),
            eq("NET"), argThat(amount(new BigDecimal("10000.00"))));
        verify(repository).insertIfAbsent(eq(42L), eq("LDDS"), argThat(amount(RegulatedRates.LDDS)),
            eq("NET"), argThat(amount(new BigDecimal("12000.00"))));
    }

    @Test
    void leavesANonRegulatedAccountAlone() {
        assertThat(service.configureIfMissing(account, AccountType.CHECKING)).isFalse();
        verify(repository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void neverOverwritesAUserConfiguration() {
        when(repository.findByAccountId(42L))
            .thenReturn(Optional.of(SavingsInterestConfig.builder().account(account).build()));

        assertThat(service.configureIfMissing(account, AccountType.LIVRET_A)).isFalse();
        verify(repository, never()).insertIfAbsent(anyLong(), any(), any(), any(), any());
    }

    @Test
    void treatsAConcurrentInsertAsAlreadyConfigured() {
        when(repository.findByAccountId(42L)).thenReturn(Optional.empty());
        // ON CONFLICT DO NOTHING inserted nothing: a concurrent save already configured it.
        when(repository.insertIfAbsent(anyLong(), any(), any(), any(), any())).thenReturn(0);

        assertThat(service.configureIfMissing(account, AccountType.LIVRET_A)).isFalse();
    }
}
