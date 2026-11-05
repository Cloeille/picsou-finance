package com.picsou.service.budget;

import com.picsou.dto.AllocationResponse;
import com.picsou.dto.AllocationResponse.AllocationContribution;
import com.picsou.dto.AllocationResponse.AllocationStock;
import com.picsou.dto.CashflowPeriod;
import com.picsou.model.Account;
import com.picsou.model.AssetClass;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.AccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the allocation view in two parts:
 * <ul>
 *   <li><b>Stock</b> — current account balances grouped by {@link AssetClass}
 *       (CURRENT / SAVINGS / INVESTMENT). OTHER (real estate, loans…) is excluded so the
 *       donut reflects investable money rather than full net worth.</li>
 *   <li><b>Contributions</b> — the net amount moved <em>into</em> each savings/investment
 *       account over the period, read from incoming {@link CategoryKind#TRANSFER}
 *       transactions. This is the flux that grows the stock.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class AllocationService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetSettingsService budgetSettingsService;
    private final AccountService accountService;

    public AllocationService(
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        BudgetSettingsService budgetSettingsService,
        AccountService accountService
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetSettingsService = budgetSettingsService;
        this.accountService = accountService;
    }

    public AllocationResponse compute(Long memberId, CashflowPeriod period, LocalDate today) {
        int cycleStartDay = budgetSettingsService.cycleStartDay(memberId);
        LocalDate from;
        LocalDate to;
        if (period == CashflowPeriod.YTD) {
            from = today.withDayOfYear(1);
            to = today;
        } else {
            BudgetCycle.CycleRange cycle = BudgetCycle.cycleFor(today, cycleStartDay);
            from = cycle.start();
            to = cycle.end();
        }

        List<AllocationStock> stock = buildStock(memberId);
        BigDecimal totalStock = stock.stream()
            .map(AllocationStock::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<AllocationContribution> contributions = buildContributions(memberId, from, to);
        BigDecimal totalContributions = contributions.stream()
            .map(AllocationContribution::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AllocationResponse(
            period, from, to, totalStock, stock, totalContributions, contributions);
    }

    // ─── Stock ────────────────────────────────────────────────────────────────

    private List<AllocationStock> buildStock(Long memberId) {
        Map<AssetClass, BigDecimal> byClass = new EnumMap<>(AssetClass.class);
        List<Account> accounts = accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId);
        Map<Long, Account> byId = new HashMap<>();
        for (Account account : accounts) {
            byId.put(account.getId(), account);
        }
        // The stored balance is a USD amount for a USD account, a coin quantity for an account
        // with a ticker, and the last stored figure for an account with holdings. Value each
        // account with the same EUR valuation as the dashboard (liveBalanceEur), once, and only
        // if it contributes: a skipped account is never valued, so its failing price or
        // snapshot lookup cannot break the view.
        Map<Long, BigDecimal> valueById = new HashMap<>();
        // What is left of each parent wallet after the sub-accounts taken out of it so far.
        Map<Long, BigDecimal> parentLeft = new HashMap<>();
        for (Account account : accounts) {
            AssetClass assetClass = AssetClass.of(account.getType());
            if (assetClass == AssetClass.OTHER) {
                continue; // not part of cash allocation
            }
            if (account.getParentAccountId() == null) {
                byClass.merge(assetClass, eurValue(account, valueById), BigDecimal::add);
                continue;
            }
            // Pocket / money-box sub-account: the parent wallet's balance already includes it,
            // and the dashboard counts only the parent. Count it in its own class and take the
            // same amount out of the parent's, so the total is unchanged but a money-box shows
            // as savings. It is skipped, as on the dashboard, when its parent is not listed or
            // is not part of the allocation, and when it is in its parent's class: it is then
            // already in the parent's amount for that class, and moving it would change no
            // class total but use up what is left of the wallet for a money-box of another
            // class. A sub-account's stored balance can be older than its wallet's (an
            // unselected Revolut pocket keeps its last balance, an Enable Banking sync rewrites
            // the wallet's), so it moves between zero and what is left of the wallet: a
            // negative sub-account moves nothing, its negative is already in the wallet's
            // balance.
            Account parent = byId.get(account.getParentAccountId());
            if (parent == null) {
                continue;
            }
            AssetClass parentClass = AssetClass.of(parent.getType());
            if (parentClass == AssetClass.OTHER || parentClass == assetClass) {
                continue;
            }
            BigDecimal left = parentLeft.computeIfAbsent(parent.getId(), id -> eurValue(parent, valueById));
            BigDecimal moved = eurValue(account, valueById).max(BigDecimal.ZERO).min(left.max(BigDecimal.ZERO));
            parentLeft.put(parent.getId(), left.subtract(moved));
            byClass.merge(parentClass, moved.negate(), BigDecimal::add);
            byClass.merge(assetClass, moved, BigDecimal::add);
        }

        BigDecimal total = byClass.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AllocationStock> stock = new ArrayList<>();
        for (Map.Entry<AssetClass, BigDecimal> e : byClass.entrySet()) {
            stock.add(new AllocationStock(e.getKey(), e.getValue(), percentOf(e.getValue(), total)));
        }
        stock.sort(Comparator.comparing(AllocationStock::assetClass));
        return stock;
    }

    private BigDecimal eurValue(Account account, Map<Long, BigDecimal> valueById) {
        return valueById.computeIfAbsent(account.getId(), id -> accountService.liveBalanceEur(account));
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    }

    // ─── Contributions (flux) ───────────────────────────────────────────────────

    private List<AllocationContribution> buildContributions(Long memberId, LocalDate from, LocalDate to) {
        // Preserve account identity while summing; LinkedHashMap keeps a stable order.
        Map<Long, AllocationContribution> byAccount = new LinkedHashMap<>();
        for (Transaction tx : transactionRepository
            .findByMemberIdAndKindAndDateBetween(memberId, CategoryKind.TRANSFER, from, to)) {

            // Only money arriving (positive) into a savings/investment account counts as a
            // contribution; the matching outflow from the current account is ignored.
            if (tx.getAmount().signum() <= 0) {
                continue;
            }
            Account account = tx.getAccount();
            AssetClass assetClass = AssetClass.of(account.getType());
            if (!assetClass.tracksContributions()) {
                continue;
            }
            byAccount.merge(
                account.getId(),
                new AllocationContribution(account.getId(), account.getName(), assetClass,
                    account.getColor(), tx.getAmount()),
                (existing, add) -> new AllocationContribution(
                    existing.accountId(), existing.accountName(), existing.assetClass(),
                    existing.color(), existing.amount().add(add.amount()))
            );
        }
        List<AllocationContribution> contributions = new ArrayList<>(byAccount.values());
        contributions.sort(Comparator.comparing(AllocationContribution::amount).reversed());
        return contributions;
    }
}
