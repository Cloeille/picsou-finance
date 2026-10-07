package com.picsou.service.budget;

import com.picsou.dto.AllocationResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.AssetClass;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock BudgetSettingsService budgetSettingsService;

    @InjectMocks AllocationService service;

    private static final Long MEMBER_ID = 4L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static Account account(Long id, String name, AccountType type, String balance) {
        return Account.builder()
            .id(id).name(name).type(type)
            .currentBalance(new BigDecimal(balance)).color("#000000").build();
    }

    private static Transaction transfer(Account account, String amount) {
        Category cat = Category.builder().id(9L).kind(CategoryKind.TRANSFER).name("Transfer").build();
        return Transaction.builder()
            .id(account.getId() * 100 + amount.hashCode())
            .date(LocalDate.of(2026, 6, 5))
            .amount(new BigDecimal(amount))
            .account(account)
            .categoryRef(cat)
            .description("x")
            .build();
    }

    @Test
    void compute_stockGroupsByAssetClass_excludesOther() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(
            account(1L, "Compte courant", AccountType.CHECKING, "1000.00"),
            account(2L, "Livret A", AccountType.SAVINGS, "5000.00"),
            account(3L, "LEP", AccountType.LEP, "2000.00"),
            account(4L, "PEA", AccountType.PEA, "2000.00"),
            account(5L, "Maison", AccountType.REAL_ESTATE, "300000.00") // excluded
        ));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        // 1000 current + 7000 savings + 2000 investment = 10000 (real estate excluded)
        assertThat(r.totalStock()).isEqualByComparingTo("10000.00");
        assertThat(r.stock()).hasSize(3);

        AllocationResponse.AllocationStock savings = r.stock().stream()
            .filter(s -> s.assetClass() == AssetClass.SAVINGS).findFirst().orElseThrow();
        assertThat(savings.amount()).isEqualByComparingTo("7000.00");
        assertThat(savings.percent()).isEqualByComparingTo("70.00");
    }

    @Test
    void compute_queriesHiddenExcludingRepositoryMethod() {
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of(
            account(1L, "Compte courant", AccountType.CHECKING, "1000.00")
        ));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        verify(accountRepository).findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID);
        verify(accountRepository, never()).findAllByMemberIdOrderByCreatedAtAsc(any());
    }

    @Test
    void compute_cycle_withPastAnchor_returnsPastCycleRange() {
        LocalDate anchor = LocalDate.of(2024, 3, 10);
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, anchor);

        assertThat(r.from()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    void compute_ytd_withPastYearEnd_returnsFullPastYear() {
        LocalDate anchor = LocalDate.of(2024, 12, 31);
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.YTD, anchor);

        assertThat(r.from()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2024, 12, 31));
    }

    @Test
    void compute_ytd_withCurrentYearAnchor_returnsFromJan1ToAnchor() {
        LocalDate anchor = LocalDate.of(2026, 6, 15);
        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID)).thenReturn(List.of());
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.YTD, anchor);

        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void compute_pocketInflow_notCountedAsContribution() {
        // A Revolut pocket is CHECKING → AssetClass.CURRENT → tracksContributions() = false.
        // Mirror credit legs (positive, TRANSFER) into a pocket must NOT appear in contributions.
        Account wallet = account(1L, "Revolut Wallet", AccountType.CHECKING, "5000.00");
        Account pocket = account(10L, "Pocket ••89abfe", AccountType.CHECKING, "666.00");

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(wallet, pocket));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(
                eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of(
                transfer(pocket, "666.00") // pocket inflow — CURRENT, must not be counted
            ));

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        // CURRENT accounts do not track contributions
        assertThat(r.totalContributions()).isEqualByComparingTo("0.00");
        assertThat(r.contributions()).isEmpty();
    }

    @Test
    void compute_contributions_sumIncomingTransfersIntoSavingsAndInvestment() {
        Account livret = account(2L, "Livret A", AccountType.SAVINGS, "5000.00");
        Account pea = account(4L, "PEA", AccountType.PEA, "2000.00");
        Account checking = account(1L, "Compte courant", AccountType.CHECKING, "1000.00");

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(checking, livret, pea));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of(
                transfer(livret, "300.00"),   // into savings (counts)
                transfer(livret, "200.00"),   // into savings again (sums)
                transfer(pea, "150.00"),      // into investment (counts)
                transfer(checking, "500.00"), // into current (ignored — not tracked)
                transfer(livret, "-500.00")   // outgoing (ignored — not a contribution)
            ));

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.YTD, TODAY);

        assertThat(r.totalContributions()).isEqualByComparingTo("650.00");
        assertThat(r.contributions()).hasSize(2);
        // Sorted by amount desc → Livret (500) first, then PEA (150).
        assertThat(r.contributions().get(0).accountName()).isEqualTo("Livret A");
        assertThat(r.contributions().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(r.contributions().get(1).accountName()).isEqualTo("PEA");
        assertThat(r.contributions().get(1).amount()).isEqualByComparingTo("150.00");
    }
}
