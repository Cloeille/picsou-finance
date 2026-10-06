package com.picsou.imports.homebank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ParsedHomeBankData(
        List<SourceAccount> accounts,
        List<SourceCategory> categories,
        List<SourceTransaction> transactions) {

    public ParsedHomeBankData {
        accounts = List.copyOf(accounts);
        categories = List.copyOf(categories);
        transactions = List.copyOf(transactions);
    }

    public record SourceAccount(String id, String name, String institution, String type, String currency,
                                BigDecimal initialBalance, boolean closed) { }

    /**
     * {@code kindInferred} is true when {@code income} was guessed from the signs of the exported
     * amounts (QIF categories without an explicit I/E declaration) rather than declared by the source.
     */
    public record SourceCategory(String id, String name, String parentId, boolean income, boolean kindInferred) { }

    public record SourceTransaction(String id, String accountId, LocalDate date, BigDecimal amount,
                                    String currency, String payee, String notes, String categoryId,
                                    String transferAccountId, boolean forecast) { }
}
