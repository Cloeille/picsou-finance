package com.picsou.mcp.tools;

import com.picsou.controller.RealEstateController.ValuationHistoryEntry;
import com.picsou.dto.AllocationResponse;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.dto.ExchangePositionResponse;
import com.picsou.dto.ProjectionResponse;
import com.picsou.dto.RealEstateSummaryResponse;
import com.picsou.dto.RealizedPnlResponse;
import com.picsou.dto.SavingsInterestProjection;
import com.picsou.dto.SavingsSuggestionResponse;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.AccountService;
import com.picsou.service.AllocationTargetService;
import com.picsou.service.CryptoExchangeSyncService;
import com.picsou.service.EssentialExpenseEstimator;
import com.picsou.service.LoanAmortizationService.LoanScheduleResponse;
import com.picsou.service.PortfolioDiversificationService;
import com.picsou.service.ProjectionService;
import com.picsou.service.PropertyValuationService;
import com.picsou.service.RealEstateSummaryService;
import com.picsou.service.RealizedPnlService;
import com.picsou.service.SavingsService;
import com.picsou.service.SecurityInsightService;
import com.picsou.service.UserContext;
import com.picsou.service.WealthPyramidService;
import com.picsou.service.budget.AllocationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only wealth-analysis MCP tools. Each one delegates to the service behind its REST
 * counterpart with the caller's member from {@link UserContext}, so it returns the same payload
 * and the same not-found error for another member's account. Nothing is computed here.
 *
 * <p>Whole-wealth judgements carry {@code analysis:read}. Tools that take an account id read one
 * account, like {@code get_account_holdings}, so they carry {@code accounts:read}. The security
 * insight is market reference data touching no member's data, like {@code get_price}, so it
 * carries {@code prices:read}.
 */
@Component
public class AnalysisTools {

    private static final int DEFAULT_PROJECTION_YEARS = 20;

    private final AllocationService allocationService;
    private final WealthPyramidService pyramidService;
    private final PortfolioDiversificationService diversificationService;
    private final ProjectionService projectionService;
    private final AllocationTargetService allocationTargetService;
    private final EssentialExpenseEstimator expenseEstimator;
    private final SavingsService savingsService;
    private final SecurityInsightService securityInsightService;
    private final RealEstateSummaryService realEstateSummaryService;
    private final PropertyValuationService propertyValuationService;
    private final AccountService accountService;
    private final RealizedPnlService realizedPnlService;
    private final CryptoExchangeSyncService cryptoExchangeSyncService;
    private final UserContext userContext;

    public AnalysisTools(AllocationService allocationService,
                         WealthPyramidService pyramidService,
                         PortfolioDiversificationService diversificationService,
                         ProjectionService projectionService,
                         AllocationTargetService allocationTargetService,
                         EssentialExpenseEstimator expenseEstimator,
                         SavingsService savingsService,
                         SecurityInsightService securityInsightService,
                         RealEstateSummaryService realEstateSummaryService,
                         PropertyValuationService propertyValuationService,
                         AccountService accountService,
                         RealizedPnlService realizedPnlService,
                         CryptoExchangeSyncService cryptoExchangeSyncService,
                         UserContext userContext) {
        this.allocationService = allocationService;
        this.pyramidService = pyramidService;
        this.diversificationService = diversificationService;
        this.projectionService = projectionService;
        this.allocationTargetService = allocationTargetService;
        this.expenseEstimator = expenseEstimator;
        this.savingsService = savingsService;
        this.securityInsightService = securityInsightService;
        this.realEstateSummaryService = realEstateSummaryService;
        this.propertyValuationService = propertyValuationService;
        this.accountService = accountService;
        this.realizedPnlService = realizedPnlService;
        this.cryptoExchangeSyncService = cryptoExchangeSyncService;
        this.userContext = userContext;
    }

    // ── analysis:read ─────────────────────────────────────────────────────────

    @Tool(name = "get_allocation", description = "Read-only. Current wealth by asset class, plus the "
        + "contributions that flowed into each class over the budget cycle (CYCLE) or year to date (YTD).")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public AllocationResponse getAllocation(
        @ToolParam(description = "CYCLE (current pay cycle, default) or YTD", required = false) CashflowPeriod period,
        @ToolParam(description = "Reference date, ISO yyyy-MM-dd; defaults to today", required = false) LocalDate anchor) {
        return allocationService.compute(userContext.currentMemberId(),
            period != null ? period : CashflowPeriod.CYCLE,
            anchor != null ? anchor : LocalDate.now());
    }

    @Tool(name = "get_wealth_pyramid", description = "Read-only. The five wealth-pyramid tiers, their "
        + "weights against the member's allocation targets, and the resulting score.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public WealthPyramidResponse getWealthPyramid() {
        return pyramidService.pyramid(userContext.currentMemberId());
    }

    @Tool(name = "get_portfolio_diversification", description = "Read-only. How the equity sleeve "
        + "spreads across sectors and regions; holdings with no known profile are listed as unclassified.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public DiversificationResponse getPortfolioDiversification() {
        return diversificationService.diversification(userContext.currentMemberId());
    }

    @Tool(name = "get_wealth_projection", description = "Read-only. The investable portfolio projected "
        + "forward under four return assumptions, fed by recurring investment plans. Excludes property and loans.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public ProjectionResponse getWealthProjection(
        @ToolParam(description = "Horizon in years, 1 to 40 (out-of-range values are clamped); defaults to 20",
            required = false) Integer years) {
        return projectionService.project(userContext.currentMemberId(),
            years != null ? years : DEFAULT_PROJECTION_YEARS);
    }

    @Tool(name = "get_allocation_targets", description = "Read-only. The member's target allocation "
        + "percentages, essential monthly expenses and safety-net months, or the shipped defaults when never set.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public AllocationTargetsResponse getAllocationTargets() {
        return allocationTargetService.get(userContext.currentMemberId());
    }

    @Tool(name = "get_essential_expense_estimate", description = "Read-only. What the member's own "
        + "transactions suggest they spend monthly on essentials. A suggestion; nothing is stored.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public EssentialExpenseEstimateResponse getEssentialExpenseEstimate() {
        return expenseEstimator.estimate(userContext.currentMemberId());
    }

    @Tool(name = "get_savings_suggestions", description = "Read-only. Suggested savings-book "
        + "(livret) interest configs for synced accounts that have none yet.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public List<SavingsSuggestionResponse> getSavingsSuggestions() {
        return savingsService.getSuggestions(userContext.currentMemberId());
    }

    @Tool(name = "get_real_estate_summary", description = "Read-only. Gross property value, outstanding "
        + "mortgage debt and net equity, weighted by the member's ownership shares.")
    @RequiresScope(Scopes.ANALYSIS_READ)
    public RealEstateSummaryResponse getRealEstateSummary() {
        return realEstateSummaryService.summarize(userContext.currentMemberId());
    }

    // ── accounts:read (one account) ───────────────────────────────────────────

    @Tool(name = "get_savings_interest", description = "Read-only. Year-to-date and full-year interest "
        + "projection for a savings account. Not found when the account has no savings config.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public SavingsInterestProjection getSavingsInterest(
        @ToolParam(description = "The account id") Long accountId) {
        return savingsService.getProjection(accountId, userContext.currentMemberId());
    }

    @Tool(name = "get_property_valuations", description = "Read-only. Stored value estimates for one "
        + "property account, newest first. Readable by co-owners.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public List<ValuationHistoryEntry> getPropertyValuations(
        @ToolParam(description = "The property (REAL_ESTATE) account id") Long accountId) {
        return propertyValuationService.history(accountId, userContext.currentMemberId()).stream()
            .map(ValuationHistoryEntry::from)
            .toList();
    }

    @Tool(name = "get_loan_summary", description = "Read-only. Amortization of a LOAN account: a summary "
        + "plus the monthly installment schedule. Not found when the loan details are not set.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public LoanScheduleResponse getLoanSummary(
        @ToolParam(description = "The loan account id") Long accountId) {
        return accountService.getLoanSummary(accountId, userContext.currentMemberId());
    }

    @Tool(name = "get_realized_pnl", description = "Read-only. Realized profit and loss of an "
        + "investment account per ticker, from its BUY/SELL transactions at average cost.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public RealizedPnlResponse getRealizedPnl(
        @ToolParam(description = "The account id") Long accountId) {
        return realizedPnlService.compute(accountId, userContext.currentMemberId());
    }

    @Tool(name = "get_exchange_positions", description = "Read-only. Per-product breakdown (spot, "
        + "staking, lending) of a crypto exchange account; empty for any other account.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public List<ExchangePositionResponse> getExchangePositions(
        @ToolParam(description = "The account id") Long accountId) {
        return cryptoExchangeSyncService.getPositions(accountId, userContext.currentMemberId());
    }

    // ── prices:read (reference data) ──────────────────────────────────────────

    @Tool(name = "get_security_insight", description = "Read-only. Asset type of a ticker and, for an "
        + "ETF, its composition (companies, countries, sectors). Market data, not member data.")
    @RequiresScope(Scopes.PRICES_READ)
    public SecurityInsightResponse getSecurityInsight(
        @ToolParam(description = "Ticker symbol, e.g. CW8.PA or AAPL") String ticker,
        @ToolParam(description = "Optional security name; helps identify the ETF issuer", required = false) String name) {
        return securityInsightService.getInsight(ticker, name);
    }
}
