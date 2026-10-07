package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.CategoryResponse;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.HomeBankImportDtos.*;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.ImportPreviewStore;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.imports.homebank.ParsedHomeBankData;
import com.picsou.imports.homebank.ParsedHomeBankData.SourceAccount;
import com.picsou.imports.homebank.ParsedHomeBankData.SourceCategory;
import com.picsou.imports.homebank.ParsedHomeBankData.SourceTransaction;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class HomeBankImportService {
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<AccountType> FORBIDDEN_ACCOUNT_TYPES = Set.of(
            AccountType.PEA, AccountType.COMPTE_TITRES, AccountType.CRYPTO, AccountType.ASSURANCE_VIE);
    private static final int MAX_ACCOUNTS = 100;
    private static final int MAX_CATEGORIES = 200;
    private static final int MAX_ACCOUNT_NAME = 100;
    private static final int MAX_CATEGORY_NAME = 100;
    private static final int MAX_TRANSACTION_TEXT = 255;
    private static final BigDecimal MAX_LEDGER_AMOUNT = new BigDecimal("999999999999.99999999");

    private final HomeBankFileParser parser;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final FamilyMemberRepository members;
    private final FinaryPersistenceHelper persistence;
    private final ImportPreviewStore<ParsedHomeBankData> previews;

    @org.springframework.beans.factory.annotation.Autowired
    public HomeBankImportService(HomeBankFileParser parser, AccountRepository accounts,
            CategoryRepository categories, TransactionRepository transactions, FamilyMemberRepository members,
            BalanceSnapshotRepository ignored, FinaryPersistenceHelper persistence) {
        this(parser, accounts, categories, transactions, members, ignored, persistence,
                new ImportPreviewStore<>(Clock.systemUTC(), Duration.ofMinutes(30), 8, 3));
    }

    HomeBankImportService(HomeBankFileParser parser, AccountRepository accounts,
            CategoryRepository categories, TransactionRepository transactions, FamilyMemberRepository members,
            BalanceSnapshotRepository ignored, FinaryPersistenceHelper persistence,
            ImportPreviewStore<ParsedHomeBankData> previews) {
        this.parser = parser;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.members = members;
        this.persistence = persistence;
        this.previews = previews;
    }

    public Preview preview(MultipartFile file, String password, Long memberId) {
        return preview(file, password, null, memberId);
    }

    public Preview preview(MultipartFile file, String password, String currency, Long memberId) {
        ParsedHomeBankData parsed;
        try {
            parsed = currency == null ? parser.parse(file.getBytes(), file.getOriginalFilename(), password)
                    : parser.parse(file.getBytes(), file.getOriginalFilename(), password, currency);
        } catch (IOException e) {
            throw bad("Unable to read HomeBank file");
        }

        String token = previews.put(memberId, parsed);
        Map<String, BigDecimal> balances = new HashMap<>();
        Map<String, Integer> transactionCounts = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        parsed.accounts().forEach(account -> balances.put(account.id(), account.initialBalance()));
        parsed.transactions().stream().filter(transaction -> !transaction.forecast()).forEach(transaction -> {
            balances.computeIfPresent(transaction.accountId(), (id, balance) -> balance.add(transaction.amount()));
            transactionCounts.merge(transaction.accountId(), 1, Integer::sum);
            if (transaction.categoryId() != null) {
                categoryCounts.merge(transaction.categoryId(), 1, Integer::sum);
            }
        });

        List<AccountPreview> accountPreviews = parsed.accounts().stream()
                .map(account -> new AccountPreview(account.id(), account.name(), account.institution(), account.type(),
                        suggest(account.type()), account.currency(), account.initialBalance(), balances.get(account.id()),
                        transactionCounts.getOrDefault(account.id(), 0), account.closed()))
                .toList();
        List<CategoryPreview> categoryPreviews = parsed.categories().stream()
                .map(category -> new CategoryPreview(category.id(), category.name(), category.parentId(),
                        category.income(), category.kindInferred(), categoryCounts.getOrDefault(category.id(), 0)))
                .toList();
        List<AccountResponse> existingAccounts = accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
                .map(account -> AccountResponse.from(account, account.getCurrentBalance())).toList();
        List<CategoryResponse> existingCategories = categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId)
                .stream().filter(category -> !category.isArchived()).map(CategoryResponse::from).toList();
        List<TransactionPreview> sample = parsed.transactions().stream()
                .filter(transaction -> !transaction.forecast()).limit(20)
                .map(transaction -> new TransactionPreview(transaction.id(), transaction.accountId(), transaction.date(),
                        transaction.amount(), transaction.currency(), transaction.payee(), transaction.notes(),
                        transaction.categoryId(), transaction.transferAccountId() != null))
                .toList();
        int forecastCount = (int) parsed.transactions().stream().filter(SourceTransaction::forecast).count();
        return new Preview(token, accountPreviews, categoryPreviews, existingAccounts, existingCategories, sample,
                parsed.transactions().size() - forecastCount, forecastCount);
    }

    @Transactional
    public Result executeImport(Request request, Long memberId) {
        validateRequestShape(request);
        ImportPreviewStore.Entry<ParsedHomeBankData> entry = previews.get(request.fileToken(), memberId);
        if (entry == null) {
            throw bad("Preview expired or invalid; please upload the file again");
        }
        members.findByIdForUpdate(memberId).orElseThrow(() -> bad("Family member not found"));
        ParsedHomeBankData parsed = entry.payload();

        Map<String, AccountMapping> accountMappings = accountMappingsBySource(request.accountMappings(), parsed.accounts());
        Map<String, CategoryMapping> categoryMappings = categoryMappingsBySource(request.categoryMappings(), parsed.categories());
        Map<String, Account> targetAccounts = validateAccounts(parsed, accountMappings, memberId);
        Map<String, Category> targetCategories = validateCategories(parsed, categoryMappings, memberId);
        Category reusableTransferCategory = findReusableTransferCategory(memberId);
        validateTransactions(parsed, accountMappings, categoryMappings, targetAccounts);
        validateExistingTransactionOwnership(parsed, accountMappings, targetAccounts, memberId);
        validateUniqueAccountTargets(parsed, accountMappings, targetAccounts);

        if (!previews.consume(request.fileToken(), entry)) {
            throw bad("Preview has already been used");
        }
        boolean synchronizedTransaction = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        previews.complete(request.fileToken(), entry);
                    } else {
                        previews.restore(request.fileToken(), entry);
                    }
                }
            });
        }

        try {
            FamilyMember member = members.findById(memberId)
                    .orElseThrow(() -> bad("Family member not found"));
            ImportCounts counts = new ImportCounts();
            createOrResolveAccounts(parsed, accountMappings, targetAccounts, member, memberId, counts);
            createCategoriesInParentOrder(parsed, categoryMappings, targetCategories, member, counts);
            Category transferCategory = reusableTransferCategory == null
                    ? createTransferCategory(member) : reusableTransferCategory;
            Set<Long> accountsWithNewRows = new HashSet<>();
            importOpeningBalances(parsed, accountMappings, targetAccounts, transferCategory, accountsWithNewRows);
            importTransactions(parsed, accountMappings, categoryMappings, targetAccounts, targetCategories,
                    transferCategory, counts, accountsWithNewRows);
            recomputeDerivedState(parsed, accountMappings, targetAccounts, accountsWithNewRows);

            if (!synchronizedTransaction) {
                previews.complete(request.fileToken(), entry);
            }
            return counts.result();
        } catch (RuntimeException e) {
            if (!synchronizedTransaction) {
                previews.restore(request.fileToken(), entry);
            }
            throw e;
        }
    }

    private void validateRequestShape(Request request) {
        if (request == null || blank(request.fileToken()) || request.accountMappings() == null
                || request.categoryMappings() == null) {
            throw bad("Import request and mapping lists are required");
        }
        if (request.accountMappings().size() > MAX_ACCOUNTS || request.categoryMappings().size() > MAX_CATEGORIES) {
            throw bad("Too many account or category mappings");
        }
        if (request.accountMappings().stream().anyMatch(Objects::isNull)
                || request.categoryMappings().stream().anyMatch(Objects::isNull)) {
            throw bad("Mapping entries cannot be null");
        }
    }

    private Map<String, Account> validateAccounts(ParsedHomeBankData parsed,
            Map<String, AccountMapping> mappings, Long memberId) {
        Map<String, Account> targets = new HashMap<>();
        for (SourceAccount source : parsed.accounts()) {
            AccountMapping mapping = mappings.get(source.id());
            if (mapping.action() == null) {
                throw bad("Account mapping action is required");
            }
            if (mapping.action() == FinaryMappingAction.MAP_EXISTING) {
                if (mapping.targetAccountId() == null || mapping.newAccount() != null) {
                    throw bad("Invalid existing account mapping");
                }
                Account target = accounts.findByIdAndMemberId(mapping.targetAccountId(), memberId)
                        .orElseThrow(() -> bad("Target account not found"));
                validateTargetAccount(source, target);
                targets.put(source.id(), target);
                continue;
            }
            if (mapping.action() == FinaryMappingAction.CREATE_NEW) {
                NewAccountDetails details = mapping.newAccount();
                validateNewAccountDetails(source, details);
                String externalId = homeBankAccountId(source.id());
                if (accounts.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
                    throw bad("This HomeBank account was previously archived and cannot be restored by import");
                }
                accounts.findByExternalAccountIdAndMemberId(externalId, memberId).ifPresent(existing -> {
                    validateTargetAccount(source, existing);
                    if (!existing.isManual()) {
                        throw bad("Existing HomeBank account is not a manual account");
                    }
                    targets.put(source.id(), existing);
                });
                continue;
            }
            if (mapping.action() != FinaryMappingAction.SKIP) {
                throw bad("Unsupported account mapping action");
            }
        }
        return targets;
    }

    private void validateTargetAccount(SourceAccount source, Account target) {
        if (!source.currency().equals(target.getCurrency())) {
            throw bad("Target account currency does not match");
        }
        if (target.getType() == null || FORBIDDEN_ACCOUNT_TYPES.contains(target.getType())) {
            throw bad("Investment accounts cannot receive HomeBank transactions");
        }
    }

    private void validateNewAccountDetails(SourceAccount source, NewAccountDetails details) {
        if (details == null || blank(details.name()) || details.name().length() > MAX_ACCOUNT_NAME
                || details.type() == null || FORBIDDEN_ACCOUNT_TYPES.contains(details.type())
                || !source.currency().equals(details.currency())
                || details.provider() != null && details.provider().length() > 100
                || details.color() != null && !COLOR.matcher(details.color()).matches()) {
            throw bad("Invalid new account details");
        }
    }

    private void validateUniqueAccountTargets(ParsedHomeBankData parsed, Map<String, AccountMapping> mappings,
            Map<String, Account> targets) {
        Set<Long> seenIds = new HashSet<>();
        for (SourceAccount source : parsed.accounts()) {
            if (mappings.get(source.id()).action() == FinaryMappingAction.SKIP) {
                continue;
            }
            Account target = targets.get(source.id());
            if (target != null && !seenIds.add(target.getId())) {
                throw bad("Multiple source accounts cannot map to one target");
            }
        }
    }

    private Map<String, Category> validateCategories(ParsedHomeBankData parsed,
            Map<String, CategoryMapping> mappings, Long memberId) {
        Map<String, Category> resolved = new HashMap<>();
        Map<String, SourceCategory> sourceCategories = parsed.categories().stream()
                .collect(Collectors.toMap(SourceCategory::id, Function.identity()));
        List<Category> existingCategories = categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId);

        for (SourceCategory source : parsed.categories()) {
            CategoryMapping mapping = mappings.get(source.id());
            if (mapping.action() == null) {
                throw bad("Category mapping action is required");
            }
            if (mapping.action() == CategoryMappingAction.MAP_EXISTING) {
                if (mapping.targetCategoryId() == null || mapping.name() != null) {
                    throw bad("Invalid existing category mapping");
                }
                Category target = categories.findByIdAndMemberId(mapping.targetCategoryId(), memberId)
                        .filter(category -> !category.isArchived())
                        .orElseThrow(() -> bad("Target category not found or archived"));
                validateCategoryKind(source, target);
                resolved.put(source.id(), target);
            }
            if (mapping.action() != CategoryMappingAction.MAP_EXISTING
                    && mapping.action() != CategoryMappingAction.CREATE_NEW
                    && mapping.action() != CategoryMappingAction.UNCATEGORIZED) {
                throw bad("Unsupported category mapping action");
            }
        }

        for (SourceCategory source : parsed.categories()) {
            CategoryMapping mapping = mappings.get(source.id());
            if (mapping.action() == CategoryMappingAction.CREATE_NEW) {
                validateNewCategoryMapping(mapping);
                String slug = homeBankCategorySlug(source.id());
                Category existing = existingCategories.stream()
                        .filter(category -> slug.equals(category.getSlug())).findFirst().orElse(null);
                if (existing != null) {
                    if (existing.isArchived()) {
                        throw bad("HomeBank category slug belongs to an archived category");
                    }
                    validateCategoryKind(source, existing);
                    resolved.put(source.id(), existing);
                }
                if (source.parentId() != null) {
                    SourceCategory parentSource = sourceCategories.get(source.parentId());
                    CategoryMapping parentMapping = mappings.get(source.parentId());
                    if (parentSource == null || parentMapping == null
                            || parentMapping.action() == CategoryMappingAction.UNCATEGORIZED) {
                        throw bad("Category parent must also be mapped");
                    }
                    if (parentMapping.action() == CategoryMappingAction.MAP_EXISTING) {
                        Category parent = resolved.get(parentSource.id());
                        if (parent.getParent() != null) {
                            throw bad("HomeBank categories cannot create a third category level");
                        }
                    }
                }
                continue;
            }
            if (mapping.action() == CategoryMappingAction.UNCATEGORIZED
                    && (mapping.targetCategoryId() != null || mapping.name() != null)) {
                throw bad("Uncategorized mapping cannot have a target or name");
            }
        }

        Map<String, CategoryKind> kinds = effectiveKinds(parsed, resolved);
        for (SourceCategory source : parsed.categories()) {
            if (mappings.get(source.id()).action() == CategoryMappingAction.CREATE_NEW && source.parentId() != null
                    && kinds.get(source.id()) != kinds.get(source.parentId())) {
                throw bad("Category parent kind does not match");
            }
        }
        validateReusedCategoryHierarchy(parsed, mappings, resolved);
        return resolved;
    }

    private void validateNewCategoryMapping(CategoryMapping mapping) {
        if (blank(mapping.name()) || mapping.name().length() > MAX_CATEGORY_NAME || mapping.targetCategoryId() != null) {
            throw bad("Invalid new category mapping");
        }
    }

    private void validateReusedCategoryHierarchy(ParsedHomeBankData parsed,
            Map<String, CategoryMapping> mappings, Map<String, Category> resolved) {
        for (SourceCategory source : parsed.categories()) {
            Category category = resolved.get(source.id());
            if (category == null || mappings.get(source.id()).action() != CategoryMappingAction.CREATE_NEW) {
                continue;
            }
            Category expectedParent = source.parentId() == null ? null : resolved.get(source.parentId());
            if (!Objects.equals(category.getParent(), expectedParent)) {
                throw bad("Existing HomeBank category hierarchy does not match source");
            }
        }
    }

    /**
     * Kind each source category ends up with. Already-existing targets keep their own kind. A kind
     * inferred from amount signs (QIF) is not authoritative, so an inferred sub-category adopts its
     * parent's kind, which Picsou requires to be uniform within a category tree.
     */
    private static Map<String, CategoryKind> effectiveKinds(ParsedHomeBankData parsed, Map<String, Category> resolved) {
        Map<String, SourceCategory> byId = parsed.categories().stream()
                .collect(Collectors.toMap(SourceCategory::id, Function.identity()));
        Map<String, CategoryKind> kinds = new HashMap<>();
        for (SourceCategory source : parsed.categories()) {
            effectiveKind(source, byId, resolved, kinds, new HashSet<>());
        }
        return kinds;
    }

    private static CategoryKind effectiveKind(SourceCategory source, Map<String, SourceCategory> byId,
            Map<String, Category> resolved, Map<String, CategoryKind> kinds, Set<String> visiting) {
        CategoryKind known = kinds.get(source.id());
        if (known != null) {
            return known;
        }
        Category existing = resolved.get(source.id());
        CategoryKind kind = source.income() ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        if (existing != null) {
            kind = existing.getKind();
        } else if (source.kindInferred() && source.parentId() != null && byId.containsKey(source.parentId())
                && visiting.add(source.id())) {
            kind = effectiveKind(byId.get(source.parentId()), byId, resolved, kinds, visiting);
        }
        kinds.put(source.id(), kind);
        return kind;
    }

    private void validateCategoryKind(SourceCategory source, Category target) {
        CategoryKind expected = source.income() ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        boolean compatible = source.kindInferred()
                ? target.getKind() == CategoryKind.INCOME || target.getKind() == CategoryKind.EXPENSE
                : target.getKind() == expected;
        if (!compatible) {
            throw bad("Target category kind does not match source");
        }
    }

    private void validateTransactions(ParsedHomeBankData parsed, Map<String, AccountMapping> accountMappings,
            Map<String, CategoryMapping> categoryMappings, Map<String, Account> targetAccounts) {
        Map<String, BigDecimal> accountBalances = new HashMap<>();
        for (SourceAccount source : parsed.accounts()) {
            accountBalances.put(source.id(), source.initialBalance());
        }
        for (SourceTransaction transaction : parsed.transactions()) {
            if (transaction.forecast() || accountMappings.get(transaction.accountId()).action() == FinaryMappingAction.SKIP) {
                continue;
            }
            if (transaction.date() == null || transaction.amount() == null || blank(transaction.currency())) {
                throw bad("HomeBank transaction is missing required data");
            }
            validateLedgerAmount(transaction.amount());
            if (transaction.notes() != null && transaction.notes().length() > MAX_TRANSACTION_TEXT
                    || transaction.payee() != null && transaction.payee().length() > MAX_TRANSACTION_TEXT) {
                throw bad("HomeBank transaction text exceeds supported length");
            }
            String description = transaction.notes() != null && !transaction.notes().isEmpty()
                    ? transaction.notes()
                    : transaction.payee() != null && !transaction.payee().isBlank()
                            ? transaction.payee() : "HomeBank transaction";
            if (description.length() > MAX_TRANSACTION_TEXT) {
                throw bad("HomeBank transaction text exceeds supported length");
            }
            SourceAccount sourceAccount = parsed.accounts().stream()
                    .filter(account -> account.id().equals(transaction.accountId())).findFirst()
                    .orElseThrow(() -> bad("HomeBank transaction account is missing"));
            if (!sourceAccount.currency().equals(transaction.currency())) {
                throw bad("HomeBank transaction currency does not match its account");
            }
            Account target = targetAccounts.get(sourceAccount.id());
            if (target != null) {
                validateTargetAccount(sourceAccount, target);
            }
            if (transaction.categoryId() != null && !categoryMappings.containsKey(transaction.categoryId())) {
                throw bad("HomeBank transaction category is missing");
            }
            accountBalances.computeIfPresent(transaction.accountId(),
                    (id, balance) -> balance.add(transaction.amount()));
        }
        accountBalances.values().forEach(this::validateLedgerAmount);
    }

    private void validateLedgerAmount(BigDecimal amount) {
        if (amount == null || amount.abs().compareTo(MAX_LEDGER_AMOUNT) > 0
                || amount.stripTrailingZeros().scale() > 8) {
            throw bad("HomeBank amount exceeds supported precision");
        }
    }

    private void validateExistingTransactionOwnership(ParsedHomeBankData parsed,
            Map<String, AccountMapping> accountMappings, Map<String, Account> targetAccounts, Long memberId) {
        Set<String> externalIds = parsed.transactions().stream()
                .filter(transaction -> !transaction.forecast())
                .filter(transaction -> accountMappings.get(transaction.accountId()).action() != FinaryMappingAction.SKIP)
                .map(transaction -> homeBankTransactionId(transaction.id()))
                .collect(Collectors.toCollection(HashSet::new));
        parsed.accounts().stream()
                .filter(source -> accountMappings.get(source.id()).action() != FinaryMappingAction.SKIP)
                .filter(source -> source.initialBalance().compareTo(BigDecimal.ZERO) != 0)
                .map(source -> homeBankOpeningId(source.id()))
                .forEach(externalIds::add);
        if (externalIds.isEmpty()) {
            return;
        }
        Map<String, Transaction> existingByExternalId = transactions
                .findByAccountMemberIdAndExternalIdIn(memberId, externalIds).stream()
                .collect(Collectors.toMap(Transaction::getExternalId, Function.identity(), (first, ignored) -> first));
        Map<String, String> sourceAccountByExternalId = parsed.transactions().stream()
                .filter(transaction -> !transaction.forecast())
                .collect(Collectors.toMap(transaction -> homeBankTransactionId(transaction.id()),
                        SourceTransaction::accountId, (first, ignored) -> first));
        parsed.accounts().forEach(source -> sourceAccountByExternalId.put(homeBankOpeningId(source.id()), source.id()));
        for (Map.Entry<String, Transaction> entry : existingByExternalId.entrySet()) {
            String sourceAccountId = sourceAccountByExternalId.get(entry.getKey());
            Account expectedTarget = targetAccounts.get(sourceAccountId);
            Transaction existing = entry.getValue();
            if (expectedTarget == null || existing.getAccount() == null
                    || !Objects.equals(existing.getAccount().getId(), expectedTarget.getId())) {
                throw bad("HomeBank transaction was previously imported into a different account");
            }
        }
    }

    private void createOrResolveAccounts(ParsedHomeBankData parsed, Map<String, AccountMapping> mappings,
            Map<String, Account> targets, FamilyMember member, Long memberId, ImportCounts counts) {
        for (SourceAccount source : parsed.accounts()) {
            AccountMapping mapping = mappings.get(source.id());
            if (mapping.action() == FinaryMappingAction.SKIP) {
                counts.accountsSkipped++;
                continue;
            }
            Account target = targets.get(source.id());
            if (mapping.action() == FinaryMappingAction.CREATE_NEW && target == null) {
                NewAccountDetails details = mapping.newAccount();
                target = accounts.save(Account.builder().member(member).name(details.name()).type(details.type())
                        .provider(details.provider()).currency(details.currency()).currentBalance(BigDecimal.ZERO)
                        .isManual(true).color(details.color() == null ? "#6366f1" : details.color())
                        .externalAccountId(homeBankAccountId(source.id())).build());
                targets.put(source.id(), target);
                counts.accountsCreated++;
            } else if (mapping.action() == FinaryMappingAction.MAP_EXISTING) {
                counts.accountsMapped++;
            }
        }
    }

    private void createCategoriesInParentOrder(ParsedHomeBankData parsed,
            Map<String, CategoryMapping> mappings, Map<String, Category> categoriesBySource,
            FamilyMember member, ImportCounts counts) {
        Map<String, CategoryKind> kinds = effectiveKinds(parsed, categoriesBySource);
        Set<String> pending = parsed.categories().stream()
                .filter(source -> mappings.get(source.id()).action() == CategoryMappingAction.CREATE_NEW
                        && !categoriesBySource.containsKey(source.id()))
                .map(SourceCategory::id).collect(Collectors.toCollection(LinkedHashSet::new));
        while (!pending.isEmpty()) {
            boolean createdOne = false;
            for (SourceCategory source : parsed.categories()) {
                if (!pending.contains(source.id())) {
                    continue;
                }
                Category parent = source.parentId() == null ? null : categoriesBySource.get(source.parentId());
                if (source.parentId() != null && parent == null) {
                    continue;
                }
                CategoryMapping mapping = mappings.get(source.id());
                Category category = categories.save(Category.builder().member(member).name(mapping.name())
                        .slug(homeBankCategorySlug(source.id()))
                        .kind(kinds.get(source.id()))
                        .color("#6366f1").parent(parent).build());
                categoriesBySource.put(source.id(), category);
                pending.remove(source.id());
                counts.categoriesCreated++;
                createdOne = true;
            }
            if (!createdOne) {
                throw bad("HomeBank category hierarchy is invalid");
            }
        }
    }

    private Category findReusableTransferCategory(Long memberId) {
        List<Category> existing = categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId);
        Optional<Category> namedTransfer = existing.stream()
                .filter(category -> !category.isArchived() && category.getKind() == CategoryKind.TRANSFER
                        && "Virement interne".equalsIgnoreCase(category.getName()))
                .findFirst();
        if (namedTransfer.isPresent()) {
            return namedTransfer.get();
        }
        Optional<Category> sluggedTransfer = existing.stream()
                .filter(category -> !category.isArchived() && "homebank-transfer".equals(category.getSlug())
                        && category.getKind() == CategoryKind.TRANSFER)
                .findFirst();
        if (sluggedTransfer.isPresent()) {
            return sluggedTransfer.get();
        }
        boolean reservedConflict = existing.stream().anyMatch(category -> "homebank-transfer".equals(category.getSlug()));
        if (reservedConflict) {
            throw bad("HomeBank transfer category slug is archived or has an incompatible kind");
        }
        return null;
    }

    private Category createTransferCategory(FamilyMember member) {
        return categories.save(Category.builder().member(member).name("HomeBank transfer")
                .slug("homebank-transfer").kind(CategoryKind.TRANSFER).color("#64748b").build());
    }

    private void importOpeningBalances(ParsedHomeBankData parsed, Map<String, AccountMapping> mappings,
            Map<String, Account> targets, Category transferCategory, Set<Long> accountsWithNewRows) {
        for (SourceAccount source : parsed.accounts()) {
            if (mappings.get(source.id()).action() == FinaryMappingAction.SKIP) {
                continue;
            }
            Account target = targets.get(source.id());
            if (target == null || !target.isManual()) {
                continue;
            }
            if (source.initialBalance().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            String externalId = homeBankOpeningId(source.id());
            if (transactions.existsByAccountIdAndExternalId(target.getId(), externalId)) {
                continue;
            }
            LocalDate date = parsed.transactions().stream()
                    .filter(transaction -> source.id().equals(transaction.accountId()) && !transaction.forecast())
                    .map(SourceTransaction::date).min(LocalDate::compareTo)
                    .orElse(LocalDate.now()).minusDays(1);
            transactions.save(Transaction.builder().account(target).date(date)
                    .description("HomeBank opening balance").amount(source.initialBalance())
                    .category("Opening balance").categoryRef(transferCategory).categoryManual(true)
                    .nativeCurrency(source.currency()).externalId(externalId).isManual(true).txType(null).build());
            accountsWithNewRows.add(target.getId());
        }
    }

    private void importTransactions(ParsedHomeBankData parsed, Map<String, AccountMapping> accountMappings,
            Map<String, CategoryMapping> categoryMappings, Map<String, Account> targetAccounts,
            Map<String, Category> targetCategories, Category transferCategory, ImportCounts counts,
            Set<Long> accountsWithNewRows) {
        Map<String, SourceCategory> sourceCategories = parsed.categories().stream()
                .collect(Collectors.toMap(SourceCategory::id, Function.identity()));
        for (SourceTransaction source : parsed.transactions()) {
            if (source.forecast() || accountMappings.get(source.accountId()).action() == FinaryMappingAction.SKIP) {
                counts.transactionsSkipped++;
                continue;
            }
            Account account = targetAccounts.get(source.accountId());
            String externalId = homeBankTransactionId(source.id());
            if (transactions.existsByAccountIdAndExternalId(account.getId(), externalId)) {
                counts.transactionsSkipped++;
                continue;
            }
            String description = source.notes() != null && !source.notes().isEmpty()
                    ? source.notes()
                    : source.payee() != null && !source.payee().isBlank()
                            ? source.payee() : "HomeBank transaction";
            SourceCategory sourceCategory = source.categoryId() == null ? null : sourceCategories.get(source.categoryId());
            Category category = source.transferAccountId() != null || sourceCategory == null ? null
                    : targetCategories.get(source.categoryId());
            boolean explicitCategory = sourceCategory != null && categoryMappings.containsKey(sourceCategory.id());
            if (source.transferAccountId() != null) {
                category = transferCategory;
                explicitCategory = true;
            }
            Transaction transaction = Transaction.builder().account(account).date(source.date())
                    .amount(source.amount()).description(description).counterparty(source.payee())
                    .category(sourceCategory == null ? null : sourceCategory.name())
                    .categoryRef(category).categoryManual(category != null && explicitCategory)
                    .externalId(externalId).nativeCurrency(source.currency()).isManual(true).txType(null).build();
            transactions.save(transaction);
            accountsWithNewRows.add(account.getId());
            counts.transactionsImported++;
        }
    }

    /**
     * Mirrors ManualTransactionService.recomputeDerivedState for the cash balance: every manual
     * target that received at least one new row (whatever its mapping action) gets its balance and
     * snapshot history rebuilt from the ledger. Synced (non-manual) targets keep their provider
     * balance, and accounts that only hit deduplicated rows are left untouched.
     */
    private void recomputeDerivedState(ParsedHomeBankData parsed, Map<String, AccountMapping> mappings,
            Map<String, Account> targetAccounts, Set<Long> accountsWithNewRows) {
        for (SourceAccount source : parsed.accounts()) {
            Account account = targetAccounts.get(source.id());
            if (mappings.get(source.id()).action() == FinaryMappingAction.SKIP || account == null
                    || !account.isManual() || !accountsWithNewRows.contains(account.getId())) {
                continue;
            }
            account.setCurrentBalance(transactions.sumAmountByAccountId(account.getId()));
            persistence.reconstructSnapshotsFromDb(account);
            accounts.save(account);
        }
    }

    private static Map<String, AccountMapping> accountMappingsBySource(List<AccountMapping> mappings,
            List<SourceAccount> sources) {
        return mappingsBySource(mappings, sources, AccountMapping::sourceId, SourceAccount::id);
    }

    private static Map<String, CategoryMapping> categoryMappingsBySource(List<CategoryMapping> mappings,
            List<SourceCategory> sources) {
        return mappingsBySource(mappings, sources, CategoryMapping::sourceId, SourceCategory::id);
    }

    private static <S, M> Map<String, M> mappingsBySource(List<M> provided, List<S> sources,
            Function<M, String> mappingId, Function<S, String> sourceId) {
        Map<String, M> mapped = new HashMap<>();
        for (M mapping : provided) {
            String id = mappingId.apply(mapping);
            if (id == null || mapped.putIfAbsent(id, mapping) != null) {
                throw bad("Duplicate or missing mapping");
            }
        }
        Set<String> expected = sources.stream().map(sourceId).collect(Collectors.toSet());
        if (!mapped.keySet().equals(expected)) {
            throw bad("Every source item must have exactly one mapping");
        }
        return mapped;
    }

    private static AccountType suggest(String sourceType) {
        return switch (sourceType) {
            case "bank", "cash", "creditcard" -> AccountType.CHECKING;
            case "savings" -> AccountType.SAVINGS;
            case "liability" -> AccountType.LOAN;
            default -> AccountType.OTHER;
        };
    }

    private static String homeBankAccountId(String sourceId) {
        return "homebank_" + sourceId;
    }

    private static String homeBankCategorySlug(String sourceId) {
        return "homebank_" + sourceId;
    }

    private static String homeBankTransactionId(String sourceId) {
        return "homebank_" + sourceId;
    }

    private static String homeBankOpeningId(String sourceId) {
        return "homebank_opening_" + sourceId;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class ImportCounts {
        private int accountsCreated;
        private int accountsMapped;
        private int accountsSkipped;
        private int categoriesCreated;
        private int transactionsImported;
        private int transactionsSkipped;

        private Result result() {
            return new Result(accountsCreated, accountsMapped, accountsSkipped, categoriesCreated,
                    transactionsImported, transactionsSkipped);
        }
    }
}
