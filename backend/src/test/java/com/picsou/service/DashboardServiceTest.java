package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

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

    @InjectMocks DashboardService dashboardService;

    @BeforeEach
    void stubOwnershipShares() {
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
    void getDashboard_usesSharedAccountValuation_whenHoldingHasNoLivePrice() {
        Account account = holdingAccount();
        AccountHolding holding = AccountHolding.builder()
            .account(account)
            .ticker("PHYMF")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            .currentPrice(new BigDecimal("999"))
            .build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(account));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        // Unpriced on both sides: valuation() drops the holding from the value *and* the cost
        // basis, which is the whole point -- keeping its 1000 EUR cost while its value is gone
        // is what booked an untouched account as a loss.
        when(accountService.valuation(account))
            .thenReturn(new AccountService.Valuation(
                new BigDecimal("5000"), new BigDecimal("5000"), false, true, false));
        when(historyService.buildHistory(List.of(1L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        assertThat(response.totalNetWorth()).isEqualByComparingTo("5000");
        assertThat(response.distribution()).hasSize(1);
        assertThat(response.distribution().getFirst().balanceEur()).isEqualByComparingTo("5000");
        // The dashboard must take both figures from one pass, not pair liveBalanceEur with its
        // own cost-basis loop -- see docs/features/price-service.md.
        verify(accountService).valuation(account);
    }

    @Test
    void getDashboard_reusesSameHoldingAccountValue_forTotalAndDistribution() {
        Account account = holdingAccount();
        AccountHolding holding = AccountHolding.builder()
            .account(account)
            .ticker("AAPL")
            .quantity(new BigDecimal("10"))
            .averageBuyIn(new BigDecimal("100"))
            .currentPrice(new BigDecimal("999"))
            .build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(account));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        when(accountService.valuation(account))
            .thenReturn(new AccountService.Valuation(
                new BigDecimal("2000"), new BigDecimal("1000"), true, true, false));
        when(historyService.buildHistory(List.of(1L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        assertThat(response.totalNetWorth()).isEqualByComparingTo("2000");
        assertThat(response.distribution()).hasSize(1);
        assertThat(response.distribution().getFirst().balanceEur()).isEqualByComparingTo("2000");
    }

    // ─── Liabilities path characterization ────────────────────────────────────

    @Test
    void getDashboard_loan_flowsToLiabilitiesNotDistribution() {
        stubLoanAndCashFixture();

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        // Loan balance lands in totalLiabilities, never totalAssets.
        assertThat(response.totalLiabilities()).isEqualByComparingTo("10000");
        // netWorth = assets 2000 − liabilities 10000.
        assertThat(response.totalNetWorth()).isEqualByComparingTo("-8000");
        // The loan appears only in the liabilities list, the cash account only in distribution.
        assertThat(response.liabilities())
            .extracting(DashboardResponse.LiabilityEntry::accountId)
            .containsExactly(10L);
        assertThat(response.distribution())
            .extracting(DashboardResponse.DistributionItem::accountId)
            .containsExactly(1L);
    }

    @Test
    void getDashboard_distributionPercentages_divideByOwnSideTotals() {
        stubLoanAndCashFixture();

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        // CHARACTERIZATION (updated by plan 005): assets divide by totalAssets and
        // liabilities by totalLiabilities, so percentages stay meaningful even when
        // net worth is negative (-8000 here) and can never exceed 100 %.
        assertThat(response.distribution().getFirst().percentage()).isEqualTo(100.0); // 2000 / 2000
        assertThat(response.liabilities().getFirst().percentage()).isEqualTo(100.0);  // 10000 / 10000
    }

    @Test
    void getDashboard_assetPercentages_shareOfTotalAssets_withDebtPresent() {
        Account cashAcc = cashAccount();               // id 1, CHECKING 2000
        Account savingsAcc = Account.builder()
            .id(2L)
            .name("Livret")
            .type(AccountType.SAVINGS)
            .currency("EUR")
            .currentBalance(new BigDecimal("6000"))
            .color("#22c55e")
            .build();
        Account loanAcc = loanAccount();               // id 10, LOAN 10000
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L))
            .thenReturn(List.of(cashAcc, savingsAcc, loanAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(10L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2000"), "EUR", null)).thenReturn(new BigDecimal("2000"));
        when(priceService.toEur(new BigDecimal("6000"), "EUR", null)).thenReturn(new BigDecimal("6000"));
        when(accountService.liveBalanceEur(loanAcc)).thenReturn(new BigDecimal("10000"));
        when(historyService.buildHistory(List.of(1L, 2L, 10L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        // Assets split 2000 / 6000 of totalAssets 8000 → 25 % / 75 %, regardless of the
        // 10000 debt; the loan is 100 % of totalLiabilities.
        assertThat(response.distribution())
            .extracting(DashboardResponse.DistributionItem::percentage)
            .containsExactly(25.0, 75.0);
        assertThat(response.liabilities().getFirst().percentage()).isEqualTo(100.0);
    }

    @Test
    void getDashboard_loanValuation_usesAmortizedLiveBalance_notStoredBalance() {
        Account loanAcc = loanAccount();               // stored balance 10000
        Account cashAcc = cashAccount();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(loanAcc, cashAcc));
        when(holdingRepository.findByAccount_Id(10L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        // Amortization has progressed: remaining capital 9500 < stored 10000.
        when(accountService.liveBalanceEur(loanAcc)).thenReturn(new BigDecimal("9500"));
        when(priceService.toEur(new BigDecimal("2000"), "EUR", null)).thenReturn(new BigDecimal("2000"));
        when(historyService.buildHistory(List.of(10L, 1L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        // Hero totals and the liability row both use the amortized value, matching
        // what HistoryService's live point reports for the same loan.
        assertThat(response.totalLiabilities()).isEqualByComparingTo("9500");
        assertThat(response.totalNetWorth()).isEqualByComparingTo("-7500");
        assertThat(response.liabilities().getFirst().balanceEur()).isEqualByComparingTo("9500");
    }

    @Test
    void getDashboard_creditCardDebt_isALiabilityNotANegativeAsset() {
        Account cashAcc = cashAccount();               // id 1, CHECKING 2000
        Account cardAcc = Account.builder()
            .id(11L)
            .name("American Express")
            .type(AccountType.CREDIT_CARD)
            .currency("EUR")
            .currentBalance(new BigDecimal("-500"))
            .color("#2563eb")
            .build();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(cashAcc, cardAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(11L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2000"), "EUR", null)).thenReturn(new BigDecimal("2000"));
        when(priceService.toEur(new BigDecimal("-500"), "EUR", null)).thenReturn(new BigDecimal("-500"));
        when(historyService.buildHistory(List.of(1L, 11L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        DashboardResponse response = dashboardService.getDashboard(42L, "1Y");

        assertThat(response.distribution())
            .extracting(DashboardResponse.DistributionItem::accountId, DashboardResponse.DistributionItem::percentage)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 100.0));
        assertThat(response.totalLiabilities()).isEqualByComparingTo("500");
        assertThat(response.totalNetWorth()).isEqualByComparingTo("1500");
        assertThat(response.liabilities()).singleElement().satisfies(entry -> {
            assertThat(entry.accountId()).isEqualTo(11L);
            assertThat(entry.balanceEur()).isEqualByComparingTo("500");
            assertThat(entry.percentage()).isEqualTo(100.0);
        });
    }

    @Test
    void getDashboard_rangeSwitch_mapsMonths() {
        Account cashAcc = cashAccount();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(cashAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        when(priceService.toEur(new BigDecimal("2000"), "EUR", null)).thenReturn(new BigDecimal("2000"));
        when(historyService.buildHistory(List.of(1L), 3, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());

        dashboardService.getDashboard(42L, "3M");

        // Range "3M" maps to a 3-month history window.
        verify(historyService).buildHistory(List.of(1L), 3, 42L);
    }

    /** LOAN 10000 EUR (id 10) + CHECKING 2000 EUR (id 1), no holdings, toEur pass-through. */
    private void stubLoanAndCashFixture() {
        Account loanAcc = loanAccount();
        Account cashAcc = cashAccount();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(loanAcc, cashAcc));
        when(holdingRepository.findByAccount_Id(10L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        // Loans are valued through AccountService.liveBalanceEur (amortized when a Debt
        // row exists), not through the raw stored-balance conversion.
        when(accountService.liveBalanceEur(loanAcc)).thenReturn(new BigDecimal("10000"));
        when(priceService.toEur(new BigDecimal("2000"), "EUR", null)).thenReturn(new BigDecimal("2000"));
        when(historyService.buildHistory(List.of(10L, 1L), 12, 42L)).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(42L)).thenReturn(List.of());
    }

    private Account loanAccount() {
        return Account.builder()
            .id(10L)
            .name("Mortgage")
            .type(AccountType.LOAN)
            .currency("EUR")
            .currentBalance(new BigDecimal("10000"))
            .color("#ef4444")
            .build();
    }

    private Account cashAccount() {
        return Account.builder()
            .id(1L)
            .name("Checking")
            .type(AccountType.CHECKING)
            .currency("EUR")
            .currentBalance(new BigDecimal("2000"))
            .color("#3b82f6")
            .build();
    }

    private Account holdingAccount() {
        return Account.builder()
            .id(1L)
            .name("Brokerage")
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .currentBalance(new BigDecimal("5000"))
            .color("#6366f1")
            .build();
    }
}
