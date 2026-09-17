package com.picsou.service;

import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.model.*;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.repository.RealEstateMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gross versus net equity. The subtle part is that a property and the loan financing it are
 * two separate accounts with two separate splits, so they must be weighted independently.
 */
@ExtendWith(MockitoExtension.class)
class RealEstateSummaryServiceTest {

    private static final FamilyMember ALICE = FamilyMember.builder().id(1L).displayName("Alice").build();
    private static final FamilyMember BOB = FamilyMember.builder().id(2L).displayName("Bob").build();
    private static final BigDecimal FULL = new BigDecimal("100");

    @Mock AccountAccessResolver accessResolver;
    @Mock RealEstateMetadataRepository metadataRepository;
    @Mock PropertyValuationRepository valuationRepository;
    @Mock DebtRepository debtRepository;
    @Mock LoanAmortizationService loanAmortizationService;
    @Mock AccountService accountService;

    @InjectMocks RealEstateSummaryService service;

    @BeforeEach
    void defaults() {
        lenient().when(valuationRepository.findFirstByAccountIdOrderByValuedAtDesc(any()))
            .thenReturn(Optional.empty());
    }

    private static Account property(long id, String value) {
        return Account.builder()
            .id(id).name("Maison").type(AccountType.REAL_ESTATE).currency("EUR")
            .currentBalance(new BigDecimal(value)).color("#a855f7").member(ALICE)
            .build();
    }

    private static Account loanAccount(long id, FamilyMember owner) {
        return Account.builder()
            .id(id).name("Prêt immo").type(AccountType.LOAN).currency("EUR")
            .currentBalance(new BigDecimal("150000")).color("#ef4444").member(owner)
            .build();
    }

    private static RealEstateMetadata metadata(Account account, String price, String notary) {
        return RealEstateMetadata.builder()
            .account(account).member(ALICE)
            .purchasePrice(new BigDecimal(price))
            .notaryFees(new BigDecimal(notary))
            .rentalIncome(BigDecimal.ZERO)
            .build();
    }

    @Test
    void summarize_wholeOwnership_grossMinusDebtIsNet() {
        Account house = property(10L, "400000");
        Account loan = loanAccount(20L, ALICE);
        Debt debt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(loan).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("200000")).lenderName("BNP").build();

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house, loan));
        when(accessResolver.sharesFor(any(), eq(1L))).thenReturn(Map.of(10L, FULL, 20L, FULL));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("400000"));
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(house, "300000", "24000")));
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("150000"));

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.grossValue()).isEqualByComparingTo("400000");
        assertThat(result.outstandingDebt()).isEqualByComparingTo("150000");
        assertThat(result.netValue()).isEqualByComparingTo("250000");
        // Cost basis includes the notary fees -- ignoring them would overstate the gain by
        // the 8% they typically represent on a French purchase.
        assertThat(result.costBasis()).isEqualByComparingTo("324000");
        assertThat(result.unrealizedGain()).isEqualByComparingTo("76000");
        assertThat(result.loanToValue()).isEqualByComparingTo("37.50");
    }

    @Test
    void summarize_halfOwnedProperty_halvesValueAndCost() {
        Account house = property(10L, "400000");
        BigDecimal half = new BigDecimal("50");

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house));
        when(accessResolver.sharesFor(any(), eq(1L))).thenReturn(Map.of(10L, half));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("400000"));
        when(metadataRepository.findByAccountId(10L))
            .thenReturn(Optional.of(metadata(house, "300000", "24000")));
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of());

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.grossValue()).isEqualByComparingTo("200000");
        assertThat(result.costBasis()).isEqualByComparingTo("162000");
        assertThat(result.unrealizedGain()).isEqualByComparingTo("38000");
        assertThat(result.properties()).singleElement()
            .satisfies(line -> assertThat(line.sharePercent()).isEqualByComparingTo("50"));
    }

    @Test
    void summarize_propertyAndLoanSplitDifferently_weightsEachOnItsOwnShare() {
        Account house = property(10L, "400000");
        Account loan = loanAccount(20L, ALICE);
        Debt debt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(loan).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("200000")).build();

        // Alice owns 70% of the house but only half the mortgage. Assuming the two shares
        // match would understate her debt and overstate her equity.
        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house, loan));
        when(accessResolver.sharesFor(any(), eq(1L)))
            .thenReturn(Map.of(10L, new BigDecimal("70"), 20L, new BigDecimal("50")));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("400000"));
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.empty());
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("150000"));

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.grossValue()).isEqualByComparingTo("280000");
        assertThat(result.outstandingDebt()).isEqualByComparingTo("75000");
        assertThat(result.netValue()).isEqualByComparingTo("205000");
    }

    @Test
    void summarize_multipleLoansOnOneProperty_areAllCounted() {
        Account house = property(10L, "400000");
        Account main = loanAccount(20L, ALICE);
        Account works = loanAccount(21L, ALICE);
        Debt mainDebt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(main).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("200000")).build();
        Debt worksDebt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(works).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("30000")).build();

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house, main, works));
        when(accessResolver.sharesFor(any(), eq(1L)))
            .thenReturn(Map.of(10L, FULL, 20L, FULL, 21L, FULL));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("400000"));
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.empty());
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of(mainDebt, worksDebt));
        when(loanAmortizationService.computeRemainingBalance(eq(mainDebt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("150000"));
        when(loanAmortizationService.computeRemainingBalance(eq(worksDebt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("20000"));

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.outstandingDebt()).isEqualByComparingTo("170000");
        assertThat(result.properties()).singleElement()
            .satisfies(line -> assertThat(line.loans()).hasSize(2));
    }

    @Test
    void summarize_loanTheViewerHoldsNoShareOf_isExcluded() {
        Account house = property(10L, "400000");
        Account bobsLoan = loanAccount(20L, BOB);
        Debt debt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(bobsLoan).linkedAccount(house).member(BOB)
            .borrowedAmount(new BigDecimal("200000")).build();

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house));
        // Bob's personal loan reduces the household's equity but not Alice's.
        when(accessResolver.sharesFor(any(), eq(1L)))
            .thenReturn(Map.of(10L, FULL, bobsLoan.getId(), BigDecimal.ZERO));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("400000"));
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.empty());
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of(debt));

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.outstandingDebt()).isEqualByComparingTo("0");
        assertThat(result.netValue()).isEqualByComparingTo("400000");
        assertThat(result.properties()).singleElement()
            .satisfies(line -> assertThat(line.loans()).isEmpty());
    }

    @Test
    void summarize_underwaterProperty_reportsNegativeEquity() {
        Account house = property(10L, "150000");
        Account loan = loanAccount(20L, ALICE);
        Debt debt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(loan).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("200000")).build();

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house, loan));
        when(accessResolver.sharesFor(any(), eq(1L))).thenReturn(Map.of(10L, FULL, 20L, FULL));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("150000"));
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.empty());
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of(debt));
        when(loanAmortizationService.computeRemainingBalance(eq(debt), any(LocalDate.class)))
            .thenReturn(new BigDecimal("180000"));

        RealEstateSummaryResponse result = service.summarize(1L);

        // Shown as-is: an owner whose mortgage exceeds the property's value needs to see that.
        assertThat(result.netValue()).isEqualByComparingTo("-30000");
        assertThat(result.loanToValue()).isEqualByComparingTo("120.00");
    }

    @Test
    void summarize_ignoresNonPropertyAccounts() {
        Account checking = Account.builder().id(30L).name("Compte").type(AccountType.CHECKING)
            .currency("EUR").currentBalance(new BigDecimal("5000")).color("#0ea5e9").member(ALICE).build();

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(checking));
        when(accessResolver.sharesFor(any(), eq(1L))).thenReturn(Map.of(30L, FULL));

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.properties()).isEmpty();
        assertThat(result.grossValue()).isEqualByComparingTo("0");
        // No property means no denominator; a ratio would be meaningless rather than zero.
        assertThat(result.loanToValue()).isNull();
        assertThat(result.unrealizedGainPercent()).isNull();
    }

    @Test
    void summarize_noProperties_returnsZeroedTotals() {
        when(accessResolver.readableAccounts(1L)).thenReturn(List.of());
        when(accessResolver.sharesFor(any(), eq(1L))).thenReturn(Map.of());

        RealEstateSummaryResponse result = service.summarize(1L);

        assertThat(result.grossValue()).isEqualByComparingTo("0");
        assertThat(result.netValue()).isEqualByComparingTo("0");
        assertThat(result.properties()).isEmpty();
    }

    @Test
    void summarize_resolvesLoanSharesInOneQueryPerProperty() {
        // loansFor runs per property, so a per-loan share lookup costs properties x loans.
        Account house = property(10L, "400000");
        Account main = loanAccount(20L, ALICE);
        Account works = loanAccount(21L, ALICE);
        Debt mainDebt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(main).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("200000")).build();
        Debt worksDebt = Debt.builder().startDate(java.time.LocalDate.of(2024, 1, 1)).endDate(java.time.LocalDate.of(2044, 1, 1)).account(works).linkedAccount(house).member(ALICE)
            .borrowedAmount(new BigDecimal("30000")).build();

        when(accessResolver.readableAccounts(1L)).thenReturn(List.of(house, main, works));
        when(accessResolver.sharesFor(any(), eq(1L)))
            .thenReturn(Map.of(10L, FULL, 20L, FULL, 21L, FULL));
        when(accountService.liveBalanceEur(house)).thenReturn(new BigDecimal("400000"));
        when(metadataRepository.findByAccountId(10L)).thenReturn(Optional.empty());
        when(debtRepository.findByLinkedAccountId(10L)).thenReturn(List.of(mainDebt, worksDebt));
        when(loanAmortizationService.computeRemainingBalance(any(Debt.class), any(LocalDate.class)))
            .thenReturn(new BigDecimal("100000"));

        service.summarize(1L);

        // One for the readable accounts, one for this property's loans -- not one per loan.
        verify(accessResolver, times(2)).sharesFor(any(), eq(1L));
        verify(accessResolver, never()).shareFor(any(), any());
    }
}
