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

    /**
     * {@code currency} is the budget's own currency when the file records it, otherwise null.
     * {@code actualAccountIds} are the existing accounts any Actual import created, whatever the
     * budget: the wizard pre-selects one only for the source it was created for, never by name.
     */
    public record Preview(String fileToken, String currency, List<AccountPreview> accounts,
                          List<CategoryPreview> categories, List<AccountResponse> existingAccounts,
                          List<Long> actualAccountIds, List<CategoryResponse> existingCategories,
                          List<TransactionPreview> sampleTransactions, int totalTransactions,
                          int transferTransactions) { }

    public record AccountMapping(@NotBlank String sourceId, @NotNull FinaryMappingAction action,
                                 Long targetAccountId, @Valid NewAccountDetails newAccount) { }

    public enum CategoryMappingAction { MAP_EXISTING, CREATE_NEW, UNCATEGORIZED }

    public record CategoryMapping(@NotBlank String sourceId, @NotNull CategoryMappingAction action,
                                  Long targetCategoryId, @Size(max = 100) String name) { }

    /** {@code acknowledgeLargeDeletion} confirms a {@link Plan#largeDeletion}; execute refuses one without it. */
    public record Request(@NotBlank String fileToken,
                          @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
                          @NotNull @Size(max = 100) List<@NotNull @Valid AccountMapping> accountMappings,
                          @NotNull @Size(max = 500) List<@NotNull @Valid CategoryMapping> categoryMappings,
                          boolean acknowledgeLargeDeletion) { }

    /**
     * Rows a re-import leaves in place on an append-only account (one the user created, or one an
     * import created for another source): {@code KEPT_MISSING} rows no longer exist in Actual
     * (deleted, or a parent since split), {@code KEPT_MOVED} rows now belong to another Actual
     * account or sit in an account this request does not target.
     */
    public enum WarningReason { KEPT_MISSING, KEPT_MOVED }

    public record Warning(WarningReason reason, int count) { }

    /**
     * What {@link Request} would do, computed without writing so the user can confirm it.
     * {@code largeDeletion}: the deletions exceed the safety threshold and must be acknowledged.
     */
    public record Plan(int transactionsToAdd, int transactionsToDelete, int transactionsToMove,
                       List<Warning> warnings, boolean largeDeletion) { }

    public record Result(int accountsCreated, int accountsMapped, int accountsSkipped, int categoriesCreated,
                         int transactionsImported, int transactionsSkipped, int transactionsDeleted,
                         int transactionsMoved, List<Warning> warnings) { }
}
