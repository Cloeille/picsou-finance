package com.picsou.dto;

import com.picsou.model.AccountType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class HomeBankImportDtos {
    private HomeBankImportDtos() { }

    public record AccountPreview(String sourceId, String name, String institution, String sourceType,
                                 AccountType suggestedType, String currency, BigDecimal initialBalance,
                                 BigDecimal balance, int transactionCount, boolean closed) { }
    public record CategoryPreview(String sourceId, String name, String parentSourceId,
                                  boolean income, int transactionCount) { }
    public record TransactionPreview(String sourceId, String accountSourceId, LocalDate date,
                                     BigDecimal amount, String currency, String payee, String notes,
                                     String categorySourceId, boolean transfer) { }
    public record Preview(String fileToken, List<AccountPreview> accounts, List<CategoryPreview> categories,
                          List<AccountResponse> existingAccounts, List<CategoryResponse> existingCategories,
                          List<TransactionPreview> sampleTransactions, int totalTransactions,
                          int forecastTransactions) { }

    public record AccountMapping(@NotBlank String sourceId, @NotNull FinaryMappingAction action,
                                 Long targetAccountId, @Valid NewAccountDetails newAccount) { }
    public enum CategoryMappingAction { MAP_EXISTING, CREATE_NEW, UNCATEGORIZED }
    public record CategoryMapping(@NotBlank String sourceId, @NotNull CategoryMappingAction action,
                                  Long targetCategoryId, @Size(max = 100) String name) { }
    public record Request(@NotBlank String fileToken,
                          @NotNull @Size(max = 100) List<@NotNull @Valid AccountMapping> accountMappings,
                          @NotNull @Size(max = 200) List<@NotNull @Valid CategoryMapping> categoryMappings) { }
    public record Result(int accountsCreated, int accountsMapped, int accountsSkipped,
                         int categoriesCreated, int transactionsImported, int transactionsSkipped) { }
}
