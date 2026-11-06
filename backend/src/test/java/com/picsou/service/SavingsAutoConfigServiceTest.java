package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;
import com.picsou.repository.SavingsInterestConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void configuresALivretAWithTheRegulatedRateAndCeiling() {
        when(repository.findByAccountId(42L)).thenReturn(Optional.empty());

        assertThat(service.configureIfMissing(account, AccountType.LIVRET_A)).isTrue();

        SavingsInterestConfig saved = capture();
        assertThat(saved.getProduct()).isEqualTo(SavingsProduct.LIVRET_A);
        assertThat(saved.getAnnualRate()).isEqualByComparingTo(RegulatedRates.LIVRET_A);
        assertThat(saved.getRateBasis()).isEqualTo(RateBasis.NET);
        assertThat(saved.getCeiling()).isEqualByComparingTo(RegulatedRates.LIVRET_A_CEILING);
    }

    @Test
    void configuresLepAndLdds() {
        when(repository.findByAccountId(42L)).thenReturn(Optional.empty());

        assertThat(service.configureIfMissing(account, AccountType.LEP)).isTrue();
        assertThat(service.configureIfMissing(account, AccountType.LDDS)).isTrue();

        ArgumentCaptor<SavingsInterestConfig> captor = ArgumentCaptor.forClass(SavingsInterestConfig.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(SavingsInterestConfig::getProduct)
            .containsExactly(SavingsProduct.LEP, SavingsProduct.LDDS);
        assertThat(captor.getAllValues().get(0).getCeiling())
            .isEqualByComparingTo(RegulatedRates.LEP_CEILING);
        assertThat(captor.getAllValues().get(1).getCeiling())
            .isEqualByComparingTo(RegulatedRates.LDDS_CEILING);
    }

    @Test
    void leavesANonRegulatedAccountAlone() {
        assertThat(service.configureIfMissing(account, AccountType.CHECKING)).isFalse();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void neverOverwritesAUserConfiguration() {
        when(repository.findByAccountId(42L))
            .thenReturn(Optional.of(SavingsInterestConfig.builder().account(account).build()));

        assertThat(service.configureIfMissing(account, AccountType.LIVRET_A)).isFalse();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private SavingsInterestConfig capture() {
        ArgumentCaptor<SavingsInterestConfig> captor = ArgumentCaptor.forClass(SavingsInterestConfig.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
