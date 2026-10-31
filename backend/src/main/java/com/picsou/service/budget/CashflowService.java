package com.picsou.service.budget;

import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.CashflowResponse;
import com.picsou.dto.CashflowResponse.CashflowBucket;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates a member's transactions into income / expense / net, both as a total and broken
 * down per pay cycle. Income and expense are read from the <em>sign</em> of each amount (so
 * uncategorized transactions still count); only {@link CategoryKind#TRANSFER} transactions —
 * money moved between the member's own accounts — are excluded, since they are neither income
 * nor spending. Bucketing each transaction by its pay cycle guarantees the grand totals equal
 * the sum of the series.
 */
@Service
@Transactional(readOnly = true)
public class CashflowService {

    private static final DateTimeFormatter BUCKET_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TransactionRepository transactionRepository;
    private final BudgetSettingsService budgetSettingsService;

    public CashflowService(
        TransactionRepository transactionRepository,
        BudgetSettingsService budgetSettingsService
    ) {
        this.transactionRepository = transactionRepository;
        this.budgetSettingsService = budgetSettingsService;
    }

    public CashflowResponse compute(Long memberId, CashflowPeriod period, LocalDate today) {
        int cycleStartDay = budgetSettingsService.cycleStartDay(memberId);
        BudgetCycle.CycleRange currentCycle = BudgetCycle.cycleFor(today, cycleStartDay);

        LocalDate from;
        LocalDate to;
        if (period == CashflowPeriod.YTD) {
            from = today.withDayOfYear(1);
            to = today;
        } else {
            from = currentCycle.start();
            to = currentCycle.end();
        }

        // Seed an ordered bucket per pay cycle in the span so empty cycles still appear.
        Map<LocalDate, Accumulator> buckets = new LinkedHashMap<>();
        for (BudgetCycle.CycleRange cycle : BudgetCycle.cyclesBetween(from, to, cycleStartDay)) {
            buckets.put(cycle.start(), new Accumulator(cycle));
        }

        for (Transaction tx : transactionRepository.findByMemberIdAndDateBetween(memberId, from, to)) {
            if (isTransfer(tx)) {
                continue;
            }
            BudgetCycle.CycleRange cycle = BudgetCycle.cycleFor(tx.getDate(), cycleStartDay);
            buckets.computeIfAbsent(cycle.start(), k -> new Accumulator(cycle)).add(tx.getAmount());
        }

        List<CashflowBucket> series = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (Accumulator acc : buckets.values()) {
            series.add(acc.toBucket());
            totalIncome = totalIncome.add(acc.income);
            totalExpense = totalExpense.add(acc.expense);
        }

        return new CashflowResponse(
            period, from, to,
            totalIncome, totalExpense, totalIncome.subtract(totalExpense),
            series
        );
    }

    private static boolean isTransfer(Transaction tx) {
        return tx.getCategoryRef() != null && tx.getCategoryRef().getKind() == CategoryKind.TRANSFER;
    }

    /** Mutable per-cycle tally; expense is accumulated as a positive magnitude. */
    private static final class Accumulator {
        private final BudgetCycle.CycleRange cycle;
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;

        Accumulator(BudgetCycle.CycleRange cycle) {
            this.cycle = cycle;
        }

        void add(BigDecimal amount) {
            if (amount.signum() >= 0) {
                income = income.add(amount);
            } else {
                expense = expense.add(amount.negate());
            }
        }

        CashflowBucket toBucket() {
            return new CashflowBucket(
                cycle.start(), cycle.end(), cycle.start().format(BUCKET_LABEL),
                income, expense, income.subtract(expense)
            );
        }
    }
}
