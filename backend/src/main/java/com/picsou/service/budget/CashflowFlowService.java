package com.picsou.service.budget;

import com.picsou.dto.CashflowFlowResponse;
import com.picsou.dto.CashflowFlowResponse.FlowLink;
import com.picsou.dto.CashflowFlowResponse.FlowNode;
import com.picsou.dto.CashflowFlowResponse.NodeType;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.SpendingByCategoryResponse;
import com.picsou.dto.SpendingByCategoryResponse.CategorySpend;
import com.picsou.dto.SpendingDetailResponse;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spending aggregations over a member's transactions: the income→budget→expense Sankey
 * ({@link #flow}), the ranked expense breakdown ({@link #spendingByCategory}), and a
 * single category's transactions for the drill page ({@link #categoryDetail}).
 *
 * <p>All three share {@link CashflowService}'s conventions exactly — the same pay-cycle
 * range, the same {@code TRANSFER} exclusion, and the same sign-based income/expense split
 * — so the flow totals always equal the cashflow totals. Income/expense are accumulated per
 * category in a single pass; the {@code null}-category bucket is spending with no managed
 * category yet.
 */
@Service
@Transactional(readOnly = true)
public class CashflowFlowService {

    /** Keep the Sankey legible: beyond this many expense bands, the tail rolls into "other". */
    private static final int MAX_EXPENSE_NODES = 8;

    private final TransactionRepository transactionRepository;
    private final BudgetSettingsService budgetSettingsService;
    private final CategoryRepository categoryRepository;

    public CashflowFlowService(
        TransactionRepository transactionRepository,
        BudgetSettingsService budgetSettingsService,
        CategoryRepository categoryRepository
    ) {
        this.transactionRepository = transactionRepository;
        this.budgetSettingsService = budgetSettingsService;
        this.categoryRepository = categoryRepository;
    }

    // ─── Sankey flow ───────────────────────────────────────────────────────────

    public CashflowFlowResponse flow(Long memberId, CashflowPeriod period, LocalDate today) {
        Range r = range(memberId, period, today);

        Map<Long, Agg> income = new HashMap<>();
        Map<Long, Agg> expense = new HashMap<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;

        for (Transaction tx : transactionRepository.findByMemberIdAndDateBetween(memberId, r.from, r.to)) {
            if (isTransfer(tx)) {
                continue;
            }
            BigDecimal amount = tx.getAmount();
            Category cat = tx.getCategoryRef();
            Long key = cat != null ? cat.getId() : null;
            if (amount.signum() >= 0) {
                totalIncome = totalIncome.add(amount);
                income.computeIfAbsent(key, k -> new Agg(cat)).add(amount);
            } else {
                BigDecimal magnitude = amount.negate();
                totalExpense = totalExpense.add(magnitude);
                expense.computeIfAbsent(key, k -> new Agg(cat)).add(magnitude);
            }
        }

        BigDecimal net = totalIncome.subtract(totalExpense);
        List<FlowNode> nodes = new ArrayList<>();
        List<FlowLink> links = new ArrayList<>();

        // Nothing to show — let the frontend render an empty state.
        if (totalIncome.signum() == 0 && totalExpense.signum() == 0) {
            return new CashflowFlowResponse(period, r.from, r.to,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, nodes, links);
        }

        // Sources (left): income categories, then a drawdown source if we overspent.
        List<NodeValue> sources = incomeNodes(income);
        if (net.signum() < 0) {
            sources.add(new NodeValue(synthetic("__drawdown__", NodeType.INCOME), net.negate()));
        }
        for (NodeValue source : sources) {
            nodes.add(source.node);
        }

        int hubIndex = nodes.size();
        nodes.add(synthetic("__hub__", NodeType.HUB));
        for (int i = 0; i < sources.size(); i++) {
            links.add(new FlowLink(i, hubIndex, sources.get(i).value));
        }

        // Sinks (right): expense categories (top-N + rollup), then a savings sink if net positive.
        List<NodeValue> sinks = expenseNodes(expense);
        if (net.signum() > 0) {
            sinks.add(new NodeValue(synthetic("__savings__", NodeType.SAVINGS), net));
        }
        for (NodeValue sink : sinks) {
            int idx = nodes.size();
            nodes.add(sink.node);
            links.add(new FlowLink(hubIndex, idx, sink.value));
        }

        return new CashflowFlowResponse(period, r.from, r.to, totalIncome, totalExpense, net, nodes, links);
    }

    /** Income sources, largest first; uncategorized income collapses into one node. */
    private List<NodeValue> incomeNodes(Map<Long, Agg> income) {
        List<NodeValue> out = new ArrayList<>();
        for (Agg agg : income.values()) {
            if (agg.sum.signum() <= 0) {
                continue;
            }
            FlowNode node = agg.category != null
                ? categoryNode(agg.category, NodeType.INCOME)
                : synthetic("__income_other__", NodeType.INCOME);
            out.add(new NodeValue(node, agg.sum));
        }
        out.sort(Comparator.comparing((NodeValue n) -> n.value).reversed());
        return out;
    }

    /** Expense sinks, largest first, capped at {@link #MAX_EXPENSE_NODES} with an "other" rollup. */
    private List<NodeValue> expenseNodes(Map<Long, Agg> expense) {
        List<NodeValue> all = new ArrayList<>();
        for (Agg agg : expense.values()) {
            if (agg.sum.signum() <= 0) {
                continue;
            }
            FlowNode node = agg.category != null
                ? categoryNode(agg.category, NodeType.EXPENSE)
                : synthetic("__expense_uncat__", NodeType.EXPENSE);
            all.add(new NodeValue(node, agg.sum));
        }
        all.sort(Comparator.comparing((NodeValue n) -> n.value).reversed());

        if (all.size() <= MAX_EXPENSE_NODES) {
            return all;
        }
        List<NodeValue> capped = new ArrayList<>(all.subList(0, MAX_EXPENSE_NODES - 1));
        BigDecimal rest = BigDecimal.ZERO;
        for (NodeValue overflow : all.subList(MAX_EXPENSE_NODES - 1, all.size())) {
            rest = rest.add(overflow.value);
        }
        capped.add(new NodeValue(synthetic("__expense_more__", NodeType.EXPENSE), rest));
        return capped;
    }

    // ─── Ranked breakdown ────────────────────────────────────────────────────

    public SpendingByCategoryResponse spendingByCategory(Long memberId, CashflowPeriod period, LocalDate today) {
        Range r = range(memberId, period, today);

        Map<Long, Agg> expense = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction tx : transactionRepository.findByMemberIdAndDateBetween(memberId, r.from, r.to)) {
            if (isTransfer(tx) || tx.getAmount().signum() >= 0) {
                continue;
            }
            BigDecimal magnitude = tx.getAmount().negate();
            total = total.add(magnitude);
            Category cat = tx.getCategoryRef();
            expense.computeIfAbsent(cat != null ? cat.getId() : null, k -> new Agg(cat)).add(magnitude);
        }

        final BigDecimal denominator = total;
        List<CategorySpend> categories = new ArrayList<>();
        for (Agg agg : expense.values()) {
            if (agg.sum.signum() <= 0) {
                continue;
            }
            BigDecimal share = denominator.signum() > 0
                ? agg.sum.divide(denominator, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            Category cat = agg.category;
            // Leaf-scoped row, annotated with its parent so the client can group the subtree.
            Category parent = cat != null ? cat.getParent() : null;
            categories.add(new CategorySpend(
                cat != null ? cat.getId() : null,
                cat != null ? cat.getSlug() : null,
                cat != null ? cat.getName() : null,
                cat != null ? cat.getColor() : null,
                cat != null ? cat.getIcon() : null,
                agg.sum, agg.count, share,
                parent != null ? parent.getId() : null,
                parent != null ? parent.getName() : null,
                parent != null ? parent.getColor() : null
            ));
        }
        categories.sort(Comparator.comparing(CategorySpend::amount).reversed());

        return new SpendingByCategoryResponse(period, r.from, r.to, total, categories);
    }

    // ─── Category drill ────────────────────────────────────────────────────────

    public SpendingDetailResponse categoryDetail(Long memberId, Long categoryId, CashflowPeriod period, LocalDate today) {
        Category cat = categoryRepository.findByIdAndMemberId(categoryId, memberId)
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
        Range r = range(memberId, period, today);

        // A parent drill spans its whole subtree: the parent's own transactions plus every child's.
        List<Category> childCats =
            categoryRepository.findAllByMemberIdAndParentIdOrderBySortOrderAscIdAsc(memberId, categoryId);
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        for (Category child : childCats) {
            ids.add(child.getId());
        }

        List<Transaction> txns =
            transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(memberId, ids, r.from, r.to);

        BigDecimal total = BigDecimal.ZERO;
        Map<Long, Agg> perChild = new HashMap<>();
        List<TransactionResponse> transactions = new ArrayList<>();
        for (Transaction tx : txns) {
            total = total.add(tx.getAmount());
            transactions.add(TransactionResponse.from(tx));
            Category txCat = tx.getCategoryRef();
            if (txCat != null) {
                perChild.computeIfAbsent(txCat.getId(), k -> new Agg(txCat)).add(tx.getAmount());
            }
        }

        // Per-child rollup (empty for a leaf category), in the children's own sort order.
        List<SpendingDetailResponse.ChildSpend> children = new ArrayList<>();
        for (Category child : childCats) {
            Agg agg = perChild.get(child.getId());
            children.add(new SpendingDetailResponse.ChildSpend(
                child.getId(), child.getName(), child.getColor(), child.getIcon(),
                agg != null ? agg.sum : BigDecimal.ZERO,
                agg != null ? agg.count : 0
            ));
        }

        return new SpendingDetailResponse(
            cat.getId(), cat.getSlug(), cat.getName(), cat.getColor(), cat.getIcon(),
            period, r.from, r.to, total, txns.size(), transactions, children
        );
    }

    // ─── Shared helpers ──────────────────────────────────────────────────────

    /** The [from, to] span for a period — current pay cycle, or calendar year-to-date. */
    private Range range(Long memberId, CashflowPeriod period, LocalDate today) {
        int cycleStartDay = budgetSettingsService.cycleStartDay(memberId);
        if (period == CashflowPeriod.YTD) {
            return new Range(today.withDayOfYear(1), today);
        }
        BudgetCycle.CycleRange cycle = BudgetCycle.cycleFor(today, cycleStartDay);
        return new Range(cycle.start(), cycle.end());
    }

    private static boolean isTransfer(Transaction tx) {
        return tx.getCategoryRef() != null && tx.getCategoryRef().getKind() == CategoryKind.TRANSFER;
    }

    private static FlowNode categoryNode(Category cat, NodeType type) {
        return new FlowNode("cat:" + cat.getId(), cat.getName(), cat.getColor(), type);
    }

    private static FlowNode synthetic(String key, NodeType type) {
        return new FlowNode(key, null, null, type);
    }

    private record Range(LocalDate from, LocalDate to) {}

    private record NodeValue(FlowNode node, BigDecimal value) {}

    /** Mutable per-category tally (positive magnitude) with a transaction count. */
    private static final class Agg {
        private final Category category;
        private BigDecimal sum = BigDecimal.ZERO;
        private int count = 0;

        Agg(Category category) {
            this.category = category;
        }

        void add(BigDecimal magnitude) {
            sum = sum.add(magnitude);
            count++;
        }
    }
}
