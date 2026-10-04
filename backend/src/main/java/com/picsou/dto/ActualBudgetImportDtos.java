package com.picsou.dto;

import com.picsou.imports.actual.ParsedActualBudget.Kind;
import com.picsou.model.AccountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request and response shapes of the two-phase Actual Budget import ({@code /api/actual/import}). */
public final class ActualBudgetImportDtos {
    private ActualBudgetImportDtos() { }

    /** {@code importedAccountId} is the Picsou account an earlier import created for this source, if any. */
    public record AccountPreview(String sourceId, String name, boolean offBudget, boolean closed,
                                 AccountType suggestedType, BigDecimal balance, int transactionCount,
                                 Long importedAccountId) { }

    public record CategoryPreview(String sourceId, String name, String groupName, boolean income,
                                  int transactionCount) { }

    public record TransactionPreview(String sourceId, String accountSourceId, LocalDate date, BigDecimal amount,
                                     String payee, String notes, String categorySourceId, Kind kind) { }

    /** {@code currency} is the budget's own currency when the file records it, otherwise null. */
    public record Preview(String fileToken, String currency, List<AccountPreview> accounts,
                          List<CategoryPreview> categories, List<AccountResponse> existingAccounts,
                          List<CategoryResponse> existingCategories, List<TransactionPreview> sampleTransactions,
                          int totalTransactions, int transferTransactions) { }

    public record AccountMapping(@NotBlank String sourceId, @NotNull FinaryMappingAction action,
                                 Long targetAccountId, @Valid NewAccountDetails newAccount) { }

    public enum CategoryMappingAction { MAP_EXISTING, CREATE_NEW, UNCATEGORIZED }

    public record CategoryMapping(@NotBlank String sourceId, @NotNull CategoryMappingAction action,
                                  Long targetCategoryId, @Size(max = 100) String name) { }

    public record Request(@NotBlank String fileToken,
                          @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
                          @NotNull @Size(max = 100) List<@NotNull @Valid AccountMapping> accountMappings,
                          @NotNull @Size(max = 500) List<@NotNull @Valid CategoryMapping> categoryMappings) { }

    public record Result(int accountsCreated, int accountsMapped, int accountsSkipped, int categoriesCreated,
                         int transactionsImported, int transactionsSkipped) { }
}
