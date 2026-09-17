package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.dto.DashboardResponse.DistributionItem;
import com.picsou.dto.DashboardResponse.NetWorthPoint;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountHolding;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.model.GoalType;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final AccountRepository accountRepository;
    private final GoalService goalService;
    private final GoalRepository goalRepository;
    private final PriceService priceService;
    private final AccountHoldingRepository holdingRepository;
    private final HistoryService historyService;
    private final DebtRepository debtRepository;
    private final LoanAmortizationService loanAmortizationService;
    private final AccountService accountService;
    private final AccountAccessResolver accessResolver;

    public DashboardService(
        AccountRepository accountRepository,
        GoalService goalService,
        GoalRepository goalRepository,
        PriceService priceService,
        AccountHoldingRepository holdingRepository,
        HistoryService historyService,
        DebtRepository debtRepository,
        LoanAmortizationService loanAmortizationService,
        AccountService accountService,
        AccountAccessResolver accessResolver
    ) {
        this.accountRepository = accountRepository;
        this.goalService = goalService;
        this.goalRepository = goalRepository;
        this.priceService = priceService;
        this.holdingRepository = holdingRepository;
        this.historyService = historyService;
        this.debtRepository = debtRepository;
        this.loanAmortizationService = loanAmortizationService;
        this.accountService = accountService;
        this.accessResolver = accessResolver;
    }

    public DashboardResponse getDashboard(Long memberId, String range) {
        // Owned accounts plus any the member co-owns; each contributes only their share.
        // Hidden accounts are excluded, matching every other user-facing account list.
        List<Account> accounts = accessResolver.readableAccounts(memberId).stream()
            .filter(a -> !a.isHidden())
            .toList();
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);

        // Pre-load all holdings and group by account
        Map<Long, List<AccountHolding>> holdingsByAccount = new HashMap<>();
        for (Account account : accounts) {
            holdingsByAccount.put(account.getId(), holdingRepository.findByAccount_Id(account.getId()));
        }

        // Calculate live total and invested from holdings + cash
        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        Map<Long, BigDecimal> accountValues = new HashMap<>();

        for (Account account : accounts) {
            // Pocket sub-accounts: balance already counted in the parent wallet.
            if (account.getParentAccountId() != null) continue;

            List<AccountHolding> holdings = holdingsByAccount.get(account.getId());

            BigDecimal accountValue;
            BigDecimal accountInvested;

            if (account.getType() == AccountType.LOAN) {
                // Same valuation source as HistoryService's live point: amortized
                // remaining capital when a Debt row exists, stored balance otherwise.
                // Keeps the hero's liabilities consistent with the chart's today point.
                accountValue = accountService.liveBalanceEur(account);
                accountInvested = BigDecimal.ZERO;
            } else if (holdings.isEmpty()) {
                accountValue = priceService.toEur(account.getCurrentBalance(), account.getCurrency(), account.getTicker());
                accountInvested = accountValue;
            } else {
                // One pass for both figures. Summing the cost basis here while taking the value
                // from liveBalanceEur is exactly the asymmetry that reported an untouched account
                // at -85%: the value drops a holding it cannot price, the inline loop kept that
                // holding's full purchase cost. valuation() excludes it from both sides, and
                // still falls back atomically to a provider's own EUR total (Bourse Direct,
                // Amundi) when a price lookup fails.
                AccountService.Valuation valuation = accountService.valuation(account);
                accountValue = valuation.liveEur();
                accountInvested = valuation.investedEur();
            }

            // Apply the member's share once, here: accountValues feeds both the hero totals
            // and buildDistribution, so weighting in one place keeps the pie consistent with
            // the headline figure. Accounts with no split resolve to 100% and are untouched.
            BigDecimal share = shares.get(account.getId());
            accountValue = AccountAccessResolver.weigh(accountValue, share);
            accountInvested = AccountAccessResolver.weigh(accountInvested, share);

            accountValues.put(account.getId(), accountValue);

            if (account.getType() == AccountType.LOAN) {
                totalLiabilities = totalLiabilities.add(accountValue);
            } else {
                totalAssets = totalAssets.add(accountValue);
                totalInvested = totalInvested.add(accountInvested);
            }
        }

        BigDecimal totalNetWorth = totalAssets.subtract(totalLiabilities);

        log.info("getDashboard: totalAssets={}, totalLiabilities={}, totalNetWorth={}, totalInvested={}, pnl={}",
            totalAssets, totalLiabilities, totalNetWorth, totalInvested, totalNetWorth.subtract(totalInvested));

        // Build history using shared HistoryService. Pocket sub-accounts are excluded -- same reason
        // as the skip above: their balance is already counted in the parent wallet, and HistoryService
        // sums whatever account ids it's given with no parentAccountId filtering of its own.
        List<Long> allAccountIds = accounts.stream()
            .filter(a -> a.getParentAccountId() == null)
            .map(Account::getId)
            .toList();
        int months = switch (range != null ? range : "1Y") {
            case "7D", "1M" -> 1;
            case "3M" -> 3;
            case "YTD" -> LocalDate.now().getMonthValue();
            case "ALL" -> 1200;
            default -> 12;
        };
        List<NetWorthPoint> updatedHistory = historyService.buildHistory(allAccountIds, months, memberId);

        // Percentages are shares of their own side of the balance sheet:
        // assets divide by totalAssets, liabilities by totalLiabilities (issue #18).
        List<DistributionItem> distribution = buildDistribution(
            accounts, totalAssets, holdingsByAccount, accountValues, false);
        List<DistributionItem> rawLiabilities = buildDistribution(
            accounts, totalLiabilities, holdingsByAccount, accountValues, true);

        // Enrich liabilities with loan parameters in one query
        List<Long> liabilityIds = rawLiabilities.stream().map(DistributionItem::accountId).toList();
        Map<Long, Debt> debtByAccountId = debtRepository.findByAccountIdIn(liabilityIds).stream()
            .collect(Collectors.toMap(d -> d.getAccount().getId(), d -> d));

        BigDecimal totalMonthlyPayment = null;
        List<DashboardResponse.LiabilityEntry> liabilities = new ArrayList<>();
        for (DistributionItem item : rawLiabilities) {
            Debt debt = debtByAccountId.get(item.accountId());
            BigDecimal monthlyPayment = null;
            Double percentPaid = null;
            if (debt != null) {
                monthlyPayment = loanAmortizationService.resolveMonthlyPayment(debt);
                BigDecimal borrowed = debt.getBorrowedAmount();
                if (borrowed != null && borrowed.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal remaining = item.balanceEur().abs();
                    BigDecimal repaid = borrowed.subtract(remaining);
                    percentPaid = repaid.divide(borrowed, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
                    percentPaid = Math.max(0.0, Math.min(100.0, percentPaid));
                }
                if (monthlyPayment != null) {
                    totalMonthlyPayment = (totalMonthlyPayment == null ? BigDecimal.ZERO : totalMonthlyPayment)
                        .add(monthlyPayment);
                }
            }
            liabilities.add(new DashboardResponse.LiabilityEntry(
                item.accountId(), item.name(), item.color(), item.balanceEur(),
                item.percentage(), item.accountType(), item.hasHoldings(),
                monthlyPayment, percentPaid
            ));
        }

        // Savings targets only. The dashboard card shows progress towards an amount, which a
        // recurring investment plan does not have — it would render as an empty row with no
        // percentage and no deadline. Recurring plans live on the Goals page and in the
        // projection instead.
        List<GoalProgressResponse> goals = goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(goal -> goal.getType() != GoalType.RECURRING_INVESTMENT)
            .map(goalService::toProgressResponse)
            .toList();

        return new DashboardResponse(totalNetWorth, totalLiabilities, totalMonthlyPayment,
            updatedHistory, distribution, liabilities, goals);
    }

    private List<DistributionItem> buildDistribution(List<Account> accounts, BigDecimal divisor,
                                                       Map<Long, List<AccountHolding>> holdingsByAccount,
                                                       Map<Long, BigDecimal> accountValues,
                                                       boolean liabilitiesOnly) {
        List<DistributionItem> items = new ArrayList<>();

        for (Account account : accounts) {
            // Pocket sub-accounts are internal transfers from their parent wallet — their balance
            // is already included in the wallet, so including them here would double-count.
            if (account.getParentAccountId() != null) continue;
            boolean isLoan = account.getType() == AccountType.LOAN;
            if (liabilitiesOnly != isLoan) continue;

            List<AccountHolding> holdings = holdingsByAccount.getOrDefault(account.getId(), List.of());
            // Reuse the exact value that fed the hero total. Repricing here could mix two
            // market snapshots or turn a broker fallback into a partial Yahoo valuation.
            BigDecimal balanceEur = accountValues.getOrDefault(account.getId(), BigDecimal.ZERO);

            double percentage = divisor.compareTo(BigDecimal.ZERO) > 0
                ? balanceEur.divide(divisor, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue()
                : 0.0;

            items.add(new DistributionItem(
                account.getId(),
                account.getName(),
                account.getColor(),
                balanceEur,
                Math.round(percentage * 100.0) / 100.0,
                account.getType().name(),
                !holdings.isEmpty()
            ));
        }

        return items;
    }
}
