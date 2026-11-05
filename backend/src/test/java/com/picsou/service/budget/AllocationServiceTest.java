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
import com.picsou.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock BudgetSettingsService budgetSettingsService;
    @Mock AccountService accountService;

    @InjectMocks AllocationService service;

    /** EUR accounts without holdings: the live EUR value is the stored balance. */
    @BeforeEach
    void eurAccountsAreWorthTheirBalance() {
        lenient().when(accountService.liveBalanceEur(any()))
            .thenAnswer(inv -> inv.<Account>getArgument(0).getCurrentBalance());
    }

    private static final Long MEMBER_ID = 4L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static Account account(Long id, String name, AccountType type, String balance) {
        return Account.builder()
            .id(id).name(name).type(type)
            .currentBalance(new BigDecimal(balance)).color("#000000").build();
    }

    private static BigDecimal amountOf(AllocationResponse r, AssetClass assetClass) {
        return r.stock().stream()
            .filter(s -> s.assetClass() == assetClass)
            .map(AllocationResponse.AllocationStock::amount)
            .findFirst().orElse(BigDecimal.ZERO);
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
    void compute_stockIsValuedInEur_notInTheAccountsOwnUnit() {
        // A USD current account and a crypto account whose balance is a coin quantity: the
        // stock must use the EUR value the dashboard shows, not the stored number.
        Account usd = account(1L, "Wise USD", AccountType.CHECKING, "1000.00");
        usd.setCurrency("USD");
        Account btc = account(2L, "Bitcoin", AccountType.CRYPTO, "0.50000000");
        btc.setTicker("BTC");
        Account livret = account(3L, "Livret A", AccountType.LIVRET_A, "5000.00");

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(usd, btc, livret));
        when(accountService.liveBalanceEur(usd)).thenReturn(new BigDecimal("920.00"));
        when(accountService.liveBalanceEur(btc)).thenReturn(new BigDecimal("40000.00"));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("45920.00");
        AllocationResponse.AllocationStock current = r.stock().stream()
            .filter(s -> s.assetClass() == AssetClass.CURRENT).findFirst().orElseThrow();
        assertThat(current.amount()).isEqualByComparingTo("920.00");
        AllocationResponse.AllocationStock investment = r.stock().stream()
            .filter(s -> s.assetClass() == AssetClass.INVESTMENT).findFirst().orElseThrow();
        assertThat(investment.amount()).isEqualByComparingTo("40000.00");
    }

    @Test
    void compute_stockCountsPocketsOnlyOnce() {
        Account wallet = account(1L, "Revolut Wallet", AccountType.CHECKING, "5000.00");
        Account pocket = account(10L, "Pocket", AccountType.CHECKING, "666.00");
        pocket.setParentAccountId(1L);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(wallet, pocket));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("5000.00");
    }

    @Test
    void compute_moneyBoxCountsAsSavings_andLeavesItsWalletTotal() {
        // Revolut: the wallet's balance is the sum of its children, so a money-box is both inside
        // the wallet (CURRENT) and its own SAVINGS account. It belongs to SAVINGS only.
        Account wallet = account(1L, "Revolut Wallet", AccountType.CHECKING, "5000.00");
        Account moneyBox = account(11L, "Money-box", AccountType.SAVINGS, "1200.00");
        moneyBox.setParentAccountId(1L);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(wallet, moneyBox));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("5000.00");
        AllocationResponse.AllocationStock current = r.stock().stream()
            .filter(s -> s.assetClass() == AssetClass.CURRENT).findFirst().orElseThrow();
        assertThat(current.amount()).isEqualByComparingTo("3800.00");
        AllocationResponse.AllocationStock savings = r.stock().stream()
            .filter(s -> s.assetClass() == AssetClass.SAVINGS).findFirst().orElseThrow();
        assertThat(savings.amount()).isEqualByComparingTo("1200.00");
    }

    @Test
    void compute_subAccountAboveItsWallet_movesAtMostTheWalletBalance() {
        // An unselected money-box keeps its last balance while the wallet is refreshed, so the
        // sub-accounts can add up to more than the wallet. The total stays the wallet's and no
        // class goes below zero.
        Account wallet = account(1L, "Revolut Wallet", AccountType.CHECKING, "1000.00");
        Account moneyBox = account(11L, "Money-box", AccountType.SAVINGS, "800.00");
        moneyBox.setParentAccountId(1L);
        Account staleBox = account(12L, "Old money-box", AccountType.SAVINGS, "700.00");
        staleBox.setParentAccountId(1L);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(wallet, moneyBox, staleBox));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("1000.00");
        assertThat(r.stock()).allMatch(s -> s.amount().signum() >= 0);
        AllocationResponse.AllocationStock savings = r.stock().stream()
            .filter(s -> s.assetClass() == AssetClass.SAVINGS).findFirst().orElseThrow();
        assertThat(savings.amount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void compute_subAccountWithMissingParent_isSkipped() {
        Account moneyBox = account(11L, "Money-box", AccountType.SAVINGS, "1200.00");
        moneyBox.setParentAccountId(99L);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(moneyBox));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("0");
        assertThat(r.stock()).noneMatch(s -> s.assetClass() == AssetClass.SAVINGS);
    }

    @Test
    void compute_skippedSubAccount_isNeverValued() {
        // A sub-account that does not contribute must not be valued: its failing price or
        // snapshot lookup would otherwise break the whole view.
        Account orphan = account(11L, "Money-box", AccountType.SAVINGS, "1200.00");
        orphan.setParentAccountId(99L);
        Account house = account(5L, "House", AccountType.REAL_ESTATE, "300000.00");
        Account underHouse = account(12L, "Money-box", AccountType.SAVINGS, "400.00");
        underHouse.setParentAccountId(5L);
        lenient().when(accountService.liveBalanceEur(orphan)).thenThrow(new RuntimeException("no price"));
        lenient().when(accountService.liveBalanceEur(underHouse)).thenThrow(new RuntimeException("no price"));

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(orphan, house, underHouse));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("0");
        verify(accountService, never()).liveBalanceEur(orphan);
        verify(accountService, never()).liveBalanceEur(underHouse);
    }

    @Test
    void compute_negativeSubAccount_doesNotRaiseTheWalletCeiling() {
        // A negative sub-account moves nothing (its negative is already in the wallet's
        // balance), so it cannot make room for a later money-box to take more than the wallet.
        Account wallet = account(1L, "Wallet", AccountType.CHECKING, "1000.00");
        Account overdrawn = account(10L, "Pocket", AccountType.SAVINGS, "-300.00");
        overdrawn.setParentAccountId(1L);
        Account moneyBox = account(11L, "Money-box", AccountType.SAVINGS, "1200.00");
        moneyBox.setParentAccountId(1L);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(wallet, overdrawn, moneyBox));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("1000.00");
        assertThat(r.stock()).allMatch(s -> s.amount().signum() >= 0);
        assertThat(amountOf(r, AssetClass.CURRENT)).isEqualByComparingTo("0");
        assertThat(amountOf(r, AssetClass.SAVINGS)).isEqualByComparingTo("1000.00");
        verify(accountService).liveBalanceEur(wallet);
    }

    @Test
    void compute_sameClassPocket_doesNotConsumeTheWallet() {
        // A pocket in the wallet's own class is already inside the wallet's CURRENT amount:
        // moving it would change no class total but use up what a money-box can take.
        Account wallet = account(1L, "Wallet", AccountType.CHECKING, "1000.00");
        Account pocket = account(10L, "Pocket", AccountType.CHECKING, "1000.00");
        pocket.setParentAccountId(1L);
        Account moneyBox = account(11L, "Money-box", AccountType.SAVINGS, "400.00");
        moneyBox.setParentAccountId(1L);

        when(budgetSettingsService.cycleStartDay(MEMBER_ID)).thenReturn(1);
        when(accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(MEMBER_ID))
            .thenReturn(List.of(wallet, pocket, moneyBox));
        when(transactionRepository.findByMemberIdAndKindAndDateBetween(eq(MEMBER_ID), eq(CategoryKind.TRANSFER), any(), any()))
            .thenReturn(List.of());

        AllocationResponse r = service.compute(MEMBER_ID, CashflowPeriod.CYCLE, TODAY);

        assertThat(r.totalStock()).isEqualByComparingTo("1000.00");
        assertThat(amountOf(r, AssetClass.CURRENT)).isEqualByComparingTo("600.00");
        assertThat(amountOf(r, AssetClass.SAVINGS)).isEqualByComparingTo("400.00");
        verify(accountService, never()).liveBalanceEur(pocket);
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
