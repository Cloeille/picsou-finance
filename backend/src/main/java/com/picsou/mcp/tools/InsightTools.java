package com.picsou.mcp.tools;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.DashboardResponse;
import com.picsou.dto.FamilyDashboardResponse;
import com.picsou.dto.PnlResponse;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.AccountService;
import com.picsou.service.DashboardService;
import com.picsou.service.FamilyViewService;
import com.picsou.service.HistoryService;
import com.picsou.service.PriceService;
import com.picsou.service.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only "insight" MCP tools: the net-worth dashboard, net-worth history, profit-and-loss, the
 * family (shared) dashboard, and a global asset price. Every member-scoped call resolves the caller
 * via {@link UserContext} and delegates to the relevant service. The price lookup is global market
 * data and touches no member's data, so it carries the narrow {@code prices:read} scope.
 *
 * <p>History and PnL accept an optional {@code accountIds}: when the caller omits it the tool
 * defaults to <em>all</em> of the member's accounts (their whole net worth, a pocket counted through
 * its listed wallet), which the REST layer always supplies explicitly from the selected accounts in the UI.
 */
@Component
public class InsightTools {

    /** Default look-back window for net-worth history when the caller does not specify one. */
    private static final int DEFAULT_HISTORY_MONTHS = 12;

    private final DashboardService dashboardService;
    private final HistoryService historyService;
    private final PriceService priceService;
    private final FamilyViewService familyViewService;
    private final AccountService accountService;
    private final UserContext userContext;

    public InsightTools(DashboardService dashboardService,
                        HistoryService historyService,
                        PriceService priceService,
                        FamilyViewService familyViewService,
                        AccountService accountService,
                        UserContext userContext) {
        this.dashboardService = dashboardService;
        this.historyService = historyService;
        this.priceService = priceService;
        this.familyViewService = familyViewService;
        this.accountService = accountService;
        this.userContext = userContext;
    }

    @Tool(name = "get_dashboard",
        description = "Get the authenticated member's net-worth dashboard: totals, assets vs liabilities, "
            + "allocation and per-account breakdown. Optional range filters the included history.")
    @RequiresScope(Scopes.DASHBOARD_READ)
    public DashboardResponse getDashboard(
        @ToolParam(description = "Optional range filter, e.g. 1M, 3M, 6M, 1Y, ALL; omit for the default", required = false) String range) {
        return dashboardService.getDashboard(userContext.currentMemberId(), range);
    }

    @Tool(name = "get_net_worth_history",
        description = "Get the member's net-worth over time as monthly points. accountIds defaults to ALL of the "
            + "member's accounts (a pocket whose wallet is listed counts through the wallet); months defaults to 12.")
    @RequiresScope(Scopes.DASHBOARD_READ)
    public List<DashboardResponse.NetWorthPoint> getNetWorthHistory(
        @ToolParam(description = "Account ids to include; omit for all of the member's accounts", required = false) List<Long> accountIds,
        @ToolParam(description = "Number of months of history; defaults to 12", required = false) Integer months) {
        Long memberId = userContext.currentMemberId();
        int window = months != null ? months : DEFAULT_HISTORY_MONTHS;
        return historyService.buildHistory(resolveAccountIds(accountIds, memberId), window, memberId);
    }

    @Tool(name = "get_profit_and_loss",
        description = "Get profit-and-loss across the member's investment accounts (total, invested, PnL and "
            + "optionally the change since a 'from' date). accountIds defaults to ALL of the member's accounts "
            + "(a pocket whose wallet is listed counts through the wallet).")
    @RequiresScope(Scopes.DASHBOARD_READ)
    public PnlResponse getProfitAndLoss(
        @ToolParam(description = "Account ids to include; omit for all of the member's accounts", required = false) List<Long> accountIds,
        @ToolParam(description = "Optional start date (ISO yyyy-MM-dd) for the range PnL; omit for inception-to-date", required = false) LocalDate fromDate) {
        Long memberId = userContext.currentMemberId();
        return historyService.buildPnl(resolveAccountIds(accountIds, memberId), memberId, fromDate);
    }

    @Tool(name = "get_family_dashboard",
        description = "Get the shared family dashboard visible to the authenticated member: aggregated net worth "
            + "across members who share with them. Only data the member is permitted to see is returned.")
    @RequiresScope(Scopes.FAMILY_READ)
    public FamilyDashboardResponse getFamilyDashboard() {
        return familyViewService.getFamilyDashboard(userContext.currentMemberId());
    }

    @Tool(name = "get_price",
        description = "Get the latest known price in EUR for an asset ticker (e.g. AAPL, BTC). Global market data, "
            + "not member-specific.")
    @RequiresScope(Scopes.PRICES_READ)
    public BigDecimal getPrice(
        @ToolParam(description = "Asset ticker, e.g. AAPL or BTC") String ticker) {
        return priceService.getPriceEur(ticker);
    }

    /**
     * Use the caller's explicit account ids, or fall back to every account the member owns.
     *
     * <p>The fallback leaves out a pocket whose parent wallet is in the list: the parent's balance
     * already carries it, and HistoryService sums whatever ids it is given, so the dashboard
     * drops pockets before it calls in. Explicit ids are taken as asked.
     */
    private List<Long> resolveAccountIds(List<Long> accountIds, Long memberId) {
        if (accountIds != null && !accountIds.isEmpty()) {
            return accountIds;
        }
        List<AccountResponse> accounts = accountService.findAll(memberId);
        Set<Long> listedIds = accounts.stream().map(AccountResponse::id).collect(Collectors.toSet());
        return accounts.stream()
            .filter(a -> a.parentAccountId() == null || !listedIds.contains(a.parentAccountId()))
            .map(AccountResponse::id)
            .toList();
    }
}
