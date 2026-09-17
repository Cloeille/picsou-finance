package com.picsou.service;

import com.picsou.dto.DashboardResponse.NetWorthIntradayPoint;
import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.dto.PnlResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.PriceSnapshot;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.PriceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock BalanceSnapshotRepository snapshotRepository;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock PriceService priceService;
    @Mock PriceSnapshotRepository priceSnapshotRepository;
    @Mock AccountService accountService;

    @Mock AccountAccessResolver accessResolver;

    @InjectMocks HistoryService historyService;

    @BeforeEach
    void stubOwnershipShares() {
        // Every fixture account is wholly owned, so each resolves to 100%. Weighting is then
        // the identity, which keeps these tests measuring what they were written to measure.
        lenient().when(accessResolver.sharesFor(any(), any())).thenAnswer(inv -> {
            java.util.Collection<Account> accounts = inv.getArgument(0);
            Long viewer = inv.getArgument(1);
            java.util.Map<Long, java.math.BigDecimal> shares = new java.util.HashMap<>();
            for (Account a : accounts) {
                // Mirrors the real resolver: no split rows, so the owner holds everything and
                // anyone else holds nothing. A zero share is what makes a foreign account 404.
                boolean owns = a.getMember() != null && a.getMember().getId().equals(viewer);
                shares.put(a.getId(), owns ? new java.math.BigDecimal("100") : java.math.BigDecimal.ZERO);
            }
            return shares;
        });
    }

    private static final long MEMBER_ID = 99L;
    private static final FamilyMember MEMBER = FamilyMember.builder().id(MEMBER_ID).build();

    private static Account brokerage(long id, String name) {
        return Account.builder()
            .id(id)
            .name(name)
            .type(AccountType.COMPTE_TITRES)
            .currency("EUR")
            .currentBalance(new BigDecimal("0"))
            .color("#6366f1")
            .member(MEMBER)
            .build();
    }

    /**
     * The live point takes its value and its cost basis from one {@code valuation(account)} call,
     * so both are stubbed together — which is the point: two separate lookups could straddle a
     * price change and print a P&L computed over two different sets of assets.
     */
    private void stubValuation(Account account, String live, String invested) {
        when(accountService.valuation(account)).thenReturn(new AccountService.Valuation(
            new BigDecimal(live), new BigDecimal(invested), true, true, false));
        // The intraday path still asks for the value alone; stubbed here so a test states what an
        // account is worth once, whichever accessor the production code reaches for.
        lenient().when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal(live));
    }

    /** {@link #stubValuation} for accounts a given test may not end up valuing. */
    private void stubValuationLenient(Account account, String live, String invested) {
        lenient().when(accountService.valuation(account)).thenReturn(new AccountService.Valuation(
            new BigDecimal(live), new BigDecimal(invested), true, true, false));
        lenient().when(accountService.liveBalanceEur(account)).thenReturn(new BigDecimal(live));
    }

    @Test
    void buildHistory_invested_readsSnapshotPerDate() {
        LocalDate today = LocalDate.now();
        Account account = brokerage(1L, "CT");

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(account));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L))))
            .thenReturn(List.of(
                new Object[]{1L, today.minusDays(10), new BigDecimal("5000"), new BigDecimal("4500")},
                new Object[]{1L, today.minusDays(5),  new BigDecimal("5500"), new BigDecimal("5000")},
                new Object[]{1L, today.minusDays(1),  new BigDecimal("6200"), new BigDecimal("5400")}
            ));
        stubValuation(account, "6200", "5400");

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L), 1, false, MEMBER_ID);

        // Three historical points + today's appended live point.
        assertThat(result).hasSize(4);

        assertThat(result.get(0).date()).isEqualTo(today.minusDays(10));
        assertThat(result.get(0).invested()).isEqualByComparingTo("4500");

        assertThat(result.get(1).date()).isEqualTo(today.minusDays(5));
        assertThat(result.get(1).invested()).isEqualByComparingTo("5000");

        assertThat(result.get(2).date()).isEqualTo(today.minusDays(1));
        assertThat(result.get(2).invested()).isEqualByComparingTo("5400");

        // Distinct values — proves we read row[3] per row, not a single constant.
    }

    @Test
    void buildHistory_todayPoint_usesLiveCalculation_notSnapshot() {
        LocalDate today = LocalDate.now();
        Account account = brokerage(1L, "CT");

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(account));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L))))
            .thenReturn(List.<Object[]>of(
                // Stale snapshot for today: balance and invested both behind reality.
                new Object[]{1L, today, new BigDecimal("5000"), new BigDecimal("4500")}
            ));
        stubValuation(account, "5100", "4800");

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L), 1, false, MEMBER_ID);

        NetWorthPoint todayPoint = result.get(result.size() - 1);
        assertThat(todayPoint.date()).isEqualTo(today);
        assertThat(todayPoint.total()).isEqualByComparingTo("5100");
        assertThat(todayPoint.invested()).isEqualByComparingTo("4800");
    }

    @Test
    void buildHistory_loan_contributesZeroToInvested_negativeToTotal() {
        LocalDate today = LocalDate.now();
        LocalDate date = today.minusDays(2);

        Account loan = Account.builder()
            .id(1L).name("Loan").type(AccountType.LOAN).currency("EUR")
            .currentBalance(new BigDecimal("10000")).color("#ef4444").member(MEMBER).build();
        Account checking = Account.builder()
            .id(2L).name("Checking").type(AccountType.CHECKING).currency("EUR")
            .currentBalance(new BigDecimal("2000")).color("#3b82f6").member(MEMBER).build();

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(loan, checking));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L, 2L))))
            .thenReturn(List.of(
                new Object[]{1L, date, new BigDecimal("10000"), new BigDecimal("10000")},
                new Object[]{2L, date, new BigDecimal("2000"),  new BigDecimal("2000")}
            ));
        stubValuationLenient(loan, "10000", "10000");
        stubValuationLenient(checking, "2000", "2000");

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L, 2L), 1, true, MEMBER_ID);

        NetWorthPoint point = result.stream()
            .filter(p -> p.date().equals(date))
            .findFirst()
            .orElseThrow();

        // total: checking +2000, loan -10000 → -8000.
        assertThat(point.total()).isEqualByComparingTo("-8000");
        // invested: loan contributes 0, checking contributes 2000.
        assertThat(point.invested()).isEqualByComparingTo("2000");
        // Per-account split: loan invested is ZERO regardless of its snapshot column.
        assertThat(point.accounts().get(1L).invested()).isEqualByComparingTo("0");
        assertThat(point.accounts().get(2L).invested()).isEqualByComparingTo("2000");
        // Debt-neutral pnl (plan 005): the loan contributes 0 to pnl — outstanding
        // debt is no longer read as an investment loss.
        assertThat(point.pnl()).isEqualByComparingTo("0");
        assertThat(point.accounts().get(1L).pnl()).isEqualByComparingTo("0");
        assertThat(point.accounts().get(2L).pnl()).isEqualByComparingTo("0");
    }

    @Test
    void buildHistory_forwardFill_carriesLastInvestedAcrossGap() {
        LocalDate today = LocalDate.now();
        Account brokerage = brokerage(1L, "CT");
        Account checking = Account.builder()
            .id(2L).name("Checking").type(AccountType.CHECKING).currency("EUR")
            .currentBalance(new BigDecimal("1000")).color("#3b82f6").member(MEMBER).build();

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(brokerage, checking));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L, 2L))))
            .thenReturn(List.of(
                // Brokerage: snapshots at D-7 and D-3, gap between.
                new Object[]{1L, today.minusDays(7), new BigDecimal("3000"), new BigDecimal("3000")},
                new Object[]{1L, today.minusDays(3), new BigDecimal("3200"), new BigDecimal("3200")},
                // Checking: snapshot at D-5 (inside the brokerage gap) — injects this date into ffData.dates.
                new Object[]{2L, today.minusDays(5), new BigDecimal("1000"), new BigDecimal("1000")}
            ));
        stubValuationLenient(brokerage, "3200", "3200");
        stubValuationLenient(checking, "1000", "1000");

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L, 2L), 1, false, MEMBER_ID);

        // At D-5: brokerage forward-fills from D-7 (invested=3000), checking has its own row (1000).
        NetWorthPoint atD5 = result.stream()
            .filter(p -> p.date().equals(today.minusDays(5)))
            .findFirst()
            .orElseThrow();
        assertThat(atD5.invested()).isEqualByComparingTo("4000");
    }

    @Test
    void buildHistory_split_perAccountInvested_matchesAggregate() {
        LocalDate today = LocalDate.now();
        LocalDate date = today.minusDays(2);

        Account acc1 = brokerage(1L, "CT1");
        Account acc2 = brokerage(2L, "CT2");

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(acc1, acc2));
        when(snapshotRepository.findForwardFillDataByAccountIds(any(LocalDate.class), eq(List.of(1L, 2L))))
            .thenReturn(List.of(
                new Object[]{1L, date, new BigDecimal("1200"), new BigDecimal("1000")},
                new Object[]{2L, date, new BigDecimal("2800"), new BigDecimal("2500")}
            ));
        stubValuationLenient(acc1, "1200", "1000");
        stubValuationLenient(acc2, "2800", "2500");

        List<NetWorthPoint> result = historyService.buildHistory(List.of(1L, 2L), 1, true, MEMBER_ID);

        NetWorthPoint atDate = result.stream()
            .filter(p -> p.date().equals(date))
            .findFirst()
            .orElseThrow();
        assertThat(atDate.accounts().get(1L).invested()).isEqualByComparingTo("1000");
        assertThat(atDate.accounts().get(2L).invested()).isEqualByComparingTo("2500");
        assertThat(atDate.invested()).isEqualByComparingTo("3500");
        assertThat(atDate.total()).isEqualByComparingTo("4000");
    }

    @Test
    void buildHistory_rejectsAccountsOwnedByAnotherMember() {
        Account othersAccount = brokerage(1L, "CT"); // belongs to MEMBER (id 99)
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(othersAccount));

        // A different member must not be able to read account 1's history.
        assertThatThrownBy(() -> historyService.buildHistory(List.of(1L), 1, false, 7L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void buildHistory_letsTheOwnerReadAnAccountTheyHoldNoShareOf() {
        // An owner may legitimately hold none of their own account -- they can transfer their
        // whole share away, and the resolver reports that as 0 rather than inventing 100%.
        // Reading is still theirs: they administer it. Rejecting the request instead 404'd the
        // *whole batch*, and DashboardService sends every readable id at once, so one such
        // account took the entire dashboard history down with it.
        Account transferred = brokerage(1L, "Maison");
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(transferred));
        // doReturn, not when(...): the lenient @BeforeEach answer would run against the matcher
        // call itself and NPE on a null collection.
        doReturn(java.util.Map.of(1L, java.math.BigDecimal.ZERO))
            .when(accessResolver).sharesFor(any(), any());
        stubValuation(transferred, "0", "0");

        assertThatCode(() -> historyService.buildHistory(List.of(1L), 1, false, MEMBER_ID))
            .doesNotThrowAnyException();
    }

    @Test
    void buildHistory_stillRejectsAZeroShareForANonOwner() {
        // The relaxation is for the owner only: a zero share is still the "not yours" signal
        // for everyone else, including a co-owner written out of the split.
        Account othersAccount = brokerage(1L, "CT");
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(othersAccount));
        doReturn(java.util.Map.of(1L, java.math.BigDecimal.ZERO))
            .when(accessResolver).sharesFor(any(), any());

        assertThatThrownBy(() -> historyService.buildHistory(List.of(1L), 1, false, 7L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void buildHistory_rejectsNullMemberId() {
        Account account = brokerage(1L, "CT");
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(account));

        // Member scoping is mandatory — a null memberId is a programming error,
        // not a signal to return every requested account unscoped.
        assertThatThrownBy(() -> historyService.buildHistory(List.of(1L), 1, false, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── buildPnl characterization ────────────────────────────────────────────

    private static Account loan(long id, String balance) {
        return Account.builder()
            .id(id).name("Loan").type(AccountType.LOAN).currency("EUR")
            .currentBalance(new BigDecimal(balance)).color("#ef4444").member(MEMBER).build();
    }

    private static Account checking(long id, String balance) {
        return Account.builder()
            .id(id).name("Checking").type(AccountType.CHECKING).currency("EUR")
            .currentBalance(new BigDecimal(balance)).color("#3b82f6").member(MEMBER).build();
    }

    @Test
    void buildPnl_loanSubtractsFromTotal_excludedFromInvested() {
        Account loanAcc = loan(1L, "10000");
        Account brokerageAcc = brokerage(2L, "CT");

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(loanAcc, brokerageAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        stubValuation(loanAcc, "10000", "10000");
        stubValuation(brokerageAcc, "5100", "4800");

        PnlResponse result = historyService.buildPnl(List.of(1L, 2L), MEMBER_ID);

        // CHARACTERIZATION (updated by plan 005): pnl is debt-neutral — loans contribute 0,
        // so pnl reflects only non-loan performance while total stays net worth.
        assertThat(result.total()).isEqualByComparingTo("-4900");   // 5100 − 10000 (net worth, loan negated)
        assertThat(result.invested()).isEqualByComparingTo("4800"); // loan excluded from invested
        assertThat(result.pnl()).isEqualByComparingTo("300");       // 5100 − 4800, loan contributes 0
        assertThat(result.pnlPercent()).isEqualByComparingTo("6.3"); // 300 × 100 / 4800, HALF_UP scale 1
    }

    @Test
    void buildPnl_zeroInvested_returnsNullPercent() {
        Account loanAcc = loan(1L, "10000");

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(loanAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        stubValuation(loanAcc, "10000", "10000");

        PnlResponse result = historyService.buildPnl(List.of(1L), MEMBER_ID);

        // A loan-only selection sums invested to 0 → the compareTo > 0 guard skips the division.
        assertThat(result.invested()).isEqualByComparingTo("0");
        assertThat(result.pnl()).isEqualByComparingTo("0"); // Debt-neutral pnl (plan 005): a loan-only selection has zero investment pnl.
        assertThat(result.pnlPercent()).isNull();
    }

    @Test
    void buildPnl_noHistoricalPrices_fallsBackToLivePnl() {
        LocalDate fromDate = LocalDate.now().minusDays(30);
        Account brokerageAcc = brokerage(1L, "CT");
        AccountHolding holding = AccountHolding.builder()
            .account(brokerageAcc).ticker("AAPL")
            .quantity(new BigDecimal("10")).averageBuyIn(new BigDecimal("100")).build();

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(brokerageAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        stubValuation(brokerageAcc, "5100", "4800");
        when(priceSnapshotRepository.findLatestByTickerBeforeOrOnDate("AAPL", fromDate))
            .thenReturn(Optional.empty());

        PnlResponse result = historyService.buildPnl(List.of(1L), MEMBER_ID, fromDate);

        // Zero matched prices → the live-only response shape: range fields stay null.
        assertThat(result.total()).isEqualByComparingTo("5100");
        assertThat(result.invested()).isEqualByComparingTo("4800");
        assertThat(result.pnl()).isEqualByComparingTo("300");
        assertThat(result.valueAtFrom()).isNull();
        assertThat(result.rangePnl()).isNull();
        assertThat(result.rangePnlPercent()).isNull();
    }

    @Test
    void buildPnl_rangePnl_computedAgainstHoldingsOnlyBaseline() {
        LocalDate fromDate = LocalDate.now().minusDays(30);
        Account brokerageAcc = brokerage(1L, "CT");
        Account cashAcc = checking(2L, "2000");
        AccountHolding holding = AccountHolding.builder()
            .account(brokerageAcc).ticker("AAPL")
            .quantity(new BigDecimal("10")).averageBuyIn(new BigDecimal("100")).build();

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(brokerageAcc, cashAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(holding));
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        stubValuation(brokerageAcc, "5100", "4800");
        stubValuation(cashAcc, "2000", "2000");
        when(priceSnapshotRepository.findLatestByTickerBeforeOrOnDate("AAPL", fromDate))
            .thenReturn(Optional.of(PriceSnapshot.builder()
                .ticker("AAPL").date(fromDate).priceEur(new BigDecimal("90")).build()));
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("510"));

        PnlResponse result = historyService.buildPnl(List.of(1L, 2L), MEMBER_ID, fromDate);

        // CHARACTERIZATION (updated by plan 005): both sides of the range use the SAME
        // matched-holdings universe — cash/loans no longer inflate the live side (audit BE-04).
        // valueAtFrom = 10 × 90 = 900; liveMatchedValue = 10 × 510 = 5100 (cash 2000 excluded).
        assertThat(result.valueAtFrom()).isEqualByComparingTo("900");
        assertThat(result.rangePnl()).isEqualByComparingTo("4200"); // 5100 − 900
    }

    @Test
    void buildPnl_rangePnl_ignoresHoldingsWithoutSnapshotOnBothSides() {
        LocalDate fromDate = LocalDate.now().minusDays(30);
        Account brokerageAcc = brokerage(1L, "CT");
        AccountHolding matched = AccountHolding.builder()
            .account(brokerageAcc).ticker("AAPL")
            .quantity(new BigDecimal("10")).averageBuyIn(new BigDecimal("100")).build();
        AccountHolding unmatched = AccountHolding.builder()
            .account(brokerageAcc).ticker("MSFT")
            .quantity(new BigDecimal("4")).averageBuyIn(new BigDecimal("200")).build();

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(brokerageAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(matched, unmatched));
        stubValuation(brokerageAcc, "6300", "1800");
        when(priceSnapshotRepository.findLatestByTickerBeforeOrOnDate("AAPL", fromDate))
            .thenReturn(Optional.of(PriceSnapshot.builder()
                .ticker("AAPL").date(fromDate).priceEur(new BigDecimal("90")).build()));
        // MSFT has NO snapshot at fromDate → excluded from BOTH sides of the range.
        when(priceSnapshotRepository.findLatestByTickerBeforeOrOnDate("MSFT", fromDate))
            .thenReturn(Optional.empty());
        when(priceService.getPriceEur("AAPL")).thenReturn(new BigDecimal("510"));

        PnlResponse result = historyService.buildPnl(List.of(1L), MEMBER_ID, fromDate);

        // Only AAPL is priced on both sides: valueAtFrom = 10 × 90 = 900,
        // liveMatchedValue = 10 × 510 = 5100 → rangePnl = 4200. MSFT's live value
        // never enters the comparison, so an unmatched holding cannot skew the range.
        assertThat(result.valueAtFrom()).isEqualByComparingTo("900");
        assertThat(result.rangePnl()).isEqualByComparingTo("4200");
        assertThat(result.rangePnlPercent()).isEqualByComparingTo("466.7"); // 4200 × 100 / 900
    }

    @Test
    void buildPnl_emptyAccountIds_returnsZeros() {
        when(accountRepository.findAllById(List.of())).thenReturn(List.of());

        PnlResponse result = historyService.buildPnl(List.of(), MEMBER_ID);

        assertThat(result.total()).isEqualByComparingTo("0");
        assertThat(result.invested()).isEqualByComparingTo("0");
        assertThat(result.pnl()).isEqualByComparingTo("0");
        assertThat(result.pnlPercent()).isNull();
    }

    @Test
    void buildPnl_foreignAccount_throws() {
        Account othersAccount = brokerage(1L, "CT"); // belongs to MEMBER (id 99)
        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(othersAccount));

        // A different member must not be able to read account 1's PnL.
        assertThatThrownBy(() -> historyService.buildPnl(List.of(1L), 7L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── buildIntradayHistory characterization ────────────────────────────────

    @Test
    void buildIntradayHistory_loanNegated_cashConstant() {
        Account loanAcc = loan(1L, "10000");
        Account cashAcc = checking(2L, "2000");

        when(accountRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(loanAcc, cashAcc));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of());
        when(holdingRepository.findByAccount_Id(2L)).thenReturn(List.of());
        when(snapshotRepository.findByAccountIdAndDate(eq(1L), any(LocalDate.class))).thenReturn(Optional.empty());
        when(snapshotRepository.findByAccountIdAndDate(eq(2L), any(LocalDate.class))).thenReturn(Optional.empty());
        // Lenient: the intraday path needs the value only, so it never asks for a cost basis.
        stubValuationLenient(loanAcc, "10000", "10000");
        stubValuationLenient(cashAcc, "2000", "2000");

        List<NetWorthIntradayPoint> result = historyService.buildIntradayHistory(List.of(1L, 2L), MEMBER_ID);

        assertThat(result).isNotEmpty();
        for (NetWorthIntradayPoint point : result) {
            // Every hourly point: total = cash 2000 − loan 10000; loan excluded from invested.
            assertThat(point.total()).isEqualByComparingTo("-8000");
            assertThat(point.invested()).isEqualByComparingTo("2000");
        }
    }

    @Test
    void buildIntradayHistory_cashPocketCountsOnBothSides() {
        // 10 AAPL bought at 100, quoted 120 all day, plus 500 of idle cash in the envelope.
        Account pea = brokerage(1L, "PEA");
        pea.setCashBalance(new BigDecimal("500"));
        AccountHolding aapl = AccountHolding.builder()
            .ticker("AAPL").quantity(new BigDecimal("10")).averageBuyIn(new BigDecimal("100")).build();

        when(accountRepository.findAllById(List.of(1L))).thenReturn(List.of(pea));
        when(holdingRepository.findByAccount_Id(1L)).thenReturn(List.of(aapl));
        when(priceService.toEur(new BigDecimal("100"), "EUR", null)).thenReturn(new BigDecimal("100"));
        // An hourly series inside the requested window, the shape the providers return: from
        // 23 hours ago up to now, on the hour, so only the very first bucket (24 hours ago)
        // predates the first quote.
        LocalDateTime thisHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        LocalDateTime firstQuote = thisHour.minusHours(23);
        Map<LocalDateTime, BigDecimal> quotes = new java.util.HashMap<>();
        for (LocalDateTime t = firstQuote; !t.isAfter(thisHour); t = t.plusHours(1)) {
            quotes.put(t, new BigDecimal("120"));
        }
        when(priceService.getIntradayPricesEur(eq("AAPL"), any(), any())).thenReturn(quotes);

        List<NetWorthIntradayPoint> result = historyService.buildIntradayHistory(List.of(1L), MEMBER_ID);

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> !p.timestamp().isBefore(firstQuote));
        for (NetWorthIntradayPoint point : result) {
            // invested = 10 x 100 + 500 of cash at every hour: the pocket is worth what it cost.
            assertThat(point.invested()).isEqualByComparingTo("1500");
            if (point.timestamp().isBefore(firstQuote)) {
                // No quote yet: the positions are worth nothing at that hour, the pocket remains.
                assertThat(point.total()).isEqualByComparingTo("500");
            } else {
                // total = 10 x 120 + 500 of cash: the pocket moves both sides by the same 500, so
                // the gain stays 200, and the total is the one the daily chart's today point
                // reports through valuation() rather than 500 below it.
                assertThat(point.total()).isEqualByComparingTo("1700");
            }
        }
    }
}
