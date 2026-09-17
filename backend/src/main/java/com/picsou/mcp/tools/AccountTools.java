package com.picsou.mcp.tools;

import com.picsou.dto.AccountRequest;
import com.picsou.dto.AccountResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.SnapshotRequest;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.service.AccountService;
import com.picsou.service.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * MCP tools over a member's accounts, holdings, and balance history. Every method resolves the
 * authenticated key owner's member via {@link UserContext} and delegates to the already
 * member-scoped {@link AccountService}; an access-key can therefore only ever touch its own
 * owner's accounts. Writes are restricted to <em>manual</em> accounts — {@link #createManualAccount}
 * always sets {@code isManual=true}; synced bank/broker/crypto accounts are managed by their sync.
 */
@Component
public class AccountTools {

    private final AccountService accountService;
    private final UserContext userContext;

    public AccountTools(AccountService accountService, UserContext userContext) {
        this.accountService = accountService;
        this.userContext = userContext;
    }

    @Tool(name = "list_accounts",
        description = "List all of the authenticated member's accounts with their current balances and metadata.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public List<AccountResponse> listAccounts() {
        return accountService.findAll(userContext.currentMemberId());
    }

    @Tool(name = "get_account", description = "Get a single account of the authenticated member by its id.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public AccountResponse getAccount(
        @ToolParam(description = "The account id") Long accountId) {
        return accountService.findById(accountId, userContext.currentMemberId());
    }

    @Tool(name = "get_account_holdings",
        description = "List the holdings (positions) of an investment/crypto account, with quantity, price and PnL.")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public List<HoldingResponse> getAccountHoldings(
        @ToolParam(description = "The account id") Long accountId) {
        return accountService.getHoldings(accountId, userContext.currentMemberId());
    }

    @Tool(name = "get_account_balance_history",
        description = "Get an account's recorded balance snapshots, optionally bounded by a from/to date (ISO yyyy-MM-dd).")
    @RequiresScope(Scopes.ACCOUNTS_READ)
    public List<BalanceSnapshot> getAccountBalanceHistory(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "Earliest date (inclusive), ISO yyyy-MM-dd; omit for no lower bound", required = false) LocalDate from,
        @ToolParam(description = "Latest date (inclusive), ISO yyyy-MM-dd; omit for no upper bound", required = false) LocalDate to) {
        return accountService.getHistory(accountId, userContext.currentMemberId(), from, to);
    }

    @Tool(name = "create_manual_account",
        description = "Create a new MANUAL account for the authenticated member (a non-synced account whose "
            + "balance you maintain yourself). Returns the created account.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public AccountResponse createManualAccount(
        @ToolParam(description = "Account name") String name,
        @ToolParam(description = "Account type: CHECKING, SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL, PEA, COMPTE_TITRES, CRYPTO, REAL_ESTATE, LOAN, EMPLOYEE_SAVINGS or OTHER") AccountType type,
        @ToolParam(description = "ISO currency code, e.g. EUR, USD") String currency,
        @ToolParam(description = "Opening balance; defaults to 0 when omitted", required = false) BigDecimal currentBalance,
        @ToolParam(description = "Optional hex colour like #1a2b3c", required = false) String color,
        @ToolParam(description = "Optional ticker for single-asset accounts", required = false) String ticker) {
        AccountRequest req = new AccountRequest(name, type, null, currency, currentBalance, true, color, ticker, null, null, null);
        return accountService.create(req, userContext.currentMember());
    }

    @Tool(name = "update_account",
        description = "Update an existing account of the authenticated member. Returns the updated account.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public AccountResponse updateAccount(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "Account name") String name,
        @ToolParam(description = "Account type: CHECKING, SAVINGS, LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL, PEA, COMPTE_TITRES, CRYPTO, REAL_ESTATE, LOAN, EMPLOYEE_SAVINGS or OTHER") AccountType type,
        @ToolParam(description = "ISO currency code, e.g. EUR, USD") String currency,
        @ToolParam(description = "Balance; omit to leave the service default", required = false) BigDecimal currentBalance,
        @ToolParam(description = "Optional hex colour like #1a2b3c", required = false) String color,
        @ToolParam(description = "Optional ticker for single-asset accounts", required = false) String ticker) {
        AccountRequest req = new AccountRequest(name, type, null, currency, currentBalance, true, color, ticker, null, null, null);
        return accountService.update(accountId, req, userContext.currentMemberId());
    }

    @Tool(name = "delete_account", description = "Delete (soft-delete) an account of the authenticated member.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public String deleteAccount(
        @ToolParam(description = "The account id") Long accountId) {
        accountService.delete(accountId, userContext.currentMemberId());
        return "Deleted account " + accountId;
    }

    @Tool(name = "add_balance_snapshot",
        description = "Record a manual balance snapshot for an account on a given date (ISO yyyy-MM-dd).")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public BalanceSnapshot addBalanceSnapshot(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "The balance to record") BigDecimal balance,
        @ToolParam(description = "Snapshot date, ISO yyyy-MM-dd") LocalDate date) {
        return accountService.addManualSnapshot(accountId, userContext.currentMemberId(), new SnapshotRequest(balance, date));
    }

    @Tool(name = "upsert_holding",
        description = "Create or update a holding (position) on an account by ticker, then return the account's holdings.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public List<HoldingResponse> upsertHolding(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "Ticker symbol, e.g. AAPL or BTC") String ticker,
        @ToolParam(description = "Display name of the asset") String name,
        @ToolParam(description = "Quantity held") BigDecimal quantity,
        @ToolParam(description = "Current price per unit in EUR") BigDecimal currentPriceEur) {
        Long memberId = userContext.currentMemberId();
        accountService.upsertHolding(accountId, memberId, ticker, name, quantity, currentPriceEur);
        return accountService.getHoldings(accountId, memberId);
    }

    @Tool(name = "delete_holding", description = "Delete a holding from an account by its ticker.")
    @RequiresScope(Scopes.ACCOUNTS_WRITE)
    public String deleteHolding(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "Ticker symbol to remove") String ticker) {
        accountService.deleteHolding(accountId, userContext.currentMemberId(), ticker);
        return "Deleted holding " + ticker + " from account " + accountId;
    }
}
