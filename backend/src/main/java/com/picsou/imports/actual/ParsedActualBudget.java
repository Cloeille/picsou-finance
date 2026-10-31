package com.picsou.imports.actual;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The importable content of an Actual Budget file, already resolved against Actual's own rules:
 * tombstoned rows, split parents, merged categories and merged payees are gone, transfers and
 * starting balances are flagged. {@code currency} is the budget's default currency when the file
 * records one, otherwise null (Actual budgets are single-currency and older ones never store it).
 */
public record ParsedActualBudget(
        String currency,
        List<SourceAccount> accounts,
        List<SourceCategory> categories,
        List<SourceTransaction> transactions) {

    public ParsedActualBudget {
        accounts = List.copyOf(accounts);
        categories = List.copyOf(categories);
        transactions = List.copyOf(transactions);
    }

    public record SourceAccount(String id, String name, boolean offBudget, boolean closed) { }

    public record SourceCategory(String id, String name, String groupId, String groupName, boolean income) { }

    /** How a row moves money, which decides whether it may count as income or spending. */
    public enum Kind { REGULAR, TRANSFER, STARTING_BALANCE }

    public record SourceTransaction(String id, String accountId, LocalDate date, BigDecimal amount,
                                    String payee, String notes, String categoryId, Kind kind) { }
}
