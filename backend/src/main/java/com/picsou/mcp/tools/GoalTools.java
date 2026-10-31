package com.picsou.mcp.tools;

import com.picsou.dto.GoalMonthEntryResponse;
import com.picsou.dto.GoalProgressResponse;
import com.picsou.dto.GoalRequest;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.GoalService;
import com.picsou.service.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * MCP tools over a member's savings goals. Every method resolves the authenticated key owner's
 * member via {@link UserContext} and delegates to the already member-scoped {@link GoalService};
 * a key can therefore only ever read or change its own owner's goals.
 */
@Component
public class GoalTools {

    private final GoalService goalService;
    private final UserContext userContext;

    public GoalTools(GoalService goalService, UserContext userContext) {
        this.goalService = goalService;
        this.userContext = userContext;
    }

    @Tool(name = "list_goals",
        description = "List the authenticated member's savings goals with progress, monthly-needed and on-track status.")
    @RequiresScope(Scopes.GOALS_READ)
    public List<GoalProgressResponse> listGoals() {
        return goalService.findAll(userContext.currentMemberId());
    }

    @Tool(name = "get_goal", description = "Get a single savings goal of the authenticated member by its id, with progress.")
    @RequiresScope(Scopes.GOALS_READ)
    public GoalProgressResponse getGoal(
        @ToolParam(description = "The goal id") Long goalId) {
        return goalService.findById(goalId, userContext.currentMemberId());
    }

    @Tool(name = "get_goal_monthly_entries",
        description = "Get a goal's month-by-month entries: objective, actual (from snapshots), manual declaration and effective value.")
    @RequiresScope(Scopes.GOALS_READ)
    public List<GoalMonthEntryResponse> getGoalMonthlyEntries(
        @ToolParam(description = "The goal id") Long goalId) {
        return goalService.getMonthlyEntries(goalId, userContext.currentMemberId());
    }

    @Tool(name = "create_goal",
        description = "Create a savings goal for the authenticated member, tracked across one or more of the member's accounts.")
    @RequiresScope(Scopes.GOALS_WRITE)
    public GoalProgressResponse createGoal(
        @ToolParam(description = "Goal name") String name,
        @ToolParam(description = "Target amount to reach") BigDecimal targetAmount,
        @ToolParam(description = "Deadline, ISO yyyy-MM-dd; must be in the future") LocalDate deadline,
        @ToolParam(description = "Ids of the member's accounts whose balances count toward this goal (at least one)") List<Long> accountIds) {
        GoalRequest req = GoalRequest.savingsTarget(name, targetAmount, deadline, accountIds);
        return goalService.create(req, userContext.currentMember());
    }

    @Tool(name = "update_goal", description = "Update an existing savings goal of the authenticated member.")
    @RequiresScope(Scopes.GOALS_WRITE)
    public GoalProgressResponse updateGoal(
        @ToolParam(description = "The goal id") Long goalId,
        @ToolParam(description = "Goal name") String name,
        @ToolParam(description = "Target amount to reach") BigDecimal targetAmount,
        @ToolParam(description = "Deadline, ISO yyyy-MM-dd; must be in the future") LocalDate deadline,
        @ToolParam(description = "Ids of the member's accounts whose balances count toward this goal (at least one)") List<Long> accountIds) {
        GoalRequest req = GoalRequest.savingsTarget(name, targetAmount, deadline, accountIds);
        return goalService.update(goalId, req, userContext.currentMemberId());
    }

    @Tool(name = "create_recurring_investment",
        description = "Create a recurring monthly investment plan for the authenticated member "
            + "(e.g. 300 EUR into the PEA every month). Unlike a savings goal it has no target "
            + "amount and no deadline; it feeds the wealth projection.")
    @RequiresScope(Scopes.GOALS_WRITE)
    public GoalProgressResponse createRecurringInvestment(
        @ToolParam(description = "Plan name") String name,
        @ToolParam(description = "Amount invested every month") BigDecimal monthlyAmount,
        @ToolParam(description = "Id of the member's account the money goes into") Long accountId,
        @ToolParam(description = "Expected annual return in percent, optional") BigDecimal expectedReturn,
        @ToolParam(description = "First month, ISO yyyy-MM-dd; null means already running") LocalDate startDate,
        @ToolParam(description = "Last month, ISO yyyy-MM-dd; null means open-ended") LocalDate endDate) {
        GoalRequest req = GoalRequest.recurringInvestment(
            name, monthlyAmount, expectedReturn, startDate, endDate, accountId);
        return goalService.create(req, userContext.currentMember());
    }

    @Tool(name = "delete_goal", description = "Delete a savings goal of the authenticated member.")
    @RequiresScope(Scopes.GOALS_WRITE)
    public String deleteGoal(
        @ToolParam(description = "The goal id") Long goalId) {
        goalService.delete(goalId, userContext.currentMemberId());
        return "Deleted goal " + goalId;
    }

    @Tool(name = "set_goal_month_contribution",
        description = "Set the manual contribution declared for a goal in a given month (yearMonth is \"yyyy-MM\", e.g. 2026-03).")
    @RequiresScope(Scopes.GOALS_WRITE)
    public GoalMonthEntryResponse setGoalMonthContribution(
        @ToolParam(description = "The goal id") Long goalId,
        @ToolParam(description = "Target month as \"yyyy-MM\", e.g. 2026-03") String yearMonth,
        @ToolParam(description = "The contribution amount to record for that month") BigDecimal amount) {
        return goalService.setManualContribution(goalId, yearMonth, amount, userContext.currentMemberId());
    }
}
