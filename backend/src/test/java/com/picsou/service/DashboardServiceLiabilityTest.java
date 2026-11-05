package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceLiabilityTest {

    @Mock AccountRepository accountRepository;
    @Mock GoalService goalService;
    @Mock GoalRepository goalRepository;
    @Mock PriceService priceService;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock HistoryService historyService;
    @Mock DebtRepository debtRepository;
    @Mock LoanAmortizationService loanAmortizationService;
    @Mock AccountService accountService;
    @Mock AccountAccessResolver accessResolver;

    DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            accountRepository, goalService, goalRepository,
            priceService, holdingRepository, historyService,
            debtRepository, loanAmortizationService, accountService,
            accessResolver
        );
        // Fixtures own their accounts outright, so readableAccounts mirrors the repository
        // and every share is 100% -- weighting becomes the identity.
        lenient().when(accessResolver.readableAccounts(any())).thenAnswer(inv ->
            accountRepository.findAllByMemberIdOrderByCreatedAtAsc(inv.getArgument(0)));
        lenient().when(accessResolver.sharesFor(any(), any())).thenAnswer(inv -> {
            java.util.Collection<Account> accounts = inv.getArgument(0);
            java.util.Map<Long, java.math.BigDecimal> shares = new java.util.HashMap<>();
            for (Account a : accounts) {
                shares.put(a.getId(), new java.math.BigDecimal("100"));
            }
            return shares;
        });
    }

    @Test
    void liability_with_debt_row_gets_monthlyPayment_and_percentPaid() {
        FamilyMember member = new FamilyMember();
        member.setId(1L);

        Account loan = new Account();
        loan.setId(10L);
        loan.setName("Mortgage");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-80000"));
        loan.setCurrency("EUR");
        loan.setColor("#6366f1");

        Debt debt = new Debt();
        debt.setBorrowedAmount(new BigDecimal("100000"));
        debt.setMonthlyPayment(new BigDecimal("800"));
        debt.setInterestRate(new BigDecimal("0.015"));
        debt.setStartDate(LocalDate.of(2022, 1, 1));
        debt.setEndDate(LocalDate.of(2037, 1, 1));
        debt.setAccount(loan);

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(10L)).thenReturn(List.of());
        // Loans are valued through AccountService.liveBalanceEur (positive remaining balance).
        when(accountService.liveBalanceEur(loan)).thenReturn(new BigDecimal("80000"));
        when(debtRepository.findByAccountIdIn(List.of(10L))).thenReturn(List.of(debt));
        when(loanAmortizationService.resolveMonthlyPayment(debt)).thenReturn(new BigDecimal("800.00"));
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.monthlyPayment()).isEqualByComparingTo("800.00");
        assertThat(entry.percentPaid()).isNotNull();
        // borrowedAmount=100000, remaining=80000 → 20% paid
        assertThat(entry.percentPaid()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(result.totalMonthlyPayment()).isEqualByComparingTo("800.00");
    }

    @Test
    void liability_without_debt_row_gets_null_fields() {
        FamilyMember member = new FamilyMember();
        member.setId(1L);

        Account loan = new Account();
        loan.setId(11L);
        loan.setName("Finary loan");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-15000"));
        loan.setCurrency("EUR");
        loan.setColor("#f97316");

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(11L)).thenReturn(List.of());
        when(accountService.liveBalanceEur(loan)).thenReturn(new BigDecimal("15000"));
        when(debtRepository.findByAccountIdIn(List.of(11L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.monthlyPayment()).isNull();
        assertThat(entry.percentPaid()).isNull();
        assertThat(result.totalMonthlyPayment()).isNull();
    }

    @Test
    void liability_with_debt_missing_dates_returns_null_monthlyPayment() {
        Account loan = new Account();
        loan.setId(12L);
        loan.setName("Incomplete loan");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-5000"));
        loan.setCurrency("EUR");
        loan.setColor("#aabbcc");

        Debt debt = new Debt();
        debt.setAccount(loan);
        debt.setBorrowedAmount(new BigDecimal("5000"));
        // intentionally no startDate / endDate / monthlyPayment

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(12L)).thenReturn(List.of());
        when(accountService.liveBalanceEur(loan)).thenReturn(new BigDecimal("5000"));
        when(debtRepository.findByAccountIdIn(List.of(12L))).thenReturn(List.of(debt));
        when(loanAmortizationService.resolveMonthlyPayment(debt)).thenReturn(null);
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        assertThat(result.liabilities().get(0).monthlyPayment()).isNull();
        assertThat(result.totalMonthlyPayment()).isNull();
    }

    @Test
    void credit_card_liability_carries_payment_due_amount_and_date() {
        Account card = new Account();
        card.setId(30L);
        card.setName("Card");
        card.setType(AccountType.CREDIT_CARD);
        card.setCurrentBalance(new BigDecimal("-2254.90"));
        card.setCurrency("EUR");
        card.setColor("#0ea5e9");
        card.setPaymentDueAmount(new BigDecimal("912.40"));
        card.setPaymentDueDate(LocalDate.of(2026, 11, 5));

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(card));
        when(holdingRepository.findByAccount_Id(30L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(30L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.balanceEur()).isEqualByComparingTo("2254.90");
        assertThat(entry.paymentDueAmountEur()).isEqualByComparingTo("912.40");
        assertThat(entry.paymentDueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(entry.monthlyPayment()).isNull();
        assertThat(entry.percentPaid()).isNull();
    }

    @Test
    void deferred_debit_card_without_a_parent_link_still_counts_as_a_liability() {
        // Caisse d'Epargne cards never carry parentAccountId: the pocket rule would hide them.
        Account card = new Account();
        card.setId(33L);
        card.setName("Deferred card");
        card.setType(AccountType.CREDIT_CARD);
        card.setCardNature(CardNature.DEFERRED_DEBIT);
        card.setCurrentBalance(new BigDecimal("-87.10"));
        card.setCurrency("EUR");
        card.setColor("#0ea5e9");
        card.setPaymentDueAmount(new BigDecimal("87.10"));
        card.setPaymentDueDate(LocalDate.of(2026, 10, 31));

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(card));
        when(holdingRepository.findByAccount_Id(33L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(33L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        assertThat(result.liabilities().get(0).balanceEur()).isEqualByComparingTo("87.10");
        assertThat(result.liabilities().get(0).paymentDueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void credit_card_without_statement_leaves_payment_due_fields_null() {
        Account card = new Account();
        card.setId(31L);
        card.setName("Card");
        card.setType(AccountType.CREDIT_CARD);
        card.setCurrentBalance(new BigDecimal("-100"));
        card.setCurrency("EUR");
        card.setColor("#0ea5e9");

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(card));
        when(holdingRepository.findByAccount_Id(31L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(31L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse.LiabilityEntry entry = dashboardService.getDashboard(1L, null).liabilities().get(0);

        assertThat(entry.paymentDueAmountEur()).isNull();
        assertThat(entry.paymentDueDate()).isNull();
    }

    @Test
    void loan_liability_never_carries_payment_due_fields() {
        Account loan = new Account();
        loan.setId(32L);
        loan.setName("Loan");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-5000"));
        loan.setCurrency("EUR");
        loan.setColor("#aabbcc");
        loan.setPaymentDueAmount(new BigDecimal("300"));
        loan.setPaymentDueDate(LocalDate.of(2026, 11, 5));

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(32L)).thenReturn(List.of());
        when(accountService.liveBalanceEur(loan)).thenReturn(new BigDecimal("5000"));
        when(debtRepository.findByAccountIdIn(List.of(32L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse.LiabilityEntry entry = dashboardService.getDashboard(1L, null).liabilities().get(0);

        assertThat(entry.paymentDueAmountEur()).isNull();
        assertThat(entry.paymentDueDate()).isNull();
    }

    /**
     * Regression guard for the history double-count fix: a pocket sub-account's balance is already
     * folded into its parent wallet (see the skip a few lines above in getDashboard/buildDistribution),
     * so it must not also be counted a second time via HistoryService -- which sums whatever account
     * ids it's given with no parentAccountId filtering of its own.
     */
    @Test
    void dashboard_excludesPocketChildAccounts_fromHistoryAccountIds() {
        Account wallet = new Account();
        wallet.setId(20L);
        wallet.setName("Revolut EUR");
        wallet.setType(AccountType.CHECKING);
        wallet.setCurrentBalance(new BigDecimal("500"));
        wallet.setCurrency("EUR");
        wallet.setColor("#6366f1");

        Account pocket = new Account();
        pocket.setId(21L);
        pocket.setName("Pocket");
        pocket.setType(AccountType.CHECKING);
        pocket.setCurrentBalance(new BigDecimal("50"));
        pocket.setCurrency("EUR");
        pocket.setColor("#6366f1");
        pocket.setParentAccountId(20L);

        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(wallet, pocket));
        when(holdingRepository.findByAccount_Id(20L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(21L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of())).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        dashboardService.getDashboard(1L, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(historyService).buildHistory(idsCaptor.capture(), any(Integer.class), any());
        assertThat(idsCaptor.getValue()).containsExactly(20L);
    }
}
