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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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

    public AllocationService(
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        BudgetSettingsService budgetSettingsService
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetSettingsService = budgetSettingsService;
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
        for (Account account : accountRepository.findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc(memberId)) {
            AssetClass assetClass = AssetClass.of(account.getType());
            if (assetClass == AssetClass.OTHER) {
                continue; // not part of cash allocation
            }
            byClass.merge(assetClass, account.getCurrentBalance(), BigDecimal::add);
        }

        BigDecimal total = byClass.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AllocationStock> stock = new ArrayList<>();
        for (Map.Entry<AssetClass, BigDecimal> e : byClass.entrySet()) {
            stock.add(new AllocationStock(e.getKey(), e.getValue(), percentOf(e.getValue(), total)));
        }
        stock.sort(Comparator.comparing(AllocationStock::assetClass));
        return stock;
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
