package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.ActualBudgetImportDtos.AccountMapping;
import com.picsou.dto.ActualBudgetImportDtos.AccountPreview;
import com.picsou.dto.ActualBudgetImportDtos.CategoryMapping;
import com.picsou.dto.ActualBudgetImportDtos.CategoryMappingAction;
import com.picsou.dto.ActualBudgetImportDtos.CategoryPreview;
import com.picsou.dto.ActualBudgetImportDtos.Preview;
import com.picsou.dto.ActualBudgetImportDtos.Request;
import com.picsou.dto.ActualBudgetImportDtos.Result;
import com.picsou.dto.ActualBudgetImportDtos.TransactionPreview;
import com.picsou.dto.CategoryResponse;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.actual.ActualBudgetFileParser;
import com.picsou.imports.actual.ParsedActualBudget;
import com.picsou.imports.actual.ParsedActualBudget.Kind;
import com.picsou.imports.actual.ParsedActualBudget.SourceAccount;
import com.picsou.imports.actual.ParsedActualBudget.SourceCategory;
import com.picsou.imports.actual.ParsedActualBudget.SourceTransaction;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Two-phase import of an Actual Budget export. {@link #preview} parses the file and caches the
 * parsed budget under a member-bound token; {@link #executeImport} applies the user's account and
 * category mappings in one transaction, after every mapping and every row has been validated, so
 * a rejected import writes nothing.
 *
 * <p>Re-imports converge: accounts, categories and transactions carry the Actual id
 * ({@code actual_<id>}), so a second import reuses what the first created and skips rows it
 * already stored. Transfer legs and starting balances land in a {@link CategoryKind#TRANSFER}
 * category, so they never count as income or spending.
 */
@Service
public class ActualBudgetImportService {

    static final String PREVIEW_EXPIRED = "Preview expired or invalid -- please upload the file again";
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final int SAMPLE_ROWS = 20;
    /** Keeps each IN list well under PostgreSQL's bind-parameter limit. */
    private static final int LOOKUP_BATCH = 1_000;
    private static final int MAX_NAME = 100;
    private static final int MAX_DESCRIPTION = 255;
    private static final String DEFAULT_COLOR = "#6366f1";
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final String PREFIX = "actual_";
    private static final String TRANSFER_SLUG = "virement-interne";
    private static final String ACTUAL_TRANSFER_SLUG = "actual-transfer";
    /** Actual holds cash ledgers; holding-based accounts derive their value from positions instead. */
    private static final Set<AccountType> NON_LEDGER_TYPES = Set.of(AccountType.PEA, AccountType.COMPTE_TITRES,
            AccountType.CRYPTO, AccountType.ASSURANCE_VIE, AccountType.EMPLOYEE_SAVINGS,
            AccountType.REAL_ESTATE, AccountType.SCPI);

    record CachedPreview(Long memberId, ParsedActualBudget budget, Instant createdAt) { }

    private final ActualBudgetFileParser parser;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;
    private final FamilyMemberRepository members;
    private final FinaryPersistenceHelper persistence;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedPreview> previews = new ConcurrentHashMap<>();

    @Autowired
    public ActualBudgetImportService(ActualBudgetFileParser parser, AccountRepository accounts,
            CategoryRepository categories, TransactionRepository transactions, FamilyMemberRepository members,
            FinaryPersistenceHelper persistence) {
        this(parser, accounts, categories, transactions, members, persistence, Clock.systemUTC());
    }

    ActualBudgetImportService(ActualBudgetFileParser parser, AccountRepository accounts,
            CategoryRepository categories, TransactionRepository transactions, FamilyMemberRepository members,
            FinaryPersistenceHelper persistence, Clock clock) {
        this.parser = parser;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.members = members;
        this.persistence = persistence;
        this.clock = clock;
    }

    // --- Phase 1: preview -------------------------------------------------------------------

    public Preview preview(MultipartFile file, Long memberId) {
        ParsedActualBudget budget;
        try {
            budget = parser.parse(file.getBytes());
        } catch (IOException e) {
            throw bad("Unable to read the Actual Budget file");
        }
        // One live preview per member bounds what the cache can hold.
        previews.values().removeIf(cached -> cached.memberId().equals(memberId) || isExpired(cached));
        String token = UUID.randomUUID().toString();
        previews.put(token, new CachedPreview(memberId, budget, clock.instant()));

        Map<String, BigDecimal> balances = new HashMap<>();
        Map<String, Integer> accountCounts = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (SourceTransaction tx : budget.transactions()) {
            balances.merge(tx.accountId(), tx.amount(), BigDecimal::add);
            accountCounts.merge(tx.accountId(), 1, Integer::sum);
            if (tx.categoryId() != null) {
                categoryCounts.merge(tx.categoryId(), 1, Integer::sum);
            }
        }
        List<Account> memberAccounts = accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId);
        Map<String, Long> importedAccounts = memberAccounts.stream()
                .filter(account -> account.getExternalAccountId() != null
                        && account.getExternalAccountId().startsWith(PREFIX))
                .collect(Collectors.toMap(Account::getExternalAccountId, Account::getId, (first, ignored) -> first));
        List<AccountPreview> accountPreviews = budget.accounts().stream()
                .map(account -> new AccountPreview(account.id(), account.name(), account.offBudget(), account.closed(),
                        account.offBudget() ? AccountType.OTHER : AccountType.CHECKING,
                        balances.getOrDefault(account.id(), BigDecimal.ZERO.setScale(2)),
                        accountCounts.getOrDefault(account.id(), 0), importedAccounts.get(PREFIX + account.id())))
                .toList();
        List<CategoryPreview> categoryPreviews = budget.categories().stream()
                .map(category -> new CategoryPreview(category.id(), category.name(), category.groupName(),
                        category.income(), categoryCounts.getOrDefault(category.id(), 0)))
                .toList();
        List<AccountResponse> existingAccounts = memberAccounts.stream()
                .map(account -> AccountResponse.from(account, account.getCurrentBalance()))
                .toList();
        List<CategoryResponse> existingCategories = categories
                .findAllByMemberIdAndArchivedFalseOrderBySortOrderAscIdAsc(memberId).stream()
                .map(CategoryResponse::from)
                .toList();
        List<SourceTransaction> all = budget.transactions();
        List<TransactionPreview> sample = new ArrayList<>(all.subList(Math.max(0, all.size() - SAMPLE_ROWS), all.size())
                .stream()
                .map(tx -> new TransactionPreview(tx.id(), tx.accountId(), tx.date(), tx.amount(), tx.payee(),
                        tx.notes(), tx.categoryId(), tx.kind()))
                .toList());
        Collections.reverse(sample);
        int transfers = (int) all.stream().filter(tx -> tx.kind() != Kind.REGULAR).count();
        return new Preview(token, budget.currency(), accountPreviews, categoryPreviews, existingAccounts,
                existingCategories, sample, all.size(), transfers);
    }

    // --- Phase 2: execute -------------------------------------------------------------------

    @Transactional
    public Result executeImport(Request request, Long memberId) {
        CachedPreview cached = previews.get(request.fileToken());
        if (cached == null || !cached.memberId().equals(memberId) || isExpired(cached)) {
            throw bad(PREVIEW_EXPIRED);
        }
        ParsedActualBudget budget = cached.budget();
        if (budget.currency() != null && !budget.currency().equals(request.currency())) {
            throw bad("The budget currency is " + budget.currency() + ", not " + request.currency());
        }

        Map<String, AccountMapping> accountMappings = bySource(request.accountMappings(), AccountMapping::sourceId,
                budget.accounts().stream().map(SourceAccount::id).toList());
        Map<String, CategoryMapping> categoryMappings = bySource(request.categoryMappings(),
                CategoryMapping::sourceId, budget.categories().stream().map(SourceCategory::id).toList());
        Map<String, Account> targetAccounts = resolveAccounts(budget, accountMappings, request.currency(), memberId);
        Map<String, Category> targetCategories = resolveCategories(budget, categoryMappings, memberId);
        Map<String, Category> groupParents = resolveGroupParents(budget, categoryMappings, targetCategories, memberId);
        List<SourceTransaction> importable = budget.transactions().stream()
                .filter(tx -> accountMappings.get(tx.accountId()).action() != FinaryMappingAction.SKIP)
                .toList();
        Set<String> alreadyImported = alreadyImported(importable, targetAccounts, memberId);
        boolean needsTransfer = importable.stream().anyMatch(tx -> tx.kind() != Kind.REGULAR);
        Category existingTransfer = needsTransfer ? existingTransferCategory(memberId) : null;

        // Consume the token before writing: of two executes racing on one preview, exactly one
        // passes. A rolled-back import hands the preview back so the user can retry it.
        if (!previews.remove(request.fileToken(), cached)) {
            throw bad(PREVIEW_EXPIRED);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED && !isExpired(cached)) {
                        previews.putIfAbsent(request.fileToken(), cached);
                    }
                }
            });
        }

        FamilyMember member = members.findById(memberId).orElseThrow(() -> bad("Family member not found"));
        Counts counts = new Counts();
        createAccounts(budget, accountMappings, targetAccounts, member, request.currency(), counts);
        createCategories(budget, categoryMappings, targetCategories, groupParents, member, counts);
        Category transferCategory = !needsTransfer ? null
                : existingTransfer != null ? existingTransfer : createTransferCategory(member);

        Map<String, SourceCategory> sourceCategories = budget.categories().stream()
                .collect(Collectors.toMap(SourceCategory::id, Function.identity()));
        List<Transaction> rows = new ArrayList<>();
        counts.transactionsSkipped = budget.transactions().size() - importable.size();
        for (SourceTransaction tx : importable) {
            String externalId = PREFIX + tx.id();
            if (alreadyImported.contains(externalId)) {
                counts.transactionsSkipped++;
                continue;
            }
            rows.add(toTransaction(tx, externalId, targetAccounts.get(tx.accountId()), request.currency(),
                    sourceCategories.get(tx.categoryId()), targetCategories, transferCategory));
        }
        transactions.saveAll(rows);
        counts.transactionsImported = rows.size();

        for (Account account : ledgerAccounts(budget, targetAccounts)) {
            account.setCurrentBalance(transactions.sumAmountByAccountId(account.getId()));
            accounts.save(account);
            persistence.reconstructSnapshotsFromDb(account);
        }
        return counts.result();
    }

    private Map<String, Account> resolveAccounts(ParsedActualBudget budget, Map<String, AccountMapping> mappings,
            String currency, Long memberId) {
        Map<String, Account> targets = new HashMap<>();
        Set<Long> used = new HashSet<>();
        for (SourceAccount source : budget.accounts()) {
            AccountMapping mapping = mappings.get(source.id());
            Account target = switch (mapping.action()) {
                case SKIP -> null;
                case MAP_EXISTING -> {
                    if (mapping.targetAccountId() == null) {
                        throw bad("Choose the account '" + source.name() + "' maps to");
                    }
                    Account existing = accounts.findByIdAndMemberId(mapping.targetAccountId(), memberId)
                            .orElseThrow(() -> bad("Target account not found"));
                    requireLedger(existing.getType(), existing.getCurrency(), currency);
                    yield existing;
                }
                case CREATE_NEW -> {
                    requireNewAccount(mapping.newAccount(), currency);
                    String externalId = PREFIX + source.id();
                    if (accounts.existsSoftDeletedByExternalAccountIdAndMemberId(externalId, memberId)) {
                        throw bad("The account '" + source.name() + "' was imported before and then deleted");
                    }
                    Account reused = accounts.findByExternalAccountIdAndMemberId(externalId, memberId).orElse(null);
                    if (reused != null) {
                        requireLedger(reused.getType(), reused.getCurrency(), currency);
                    }
                    yield reused;
                }
            };
            if (target != null) {
                if (!used.add(target.getId())) {
                    throw bad("Several Actual accounts cannot map to the same Picsou account");
                }
                targets.put(source.id(), target);
            }
        }
        return targets;
    }

    private static void requireLedger(AccountType type, String accountCurrency, String currency) {
        if (type == null || NON_LEDGER_TYPES.contains(type)) {
            throw bad("Investment and property accounts cannot receive Actual Budget transactions");
        }
        if (!currency.equals(accountCurrency)) {
            throw bad("Target account currency does not match the budget currency");
        }
    }

    private static void requireNewAccount(NewAccountDetails details, String currency) {
        if (details == null || blank(details.name()) || details.name().length() > MAX_NAME
                || details.provider() != null && details.provider().length() > MAX_NAME
                || details.color() != null && !COLOR.matcher(details.color()).matches()
                || details.currency() != null && !details.currency().equals(currency)) {
            throw bad("Invalid new account details");
        }
        requireLedger(details.type(), currency, currency);
    }

    /** Resolves MAP_EXISTING targets and the categories an earlier import already created. */
    private Map<String, Category> resolveCategories(ParsedActualBudget budget,
            Map<String, CategoryMapping> mappings, Long memberId) {
        Map<String, Category> bySlug = categoriesBySlug(memberId);
        Map<String, Category> resolved = new HashMap<>();
        for (SourceCategory source : budget.categories()) {
            CategoryMapping mapping = mappings.get(source.id());
            CategoryKind kind = kindOf(source);
            switch (mapping.action()) {
                case MAP_EXISTING -> {
                    Category target = mapping.targetCategoryId() == null ? null : categories
                            .findByIdAndMemberId(mapping.targetCategoryId(), memberId)
                            .filter(category -> !category.isArchived()).orElse(null);
                    if (target == null) {
                        throw bad("Target category for '" + source.name() + "' not found or archived");
                    }
                    if (target.getKind() != kind) {
                        throw bad("Category '" + source.name() + "' must map to a " + kind + " category");
                    }
                    resolved.put(source.id(), target);
                }
                case CREATE_NEW -> {
                    if (blank(mapping.name()) || mapping.name().strip().length() > MAX_NAME) {
                        throw bad("Invalid name for category '" + source.name() + "'");
                    }
                    Category existing = bySlug.get(PREFIX + source.id());
                    if (existing != null) {
                        requireReusable(existing, kind);
                        resolved.put(source.id(), existing);
                    }
                }
                case UNCATEGORIZED -> { }
            }
        }
        return resolved;
    }

    /**
     * Actual groups become Picsou parent categories, created only for groups that gain a new child.
     * Actual groups hold a single kind, which Picsou requires of a parent and its children.
     */
    private Map<String, Category> resolveGroupParents(ParsedActualBudget budget,
            Map<String, CategoryMapping> mappings, Map<String, Category> resolved, Long memberId) {
        Map<String, Category> bySlug = categoriesBySlug(memberId);
        Map<String, Category> parents = new HashMap<>();
        for (SourceCategory source : budget.categories()) {
            if (mappings.get(source.id()).action() != CategoryMappingAction.CREATE_NEW
                    || resolved.containsKey(source.id()) || source.groupId() == null) {
                continue;
            }
            Category existing = bySlug.get(PREFIX + "group_" + source.groupId());
            if (existing != null) {
                requireReusable(existing, kindOf(source));
                if (existing.getParent() != null) {
                    throw bad("The category created for group '" + source.groupName() + "' is no longer top-level");
                }
                parents.put(source.groupId(), existing);
            }
        }
        return parents;
    }

    private static void requireReusable(Category existing, CategoryKind kind) {
        if (existing.isArchived()) {
            throw bad("Category '" + existing.getName() + "' from an earlier import is archived; restore it or map it");
        }
        if (existing.getKind() != kind) {
            throw bad("Category '" + existing.getName() + "' from an earlier import changed kind");
        }
    }

    private Map<String, Category> categoriesBySlug(Long memberId) {
        return categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId).stream()
                .filter(category -> category.getSlug() != null)
                .collect(Collectors.toMap(Category::getSlug, Function.identity(), (first, ignored) -> first));
    }

    /**
     * External ids of rows already stored, looked up by id rather than by date: the user may have
     * moved an imported row to another date. A row stored in another account than the one it maps
     * to now is refused rather than duplicated or moved.
     */
    private Set<String> alreadyImported(List<SourceTransaction> importable, Map<String, Account> targets,
            Long memberId) {
        Map<String, String> accountByExternalId = new HashMap<>();
        importable.forEach(tx -> accountByExternalId.put(PREFIX + tx.id(), tx.accountId()));
        List<String> externalIds = List.copyOf(accountByExternalId.keySet());
        Set<String> stored = new HashSet<>();
        for (int from = 0; from < externalIds.size(); from += LOOKUP_BATCH) {
            List<String> batch = externalIds.subList(from, Math.min(from + LOOKUP_BATCH, externalIds.size()));
            for (TransactionRepository.StoredExternalId existing : transactions.findStoredExternalIds(memberId, batch)) {
                Account target = targets.get(accountByExternalId.get(existing.getExternalId()));
                if (target == null || !Objects.equals(target.getId(), existing.getAccountId())) {
                    throw bad("Some transactions were already imported into another account; "
                            + "map the Actual account to the account it was imported into");
                }
                stored.add(existing.getExternalId());
            }
        }
        return stored;
    }

    /**
     * Accounts an Actual import created follow their ledger, whichever mapping a re-import uses;
     * accounts the user created keep the balance they set.
     */
    private static List<Account> ledgerAccounts(ParsedActualBudget budget, Map<String, Account> targets) {
        return budget.accounts().stream()
                .map(source -> targets.get(source.id()))
                .filter(account -> account != null && account.getExternalAccountId() != null
                        && account.getExternalAccountId().startsWith(PREFIX))
                .toList();
    }

    private void createAccounts(ParsedActualBudget budget, Map<String, AccountMapping> mappings,
            Map<String, Account> targets, FamilyMember member, String currency, Counts counts) {
        for (SourceAccount source : budget.accounts()) {
            AccountMapping mapping = mappings.get(source.id());
            switch (mapping.action()) {
                case SKIP -> counts.accountsSkipped++;
                case MAP_EXISTING -> counts.accountsMapped++;
                case CREATE_NEW -> {
                    Account account = targets.get(source.id());
                    if (account == null) {
                        NewAccountDetails details = mapping.newAccount();
                        account = accounts.save(Account.builder().member(member).name(details.name().strip())
                                .type(details.type()).provider(blank(details.provider()) ? null : details.provider())
                                .currency(currency).currentBalance(BigDecimal.ZERO)
                                .isManual(true).color(details.color() == null ? DEFAULT_COLOR : details.color())
                                .externalAccountId(PREFIX + source.id()).build());
                        targets.put(source.id(), account);
                        counts.accountsCreated++;
                    } else {
                        counts.accountsMapped++;
                    }
                }
            }
        }
    }

    private void createCategories(ParsedActualBudget budget, Map<String, CategoryMapping> mappings,
            Map<String, Category> resolved, Map<String, Category> groupParents, FamilyMember member,
            Counts counts) {
        for (SourceCategory source : budget.categories()) {
            CategoryMapping mapping = mappings.get(source.id());
            if (mapping.action() != CategoryMappingAction.CREATE_NEW || resolved.containsKey(source.id())) {
                continue;
            }
            Category parent = null;
            if (source.groupId() != null) {
                parent = groupParents.get(source.groupId());
                if (parent == null) {
                    parent = categories.save(Category.builder().member(member)
                            .name(truncate(nameOr(source.groupName(), "Actual Budget"), MAX_NAME))
                            .slug(PREFIX + "group_" + source.groupId()).kind(kindOf(source))
                            .color(DEFAULT_COLOR).build());
                    groupParents.put(source.groupId(), parent);
                    counts.categoriesCreated++;
                }
            }
            resolved.put(source.id(), categories.save(Category.builder().member(member)
                    .name(mapping.name().strip()).slug(PREFIX + source.id()).kind(kindOf(source))
                    .color(DEFAULT_COLOR).parent(parent).build()));
            counts.categoriesCreated++;
        }
    }

    /** The member's default "Virement interne" category, else the one an earlier import created. */
    private Category existingTransferCategory(Long memberId) {
        Map<String, Category> bySlug = categoriesBySlug(memberId);
        for (String slug : List.of(TRANSFER_SLUG, ACTUAL_TRANSFER_SLUG)) {
            Category category = bySlug.get(slug);
            if (category != null && !category.isArchived() && category.getKind() == CategoryKind.TRANSFER) {
                return category;
            }
        }
        if (bySlug.containsKey(ACTUAL_TRANSFER_SLUG)) {
            throw bad("The 'Actual Budget transfer' category is archived or no longer a transfer category");
        }
        return null;
    }

    private Category createTransferCategory(FamilyMember member) {
        return categories.save(Category.builder().member(member).name("Actual Budget transfer")
                .slug(ACTUAL_TRANSFER_SLUG).kind(CategoryKind.TRANSFER).color("#64748b").build());
    }

    private static Transaction toTransaction(SourceTransaction tx, String externalId, Account account,
            String currency, SourceCategory sourceCategory, Map<String, Category> targetCategories,
            Category transferCategory) {
        Category category = tx.kind() == Kind.REGULAR
                ? sourceCategory == null ? null : targetCategories.get(sourceCategory.id())
                : transferCategory;
        String description = !blank(tx.notes()) ? tx.notes().strip()
                : !blank(tx.payee()) ? tx.payee().strip()
                : tx.kind() == Kind.STARTING_BALANCE ? "Starting balance" : "Actual Budget transaction";
        return Transaction.builder()
                .account(account)
                .date(tx.date())
                .amount(tx.amount())
                .description(truncate(description, MAX_DESCRIPTION))
                .counterparty(blank(tx.payee()) ? null : truncate(tx.payee().strip(), MAX_DESCRIPTION))
                .category(sourceCategory == null ? null : truncate(sourceCategory.name(), MAX_NAME))
                .categoryRef(category)
                .categoryManual(category != null)
                .nativeCurrency(currency)
                .externalId(externalId)
                .isManual(true)
                .build();
    }

    // --- helpers ----------------------------------------------------------------------------

    private static <M> Map<String, M> bySource(List<M> provided, Function<M, String> sourceId, List<String> expected) {
        Map<String, M> mapped = new LinkedHashMap<>();
        for (M mapping : provided) {
            if (mapped.putIfAbsent(sourceId.apply(mapping), mapping) != null) {
                throw bad("Each Actual account and category must be mapped exactly once");
            }
        }
        if (!mapped.keySet().equals(new HashSet<>(expected))) {
            throw bad("Each Actual account and category must be mapped exactly once");
        }
        return mapped;
    }

    private static CategoryKind kindOf(SourceCategory source) {
        return source.income() ? CategoryKind.INCOME : CategoryKind.EXPENSE;
    }

    private boolean isExpired(CachedPreview cached) {
        return !cached.createdAt().plus(PREVIEW_TTL).isAfter(clock.instant());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void cleanupExpiredPreviews() {
        previews.values().removeIf(this::isExpired);
    }

    private static String nameOr(String value, String fallback) {
        return blank(value) ? fallback : value.strip();
    }

    private static String truncate(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    private static final class Counts {
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
