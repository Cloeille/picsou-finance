package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.ScpiPosition;
import com.picsou.model.ScpiProvider;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import com.picsou.repository.SavingsInterestConfigRepository;
import com.picsou.repository.ScpiPositionRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Soft-delete must release the fund links. The unique indexes do not exclude a
 * deleted account, so a leftover code would block the next link and a later sync
 * would keep writing to the deleted row.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceDeleteTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock RealEstateMetadataRepository realEstateMetadataRepository;
    @Mock PropertyValuationRepository propertyValuationRepository;
    @Mock DebtRepository debtRepository;
    @Mock SavingsInterestConfigRepository savingsInterestConfigRepository;
    @Mock PriceService priceService;
    @Mock LoanAmortizationService loanAmortizationService;
    @Mock AccountAccessResolver accessResolver;
    @Mock BankLogoResolver bankLogoResolver;
    @Mock ScpiPositionRepository scpiPositionRepository;
    @Mock ScpiFundService scpiFundService;
    @Mock PeerLoanTermsRepository peerLoanTermsRepository;
    @InjectMocks AccountService service;

    @Test
    void delete_clearsBothFundLinksBeforeMarkingTheAccountDeleted() {
        Account account = Account.builder().id(10L).type(AccountType.SCPI).build();
        ScpiPosition position = new ScpiPosition();
        position.setCorumFundCode("US");
        position.setSofidyFundCode("DY");
        when(accountRepository.findByIdAndMemberId(10L, 1L)).thenReturn(Optional.of(account));
        when(scpiPositionRepository.findByAccountIdAndMemberId(10L, 1L)).thenReturn(Optional.of(position));

        service.delete(10L, 1L);

        assertThat(position.getCorumFundCode()).isNull();
        assertThat(position.getSofidyFundCode()).isNull();
        assertThat(account.getDeletedAt()).isNotNull();
        verify(scpiPositionRepository).save(position);
        verify(accountRepository).save(account);
        // #232: the sync must not recreate the account it just lost.
        verify(scpiFundService).tombstone(1L, ScpiProvider.CORUM, "US");
        verify(scpiFundService).tombstone(1L, ScpiProvider.SOFIDY, "DY");
    }

    @Test
    void delete_unlinksLoansAndPeerLoansBeforeMarkingTheAccountDeleted() {
        // The foreign keys are ON DELETE SET NULL, which a soft-delete never fires: a loan
        // left pointing at the deleted property failed to load and the accounts page 500ed.
        Account property = Account.builder().id(20L).type(AccountType.REAL_ESTATE).build();
        when(accountRepository.findByIdAndMemberId(20L, 1L)).thenReturn(Optional.of(property));

        service.delete(20L, 1L);

        var order = inOrder(debtRepository, peerLoanTermsRepository, accountRepository);
        order.verify(debtRepository).unlinkAccount(20L);
        order.verify(peerLoanTermsRepository).unlinkAccount(20L);
        order.verify(accountRepository).save(property);
        assertThat(property.getDeletedAt()).isNotNull();
    }
}
