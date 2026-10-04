package com.picsou.mcp.tools;

import com.picsou.dto.BudgetRequest;
import com.picsou.dto.BudgetResponse;
import com.picsou.dto.CashflowFlowResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.CashflowResponse;
import com.picsou.dto.CategorizationRuleRequest;
import com.picsou.dto.CategorizationRuleResponse;
import com.picsou.dto.CategoryRequest;
import com.picsou.dto.CategoryResponse;
import com.picsou.dto.RecurringActivityResponse;
import com.picsou.dto.RecurringOccurrenceResponse;
import com.picsou.dto.RecurringSeriesRequest;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.dto.RulePreviewRequest;
import com.picsou.dto.SpendingByCategoryResponse;
import com.picsou.dto.SpendingDetailResponse;
import com.picsou.dto.TransactionResponse;
import com.picsou.exception.ResourceNotFoundException;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.ScopeEnforcementAspect;
import com.picsou.mcp.Scopes;
import com.picsou.model.CategoryKind;
import com.picsou.model.RecurringCadence;
import com.picsou.model.RecurringStatus;
import com.picsou.model.RuleMatchType;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetService;
import com.picsou.service.budget.CashflowFlowService;
import com.picsou.service.budget.CashflowService;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import com.picsou.service.budget.RecurringDetectionService;
import com.picsou.service.budget.RecurringSeriesService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * MCP tools over the Budget module: managed categories, categorization rules, the budgeted
 * transaction view, recurring series (read, triage and detection), envelopes, period-selectable
 * spending and cashflow reads, plus a composed overview.
 * Every method resolves the authenticated key owner's member via {@link UserContext} and
 * delegates to the already member-scoped budget services — a key can therefore only ever
 * read or change its own owner's budget data. Rules are member-local; envelopes affect only
 * the node they target, never their subtree (see {@code docs/features/budget.md}).
 */
@Component
public class BudgetTools {

    private final CategoryService categoryService;
    private final CategorizationService categorizationService;
    private final BudgetService budgetService;
    private final RecurringSeriesService recurringSeriesService;
    private final RecurringDetectionService recurringDetectionService;
    private final CashflowService cashflowService;
    private final CashflowFlowService cashflowFlowService;
    private final TransactionRepository transactionRepository;
    private final UserContext userContext;
    private final Validator validator;

    public BudgetTools(CategoryService categoryService,
                       CategorizationService categorizationService,
                       BudgetService budgetService,
                       RecurringSeriesService recurringSeriesService,
                       RecurringDetectionService recurringDetectionService,
                       CashflowService cashflowService,
                       CashflowFlowService cashflowFlowService,
                       TransactionRepository transactionRepository,
                       UserContext userContext,
                       Validator validator) {
        this.categoryService = categoryService;
        this.categorizationService = categorizationService;
        this.budgetService = budgetService;
        this.recurringSeriesService = recurringSeriesService;
        this.recurringDetectionService = recurringDetectionService;
        this.cashflowService = cashflowService;
        this.cashflowFlowService = cashflowFlowService;
        this.transactionRepository = transactionRepository;
        this.userContext = userContext;
        this.validator = validator;
    }

    // ─── Categories ─────────────────────────────────────────────────────────

    @Tool(name = "list_budget_categories",
        description = "List the authenticated member's managed budget categories (including seeded defaults), tree-shaped via parentId.")
    @RequiresScope(Scopes.BUDGET_CATEGORIES_READ)
    public List<CategoryResponse> listBudgetCategories() {
        return categoryService.findAll(userContext.currentMemberId());
    }

    @Tool(name = "get_budget_category", description = "Get a single budget category of the authenticated member by its id.")
    @RequiresScope(Scopes.BUDGET_CATEGORIES_READ)
    public CategoryResponse getBudgetCategory(@ToolParam(description = "The category id") Long categoryId) {
        return listBudgetCategories().stream()
            .filter(c -> c.id().equals(categoryId))
            .findFirst()
            .orElseThrow(() -> ResourceNotFoundException.category(categoryId));
    }

    @Tool(name = "create_budget_category",
        description = "Create a budget category for the authenticated member. kind is INCOME, EXPENSE or TRANSFER. "
            + "parentId (optional) nests it one level under an existing root category of the same kind.")
    @RequiresScope(Scopes.BUDGET_CATEGORIES_WRITE)
    public CategoryResponse createBudgetCategory(
        @ToolParam(description = "Category name") String name,
        @ToolParam(description = "INCOME, EXPENSE or TRANSFER") CategoryKind kind,
        @ToolParam(description = "Optional hex colour like #1a2b3c", required = false) String color,
        @ToolParam(description = "Optional lucide-react icon name", required = false) String icon,
        @ToolParam(description = "Optional parent category id for a sub-category", required = false) Long parentId) {
        CategoryRequest req = new CategoryRequest(name, kind, color, icon, null, parentId);
        return categoryService.create(req, userContext.currentMemberId());
    }

    @Tool(name = "update_budget_category", description = "Update an existing budget category of the authenticated member.")
    @RequiresScope(Scopes.BUDGET_CATEGORIES_WRITE)
    public CategoryResponse updateBudgetCategory(
        @ToolParam(description = "The category id") Long categoryId,
        @ToolParam(description = "Category name") String name,
        @ToolParam(description = "INCOME, EXPENSE or TRANSFER") CategoryKind kind,
        @ToolParam(description = "Optional hex colour like #1a2b3c", required = false) String color,
        @ToolParam(description = "Optional lucide-react icon name", required = false) String icon,
        @ToolParam(description = "Optional parent category id for a sub-category", required = false) Long parentId) {
        CategoryRequest req = new CategoryRequest(name, kind, color, icon, null, parentId);
        return categoryService.update(categoryId, req, userContext.currentMemberId());
    }

    @Tool(name = "delete_budget_category",
        description = "Archive (soft-delete) a budget category of the authenticated member; archiving a parent cascades to its sub-categories.")
    @RequiresScope(Scopes.BUDGET_CATEGORIES_WRITE)
    public String deleteBudgetCategory(@ToolParam(description = "The category id") Long categoryId) {
        categoryService.archive(categoryId, userContext.currentMemberId());
        return "Archived category " + categoryId;
    }

    // ─── Rules ──────────────────────────────────────────────────────────────

    @Tool(name = "list_budget_rules",
        description = "List the authenticated member's categorization rules (USER and learned AUTO ones), highest priority first.")
    @RequiresScope(Scopes.BUDGET_RULES_READ)
    public List<CategorizationRuleResponse> listBudgetRules() {
        return categorizationService.findAllRules(userContext.currentMemberId());
    }

    @Tool(name = "get_budget_rule", description = "Get a single categorization rule of the authenticated member by its id.")
    @RequiresScope(Scopes.BUDGET_RULES_READ)
    public CategorizationRuleResponse getBudgetRule(@ToolParam(description = "The rule id") Long ruleId) {
        return listBudgetRules().stream()
            .filter(r -> r.id().equals(ruleId))
            .findFirst()
            .orElseThrow(() -> ResourceNotFoundException.rule(ruleId));
    }

    @Tool(name = "create_budget_rule",
        description = "Create a categorization rule for the authenticated member. matchType is COUNTERPARTY, "
            + "KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY; higher priority wins on conflict.")
    @RequiresScope(Scopes.BUDGET_RULES_WRITE)
    public CategorizationRuleResponse createBudgetRule(
        @ToolParam(description = "COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY") RuleMatchType matchType,
        @ToolParam(description = "The pattern to match against counterparty/description/merchant label") String pattern,
        @ToolParam(description = "Target category id") Long categoryId,
        @ToolParam(description = "Priority; higher wins on conflict, defaults to 0", required = false) Integer priority) {
        CategorizationRuleRequest req = new CategorizationRuleRequest(matchType, pattern, categoryId, priority);
        return categorizationService.createRule(req, userContext.currentMemberId());
    }

    @Tool(name = "update_budget_rule", description = "Update an existing categorization rule of the authenticated member.")
    @RequiresScope(Scopes.BUDGET_RULES_WRITE)
    public CategorizationRuleResponse updateBudgetRule(
        @ToolParam(description = "The rule id") Long ruleId,
        @ToolParam(description = "COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY") RuleMatchType matchType,
        @ToolParam(description = "The pattern to match against counterparty/description/merchant label") String pattern,
        @ToolParam(description = "Target category id") Long categoryId,
        @ToolParam(description = "Priority; higher wins on conflict", required = false) Integer priority) {
        CategorizationRuleRequest req = new CategorizationRuleRequest(matchType, pattern, categoryId, priority);
        return categorizationService.updateRule(ruleId, req, userContext.currentMemberId());
    }

    @Tool(name = "delete_budget_rule", description = "Delete a categorization rule of the authenticated member.")
    @RequiresScope(Scopes.BUDGET_RULES_WRITE)
    public String deleteBudgetRule(@ToolParam(description = "The rule id") Long ruleId) {
        categorizationService.deleteRule(ruleId, userContext.currentMemberId());
        return "Deleted rule " + ruleId;
    }

    @Tool(name = "apply_rule_to_transactions",
        description = "Re-run the authenticated member's full categorization pipeline (rules, then the offline "
            + "merchant knowledge base) over every still-uncategorized transaction. Never overrides an existing "
            + "category. Returns how many transactions were assigned a category.")
    @RequiresScope(Scopes.BUDGET_RULES_WRITE)
    public String applyRuleToTransactions() {
        int assigned = categorizationService.recategorizeUncategorized(userContext.currentMemberId());
        return "Categorized " + assigned + " transaction(s)";
    }

    @Tool(name = "preview_budget_rule",
        description = "Dry run of a categorization rule before create_budget_rule: returns how many of the authenticated "
            + "member's transactions it would categorize (uncategorized ones or ones not categorized by hand) and up to "
            + "200 of them. Requires budget:transactions-read because any pattern is a search over transaction "
            + "counterparty, description and merchant label. Writes nothing: no rule is saved and no transaction changes.")
    @RequiresScope(Scopes.BUDGET_TRANSACTIONS_READ)
    public CategorizationService.RulePreviewResult previewBudgetRule(
        @ToolParam(description = "COUNTERPARTY, KEYWORD, KEYWORDS_ALL or KEYWORDS_ANY") RuleMatchType matchType,
        @ToolParam(description = "The pattern to match against counterparty/description/merchant label") String pattern) {
        RulePreviewRequest req = validated(new RulePreviewRequest(matchType, pattern));
        return categorizationService.previewRule(req.matchType(), req.pattern(), userContext.currentMemberId());
    }

    // ─── Transactions (budgeted view) ──────────────────────────────────────

    @Tool(name = "list_budget_transactions",
        description = "List the authenticated member's transactions in a date range, optionally filtered by "
            + "category (omit for all categories) or restricted to only-uncategorized ones. Includes merchant "
            + "label/brand and the current category.")
    @RequiresScope(Scopes.BUDGET_TRANSACTIONS_READ)
    public List<TransactionResponse> listBudgetTransactions(
        @ToolParam(description = "Earliest date (inclusive), ISO yyyy-MM-dd") LocalDate from,
        @ToolParam(description = "Latest date (inclusive), ISO yyyy-MM-dd") LocalDate to,
        @ToolParam(description = "Restrict to this category id; omit for every category", required = false) Long categoryId,
        @ToolParam(description = "When true, ignore categoryId and return only uncategorized transactions", required = false) Boolean uncategorizedOnly) {
        Long memberId = userContext.currentMemberId();
        if (Boolean.TRUE.equals(uncategorizedOnly)) {
            return categorizationService.findUncategorized(memberId).stream()
                .filter(t -> !t.date().isBefore(from) && !t.date().isAfter(to))
                .toList();
        }
        return transactionRepository.searchByMember(memberId, from, to, null, categoryId).stream()
            .map(TransactionResponse::from)
            .toList();
    }

    @Tool(name = "update_budget_transaction",
        description = "Set or change a transaction's category. Optionally learn a rule from this assignment "
            + "(createRule) and retro-apply it to the member's other matching uncategorized transactions.")
    @RequiresScope(Scopes.BUDGET_TRANSACTIONS_WRITE)
    public String updateBudgetTransaction(
        @ToolParam(description = "The transaction id") Long transactionId,
        @ToolParam(description = "The category id to assign") Long categoryId,
        @ToolParam(description = "Whether to learn a rule from this assignment", required = false) Boolean createRule) {
        categorizationService.categorize(
            transactionId, categoryId, Boolean.TRUE.equals(createRule), null, null, List.of(),
            userContext.currentMemberId());
        return "Categorized transaction " + transactionId;
    }

    // ─── Recurring ──────────────────────────────────────────────────────────

    @Tool(name = "list_recurring_series",
        description = "List the authenticated member's recurring series (subscriptions/direct debits/salaries), "
            + "optionally filtered by status (SUGGESTED, CONFIRMED, IGNORED).")
    @RequiresScope(Scopes.BUDGET_RECURRING_READ)
    public List<RecurringSeriesResponse> listRecurringSeries(
        @ToolParam(description = "SUGGESTED, CONFIRMED or IGNORED; omit for all", required = false) RecurringStatus status) {
        return recurringSeriesService.findAll(userContext.currentMemberId(), status, LocalDate.now());
    }

    @Tool(name = "get_recurring_series",
        description = "Get a single recurring series of the authenticated member by its id, with detection confidence and runtime status.")
    @RequiresScope(Scopes.BUDGET_RECURRING_READ)
    public RecurringSeriesResponse getRecurringSeries(@ToolParam(description = "The recurring series id") Long seriesId) {
        return recurringSeriesService.findAll(userContext.currentMemberId(), null, LocalDate.now()).stream()
            .filter(s -> s.id().equals(seriesId))
            .findFirst()
            .orElseThrow(() -> ResourceNotFoundException.recurringSeries(seriesId));
    }

    @Tool(name = "get_recurring_activity",
        description = "The authenticated member's recurring 'what changed' feed, newest first: series the detector silently "
            + "auto-confirmed or whose price stepped within the last 60 days. Each entry can be reversed with "
            + "undo_recurring_series_change.")
    @RequiresScope(Scopes.BUDGET_RECURRING_READ)
    public List<RecurringActivityResponse> getRecurringActivity() {
        return recurringSeriesService.activity(userContext.currentMemberId(), LocalDate.now());
    }

    @Tool(name = "get_recurring_calendar",
        description = "Projected charges of the authenticated member's confirmed recurring series from today through "
            + "the next horizonDays days, soonest first. Credit-card payments due (card account name, amount due, reward "
            + "points) are included only when the key also holds accounts:read.")
    @RequiresScope(Scopes.BUDGET_RECURRING_READ)
    public List<RecurringOccurrenceResponse> getRecurringCalendar(
        @ToolParam(description = "How many days ahead to project; defaults to 60", required = false) Integer horizonDays) {
        List<RecurringOccurrenceResponse> upcoming = recurringSeriesService.upcoming(
            userContext.currentMemberId(), LocalDate.now(), horizonDays != null ? horizonDays : 60);
        if (ScopeEnforcementAspect.isGranted(Scopes.ACCOUNTS_READ)) {
            return upcoming;
        }
        return upcoming.stream().filter(o -> !o.creditCardPayment()).toList();
    }

    @Tool(name = "confirm_recurring_series",
        description = "Mark one of the authenticated member's recurring series as CONFIRMED, so it appears in the "
            + "calendar and upcoming subscriptions. Idempotent. Returns the updated series.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public RecurringSeriesResponse confirmRecurringSeries(@ToolParam(description = "The recurring series id") Long seriesId) {
        return recurringSeriesService.confirm(seriesId, userContext.currentMemberId());
    }

    @Tool(name = "ignore_recurring_series",
        description = "Mark one of the authenticated member's recurring series as IGNORED. The series is kept, not deleted, "
            + "so detection never suggests it again. Idempotent; confirm_recurring_series reverses it. Returns the updated series.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public RecurringSeriesResponse ignoreRecurringSeries(@ToolParam(description = "The recurring series id") Long seriesId) {
        return recurringSeriesService.ignore(seriesId, userContext.currentMemberId());
    }

    @Tool(name = "undo_recurring_series_change",
        description = "Reverse a get_recurring_activity entry. A price change is acknowledged: the new amount stays and "
            + "the alert is cleared. A silent auto-confirm is rejected: the series becomes IGNORED so detection will "
            + "not re-confirm it. A series with neither is returned unchanged. Returns the updated series.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public RecurringSeriesResponse undoRecurringSeriesChange(@ToolParam(description = "The recurring series id") Long seriesId) {
        return recurringSeriesService.undo(seriesId, userContext.currentMemberId(), LocalDate.now());
    }

    @Tool(name = "create_recurring_series",
        description = "Declare a recurring series the detector missed for the authenticated member. It starts CONFIRMED. "
            + "expectedAmount is signed like a transaction: negative for a debit, positive for income. Returns the created series.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public RecurringSeriesResponse createRecurringSeries(
        @ToolParam(description = "Display label, at most 255 characters") String label,
        @ToolParam(description = "Signed expected amount, negative for a debit") BigDecimal expectedAmount,
        @ToolParam(description = "WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY or YEARLY") RecurringCadence cadence,
        @ToolParam(description = "Optional counterparty, at most 255 characters", required = false) String counterparty,
        @ToolParam(description = "Optional next due date, ISO yyyy-MM-dd", required = false) LocalDate nextDueDate,
        @ToolParam(description = "Optional category id of the member", required = false) Long categoryId) {
        RecurringSeriesRequest req = validated(
            new RecurringSeriesRequest(label, counterparty, expectedAmount, cadence, nextDueDate, categoryId));
        return recurringSeriesService.create(req, userContext.currentMemberId());
    }

    @Tool(name = "update_recurring_series",
        description = "Replace the label, counterparty, amount, cadence, next due date and category of one of the "
            + "authenticated member's recurring series. Omitted optional fields are cleared. Status is unchanged. "
            + "Returns the updated series.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public RecurringSeriesResponse updateRecurringSeries(
        @ToolParam(description = "The recurring series id") Long seriesId,
        @ToolParam(description = "Display label, at most 255 characters") String label,
        @ToolParam(description = "Signed expected amount, negative for a debit") BigDecimal expectedAmount,
        @ToolParam(description = "WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY or YEARLY") RecurringCadence cadence,
        @ToolParam(description = "Optional counterparty, at most 255 characters", required = false) String counterparty,
        @ToolParam(description = "Optional next due date, ISO yyyy-MM-dd", required = false) LocalDate nextDueDate,
        @ToolParam(description = "Optional category id of the member", required = false) Long categoryId) {
        RecurringSeriesRequest req = validated(
            new RecurringSeriesRequest(label, counterparty, expectedAmount, cadence, nextDueDate, categoryId));
        return recurringSeriesService.update(seriesId, req, userContext.currentMemberId());
    }

    @Tool(name = "delete_recurring_series",
        description = "Permanently delete one of the authenticated member's recurring series. Detection may suggest it "
            + "again from future transactions; use ignore_recurring_series to dismiss it for good.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public String deleteRecurringSeries(@ToolParam(description = "The recurring series id") Long seriesId) {
        recurringSeriesService.delete(seriesId, userContext.currentMemberId());
        return "Deleted recurring series " + seriesId;
    }

    @Tool(name = "detect_recurring_series",
        description = "Re-run recurring detection over the authenticated member's transactions of the last 400 days. "
            + "Creates new SUGGESTED series, refreshes amounts and due dates of existing ones, and may auto-confirm a "
            + "high-confidence suggestion. Never changes a CONFIRMED or IGNORED decision. Returns how many series were "
            + "created or refreshed.")
    @RequiresScope(Scopes.BUDGET_RECURRING_WRITE)
    public String detectRecurringSeries() {
        int detected = recurringDetectionService.detect(userContext.currentMemberId(), LocalDate.now());
        return "Detected or refreshed " + detected + " recurring series";
    }

    // ─── Envelopes ──────────────────────────────────────────────────────────

    @Tool(name = "list_budget_envelopes",
        description = "List the authenticated member's budget envelopes with current-cycle progress; a parent envelope's spent rolls up its subtree.")
    @RequiresScope(Scopes.BUDGET_ENVELOPES_READ)
    public List<BudgetResponse> listBudgetEnvelopes() {
        return budgetService.findAll(userContext.currentMemberId());
    }

    @Tool(name = "get_budget_envelope", description = "Get a single budget envelope of the authenticated member by its id.")
    @RequiresScope(Scopes.BUDGET_ENVELOPES_READ)
    public BudgetResponse getBudgetEnvelope(@ToolParam(description = "The envelope (budget) id") Long envelopeId) {
        return listBudgetEnvelopes().stream()
            .filter(b -> b.id().equals(envelopeId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Budget not found"));
    }

    @Tool(name = "create_budget_envelope",
        description = "Create a monthly budget envelope on one of the authenticated member's categories. "
            + "A parent and its children can never both hold an envelope (double-counting guard).")
    @RequiresScope(Scopes.BUDGET_ENVELOPES_WRITE)
    public BudgetResponse createBudgetEnvelope(
        @ToolParam(description = "The category id this envelope caps") Long categoryId,
        @ToolParam(description = "Monthly limit amount") BigDecimal monthlyLimit) {
        return budgetService.create(new BudgetRequest(categoryId, monthlyLimit), userContext.currentMemberId());
    }

    @Tool(name = "update_budget_envelope", description = "Update an existing budget envelope's category or monthly limit.")
    @RequiresScope(Scopes.BUDGET_ENVELOPES_WRITE)
    public BudgetResponse updateBudgetEnvelope(
        @ToolParam(description = "The envelope (budget) id") Long envelopeId,
        @ToolParam(description = "The category id this envelope caps") Long categoryId,
        @ToolParam(description = "Monthly limit amount") BigDecimal monthlyLimit) {
        return budgetService.update(envelopeId, new BudgetRequest(categoryId, monthlyLimit), userContext.currentMemberId());
    }

    @Tool(name = "delete_budget_envelope", description = "Delete a budget envelope of the authenticated member.")
    @RequiresScope(Scopes.BUDGET_ENVELOPES_WRITE)
    public String deleteBudgetEnvelope(@ToolParam(description = "The envelope (budget) id") Long envelopeId) {
        budgetService.delete(envelopeId, userContext.currentMemberId());
        return "Deleted envelope " + envelopeId;
    }

    /**
     * The underlying {@code Budget} model only carries a monthly money limit (no percentage
     * allocation), so this is a thin convenience wrapper over {@link #updateBudgetEnvelope} that
     * changes just the limit and keeps the envelope's current category. See STATUS.md for the
     * percentage-allocation gap.
     */
    @Tool(name = "set_envelope_allocation",
        description = "Change a budget envelope's monthly money limit, keeping its category unchanged. "
            + "The Budget model supports a money limit only, not a percentage allocation.")
    @RequiresScope(Scopes.BUDGET_ENVELOPES_WRITE)
    public BudgetResponse setEnvelopeAllocation(
        @ToolParam(description = "The envelope (budget) id") Long envelopeId,
        @ToolParam(description = "New monthly limit amount") BigDecimal monthlyLimit) {
        BudgetResponse current = getBudgetEnvelope(envelopeId);
        return budgetService.update(envelopeId, new BudgetRequest(current.categoryId(), monthlyLimit),
            userContext.currentMemberId());
    }

    // ─── Dashboard ──────────────────────────────────────────────────────────

    /** The overview data behind the Budget UI's landing page: cashflow, top categories, upcoming subscriptions. */
    public record BudgetDashboard(
        CashflowResponse cashflow,
        List<SpendingByCategoryResponse.CategorySpend> topCategories,
        List<RecurringOccurrenceResponse> upcomingSubscriptions
    ) {}

    @Tool(name = "get_budget_dashboard",
        description = "Get the authenticated member's budget overview for the current pay cycle: income/expense/net "
            + "cashflow, the top spending categories, and subscriptions due in the next 30 days — the same data as the Overview page.")
    @RequiresScope(Scopes.BUDGET_DASHBOARD_READ)
    public BudgetDashboard getBudgetDashboard() {
        Long memberId = userContext.currentMemberId();
        LocalDate today = LocalDate.now();
        CashflowResponse cashflow = cashflowService.compute(memberId, CashflowPeriod.CYCLE, today);
        List<SpendingByCategoryResponse.CategorySpend> topCategories =
            cashflowFlowService.spendingByCategory(memberId, CashflowPeriod.CYCLE, today).categories().stream()
                .sorted((a, b) -> b.amount().compareTo(a.amount()))
                .limit(5)
                .toList();
        List<RecurringOccurrenceResponse> upcoming = recurringSeriesService.upcoming(memberId, today, 30);
        return new BudgetDashboard(cashflow, topCategories, upcoming);
    }

    @Tool(name = "get_spending_by_category",
        description = "The authenticated member's expenses ranked by category for a period: CYCLE (the pay cycle "
            + "containing anchor) or YTD (Jan 1 of anchor's year to anchor).")
    @RequiresScope(Scopes.BUDGET_DASHBOARD_READ)
    public SpendingByCategoryResponse getSpendingByCategory(
        @ToolParam(description = "CYCLE or YTD; defaults to CYCLE", required = false) CashflowPeriod period,
        @ToolParam(description = "Reference date, ISO yyyy-MM-dd; defaults to today", required = false) LocalDate anchor) {
        return cashflowFlowService.spendingByCategory(userContext.currentMemberId(), periodOrCycle(period), anchorOrToday(anchor));
    }

    @Tool(name = "get_spending_category_detail",
        description = "Drill into one of the authenticated member's categories for a period: total, per-sub-category "
            + "split and the transactions of the category and its sub-categories. Requires budget:transactions-read "
            + "because it returns transaction rows, not only aggregates.")
    @RequiresScope(Scopes.BUDGET_TRANSACTIONS_READ)
    public SpendingDetailResponse getSpendingCategoryDetail(
        @ToolParam(description = "The category id") Long categoryId,
        @ToolParam(description = "CYCLE or YTD; defaults to CYCLE", required = false) CashflowPeriod period,
        @ToolParam(description = "Reference date, ISO yyyy-MM-dd; defaults to today", required = false) LocalDate anchor) {
        return cashflowFlowService.categoryDetail(userContext.currentMemberId(), categoryId, periodOrCycle(period),
            anchorOrToday(anchor));
    }

    @Tool(name = "get_cashflow",
        description = "The authenticated member's income, expenses and net for a period: CYCLE (the pay cycle "
            + "containing anchor) or YTD (bucketed by pay cycle).")
    @RequiresScope(Scopes.BUDGET_DASHBOARD_READ)
    public CashflowResponse getCashflow(
        @ToolParam(description = "CYCLE or YTD; defaults to CYCLE", required = false) CashflowPeriod period,
        @ToolParam(description = "Reference date, ISO yyyy-MM-dd; defaults to today", required = false) LocalDate anchor) {
        return cashflowService.compute(userContext.currentMemberId(), periodOrCycle(period), anchorOrToday(anchor));
    }

    @Tool(name = "get_cashflow_flow",
        description = "The authenticated member's income → budget → expenses money-flow graph (Sankey nodes and links) "
            + "for a period: CYCLE or YTD.")
    @RequiresScope(Scopes.BUDGET_DASHBOARD_READ)
    public CashflowFlowResponse getCashflowFlow(
        @ToolParam(description = "CYCLE or YTD; defaults to CYCLE", required = false) CashflowPeriod period,
        @ToolParam(description = "Reference date, ISO yyyy-MM-dd; defaults to today", required = false) LocalDate anchor) {
        return cashflowFlowService.flow(userContext.currentMemberId(), periodOrCycle(period), anchorOrToday(anchor));
    }

    private static CashflowPeriod periodOrCycle(CashflowPeriod period) {
        return period != null ? period : CashflowPeriod.CYCLE;
    }

    private static LocalDate anchorOrToday(LocalDate anchor) {
        return anchor != null ? anchor : LocalDate.now();
    }

    /** The bean-validation the REST controllers get from {@code @Valid}, which tool arguments bypass. */
    private <T> T validated(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return request;
    }
}
