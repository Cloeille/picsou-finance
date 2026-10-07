package com.picsou.mcp.tools;

import com.picsou.dto.TransactionRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.model.TransactionType;
import com.picsou.service.AccountService;
import com.picsou.service.ManualTransactionService;
import com.picsou.service.UserContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * MCP tools over a member's manual transactions. Reads go through {@link AccountService}, writes
 * through {@link ManualTransactionService} — both already scoped by the member resolved from the
 * authenticated access-key. Only manual transactions are writable; imported/synced transactions
 * are owned by their sync and are not exposed for mutation.
 */
@Component
public class TransactionTools {

    private final AccountService accountService;
    private final ManualTransactionService manualTransactionService;
    private final UserContext userContext;

    public TransactionTools(AccountService accountService,
                            ManualTransactionService manualTransactionService,
                            UserContext userContext) {
        this.accountService = accountService;
        this.manualTransactionService = manualTransactionService;
        this.userContext = userContext;
    }

    @Tool(name = "list_account_transactions",
        description = "List the transactions of one of the authenticated member's accounts.")
    @RequiresScope(Scopes.TRANSACTIONS_READ)
    public List<TransactionResponse> listAccountTransactions(
        @ToolParam(description = "The account id") Long accountId) {
        return accountService.getTransactions(accountId, userContext.currentMemberId());
    }

    @Tool(name = "add_transaction",
        description = "Add a manual transaction to an account. amount is signed (negative for outflow). "
            + "txType is one of DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND, FEE. For BUY/SELL you may also "
            + "pass ticker, name, quantity and pricePerUnit.")
    @RequiresScope(Scopes.TRANSACTIONS_WRITE)
    public TransactionResponse addTransaction(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "Transaction date, ISO yyyy-MM-dd") LocalDate date,
        @ToolParam(description = "Human-readable description") String description,
        @ToolParam(description = "Signed amount (negative for an outflow)") BigDecimal amount,
        @ToolParam(description = "DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND or FEE", required = false) TransactionType txType,
        @ToolParam(description = "Asset ticker (for BUY/SELL/DIVIDEND)", required = false) String ticker,
        @ToolParam(description = "Asset display name (for BUY/SELL)", required = false) String name,
        @ToolParam(description = "Quantity (for BUY/SELL)", required = false) BigDecimal quantity,
        @ToolParam(description = "Price per unit (for BUY/SELL)", required = false) BigDecimal pricePerUnit,
        @ToolParam(description = "ISO currency code, e.g. EUR", required = false) String currency) {
        TransactionRequest req = new TransactionRequest(
            date, description, amount, txType, ticker, name, quantity, pricePerUnit, currency, null);
        return manualTransactionService.addTransaction(accountId, userContext.currentMemberId(), req);
    }

    @Tool(name = "update_transaction", description = "Update an existing manual transaction on an account.")
    @RequiresScope(Scopes.TRANSACTIONS_WRITE)
    public TransactionResponse updateTransaction(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "The transaction id") Long transactionId,
        @ToolParam(description = "Transaction date, ISO yyyy-MM-dd") LocalDate date,
        @ToolParam(description = "Human-readable description") String description,
        @ToolParam(description = "Signed amount (negative for an outflow)") BigDecimal amount,
        @ToolParam(description = "DEPOSIT, WITHDRAWAL, BUY, SELL, DIVIDEND or FEE", required = false) TransactionType txType,
        @ToolParam(description = "Asset ticker (for BUY/SELL/DIVIDEND)", required = false) String ticker,
        @ToolParam(description = "Asset display name (for BUY/SELL)", required = false) String name,
        @ToolParam(description = "Quantity (for BUY/SELL)", required = false) BigDecimal quantity,
        @ToolParam(description = "Price per unit (for BUY/SELL)", required = false) BigDecimal pricePerUnit,
        @ToolParam(description = "ISO currency code, e.g. EUR", required = false) String currency) {
        TransactionRequest req = new TransactionRequest(
            date, description, amount, txType, ticker, name, quantity, pricePerUnit, currency, null);
        return manualTransactionService.updateTransaction(accountId, transactionId, userContext.currentMemberId(), req);
    }

    @Tool(name = "delete_transaction", description = "Delete a manual transaction from an account.")
    @RequiresScope(Scopes.TRANSACTIONS_WRITE)
    public String deleteTransaction(
        @ToolParam(description = "The account id") Long accountId,
        @ToolParam(description = "The transaction id") Long transactionId) {
        manualTransactionService.deleteTransaction(accountId, transactionId, userContext.currentMemberId());
        return "Deleted transaction " + transactionId + " from account " + accountId;
    }
}
