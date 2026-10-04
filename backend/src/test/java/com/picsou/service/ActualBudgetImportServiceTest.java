package com.picsou.service;

import com.picsou.dto.ActualBudgetImportDtos.AccountMapping;
import com.picsou.dto.ActualBudgetImportDtos.AccountPreview;
import com.picsou.dto.ActualBudgetImportDtos.CategoryMapping;
import com.picsou.dto.ActualBudgetImportDtos.CategoryMappingAction;
import com.picsou.dto.ActualBudgetImportDtos.Preview;
import com.picsou.dto.ActualBudgetImportDtos.Request;
import com.picsou.dto.ActualBudgetImportDtos.Result;
import com.picsou.dto.ActualBudgetImportDtos.TransactionPreview;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.actual.ActualBudgetFileParser;
import com.picsou.imports.actual.ActualBudgetFixture;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the service with the real parser over a synthetic Actual file, and repositories backed
 * by in-memory lists so a second import sees what the first one stored.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActualBudgetImportServiceTest {

    private static final long MEMBER = 1L;

    @Mock AccountRepository accountRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository memberRepository;
    @Mock FinaryPersistenceHelper persistence;

    private final List<Account> accounts = new ArrayList<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<Transaction> transactions = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private final FamilyMember member = FamilyMember.builder().id(MEMBER).build();
    private Category defaultTransfer;
    private ActualBudgetImportService service;

    @BeforeEach
    void setUp() {
        defaultTransfer = save(Category.builder().member(member).name("Virement interne").slug("virement-interne")
                .kind(CategoryKind.TRANSFER).build());
        when(memberRepository.findById(MEMBER)).thenReturn(Optional.of(member));
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> save(call.<Account>getArgument(0)));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenAnswer(call -> List.copyOf(accounts));
        when(accountRepository.findByIdAndMemberId(anyLong(), eq(MEMBER))).thenAnswer(call ->
                accounts.stream().filter(a -> a.getId().equals(call.getArgument(0))).findFirst());
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(MEMBER))).thenAnswer(call ->
                accounts.stream().filter(a -> call.getArgument(0).equals(a.getExternalAccountId())).findFirst());
        when(categoryRepository.save(any(Category.class))).thenAnswer(call -> save(call.<Category>getArgument(0)));
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(MEMBER))
                .thenAnswer(call -> List.copyOf(categories));
        when(categoryRepository.findAllByMemberIdAndArchivedFalseOrderBySortOrderAscIdAsc(MEMBER))
                .thenAnswer(call -> categories.stream().filter(c -> !c.isArchived()).toList());
        when(categoryRepository.findByIdAndMemberId(anyLong(), eq(MEMBER))).thenAnswer(call ->
                categories.stream().filter(c -> c.getId().equals(call.getArgument(0))).findFirst());
        when(transactionRepository.saveAll(any())).thenAnswer(call -> {
            List<Transaction> rows = call.getArgument(0);
            transactions.addAll(rows);
            return rows;
        });
        when(transactionRepository.findStoredExternalIds(eq(MEMBER), any())).thenAnswer(call -> {
            Collection<String> ids = call.getArgument(1);
            return transactions.stream().filter(t -> ids.contains(t.getExternalId()))
                    .map(t -> stored(t.getExternalId(), t.getAccount().getId())).toList();
        });
        when(transactionRepository.sumAmountByAccountId(anyLong())).thenAnswer(call -> transactions.stream()
                .filter(t -> t.getAccount().getId().equals(call.getArgument(0)))
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        service = new ActualBudgetImportService(new ActualBudgetFileParser(), accountRepository, categoryRepository,
                transactionRepository, memberRepository, persistence, clock);
    }

    @Test
    void previewSummarisesTheBudgetWithoutWriting() {
        Preview preview = preview();

        assertThat(preview.currency()).isEqualTo("EUR");
        assertThat(preview.totalTransactions()).isEqualTo(8);
        assertThat(preview.transferTransactions()).isEqualTo(3);
        assertThat(preview.accounts()).containsExactly(
                new AccountPreview("acc-checking", "Everyday", false, false, AccountType.CHECKING,
                        new BigDecimal("2915.66"), 7, null),
                new AccountPreview("acc-savings", "Rainy day", true, false, AccountType.OTHER,
                        new BigDecimal("500.00"), 1, null));
        assertThat(preview.sampleTransactions()).extracting(TransactionPreview::sourceId)
                .startsWith("t-salary", "t-lonely-parent");
        assertThat(preview.existingCategories()).hasSize(1);
        assertThat(accounts).isEmpty();
        assertThat(transactions).isEmpty();
    }

    @Test
    void importsAccountsCategoriesAndTransactionsExactly() {
        Preview preview = preview();

        Result result = service.executeImport(createEverything(preview), MEMBER);

        assertThat(result).isEqualTo(new Result(2, 0, 0, 6, 8, 0));
        Account checking = accountWithExternalId("actual_acc-checking");
        assertThat(checking.getCurrency()).isEqualTo("EUR");
        assertThat(checking.isManual()).isTrue();
        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("2915.66");
        assertThat(accountWithExternalId("actual_acc-savings").getCurrentBalance()).isEqualByComparingTo("500.00");

        Map<String, Transaction> byId = transactionsByExternalId();
        Transaction groceries = byId.get("actual_t-groceries");
        assertThat(groceries.getAccount()).isSameAs(checking);
        assertThat(groceries.getDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(groceries.getAmount()).isEqualTo(new BigDecimal("-12.34"));
        assertThat(groceries.getDescription()).isEqualTo("Market");
        assertThat(groceries.getCounterparty()).isEqualTo("Market");
        assertThat(groceries.getNativeCurrency()).isEqualTo("EUR");
        assertThat(groceries.getCategoryRef().getName()).isEqualTo("Groceries");
        assertThat(groceries.getCategoryRef().getParent().getName()).isEqualTo("Food");
        assertThat(groceries.isCategoryManual()).isTrue();
        Transaction salary = byId.get("actual_t-salary");
        assertThat(salary.getDescription()).isEqualTo("January pay");
        assertThat(salary.getCounterparty()).isEqualTo("Employer");
        assertThat(salary.getCategoryRef().getKind()).isEqualTo(CategoryKind.INCOME);
    }

    @Test
    void transferLegsAndStartingBalancesNeverCountAsIncomeOrSpending() {
        service.executeImport(createEverything(preview()), MEMBER);

        Map<String, Transaction> byId = transactionsByExternalId();
        assertThat(byId.get("actual_t-transfer-out").getCategoryRef()).isSameAs(defaultTransfer);
        assertThat(byId.get("actual_t-transfer-in").getCategoryRef()).isSameAs(defaultTransfer);
        assertThat(byId.get("actual_t-transfer-out").getAmount()).isEqualTo(new BigDecimal("-500.00"));
        assertThat(byId.get("actual_t-transfer-in").getAmount()).isEqualTo(new BigDecimal("500.00"));
        assertThat(byId.get("actual_t-start").getCategoryRef()).isSameAs(defaultTransfer);
        assertThat(transactions).filteredOn(t -> t.getCategoryRef() == defaultTransfer).hasSize(3);
    }

    @Test
    void createsItsOwnTransferCategoryWhenTheDefaultOneIsArchived() {
        defaultTransfer.setArchived(true);

        service.executeImport(createEverything(preview()), MEMBER);

        Category transfer = transactionsByExternalId().get("actual_t-transfer-in").getCategoryRef();
        assertThat(transfer.getSlug()).isEqualTo("actual-transfer");
        assertThat(transfer.getKind()).isEqualTo(CategoryKind.TRANSFER);
    }

    @Test
    void reimportingTheSameFileCreatesNothingNew() {
        service.executeImport(createEverything(preview()), MEMBER);
        int accountsAfterFirst = accounts.size();
        int categoriesAfterFirst = categories.size();

        Result second = service.executeImport(createEverything(preview()), MEMBER);

        assertThat(second).isEqualTo(new Result(0, 2, 0, 0, 0, 8));
        assertThat(accounts).hasSize(accountsAfterFirst);
        assertThat(categories).hasSize(categoriesAfterFirst);
        assertThat(transactions).hasSize(8);
    }

    @Test
    void previewPointsEachSourceAccountAtTheAccountAnEarlierImportCreated() {
        service.executeImport(createEverything(preview()), MEMBER);
        save(Account.builder().member(member).name("Everyday").type(AccountType.CHECKING)
                .currency("EUR").currentBalance(BigDecimal.ZERO).build());

        Preview second = preview();

        assertThat(second.accounts()).extracting(AccountPreview::importedAccountId).containsExactly(
                accountWithExternalId("actual_acc-checking").getId(),
                accountWithExternalId("actual_acc-savings").getId());
    }

    @Test
    void reimportingOntoTheAccountAnImportCreatedRecomputesItsBalance() {
        service.executeImport(createEverything(preview()), MEMBER);
        Account checking = accountWithExternalId("actual_acc-checking");
        Preview newer = preview(ActualBudgetFixture.household()
                .tx("t-refund", "acc-checking", 20000, 20240205, "cat-groceries", "p-market", null, null));
        Request request = withAccount(createEverything(newer), "acc-checking",
                new AccountMapping("acc-checking", FinaryMappingAction.MAP_EXISTING, checking.getId(), null));

        Result result = service.executeImport(request, MEMBER);

        assertThat(result).isEqualTo(new Result(0, 2, 0, 0, 1, 8));
        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("3115.66");
        verify(persistence, times(2)).reconstructSnapshotsFromDb(checking);
    }

    @Test
    void aRowMovedToAnotherDateIsNotImportedAgain() {
        service.executeImport(createEverything(preview()), MEMBER);
        transactionsByExternalId().get("actual_t-groceries").setDate(LocalDate.of(2030, 6, 1));

        Result second = service.executeImport(createEverything(preview()), MEMBER);

        assertThat(second).isEqualTo(new Result(0, 2, 0, 0, 0, 8));
        assertThat(transactions).hasSize(8);
    }

    @Test
    void lookupsOfStoredRowsAreBatched() {
        ActualBudgetFixture fixture = ActualBudgetFixture.household();
        for (int i = 0; i < 1_500; i++) {
            fixture.tx("t-bulk-" + i, "acc-checking", -100, 20240301, null, "p-market", null, null);
        }

        service.executeImport(createEverything(preview(fixture)), MEMBER);

        verify(transactionRepository, times(2)).findStoredExternalIds(eq(MEMBER), any());
        assertThat(transactions).hasSize(1_508);
    }

    @Test
    void refusesToDuplicateRowsAlreadyImportedIntoAnotherAccount() {
        service.executeImport(createEverything(preview()), MEMBER);
        Account other = save(Account.builder().member(member).name("Other").type(AccountType.CHECKING)
                .currency("EUR").currentBalance(BigDecimal.ZERO).build());
        Preview preview = preview();
        Request request = withAccount(createEverything(preview), "acc-checking",
                new AccountMapping("acc-checking", FinaryMappingAction.MAP_EXISTING, other.getId(), null));

        assertThatThrownBy(() -> service.executeImport(request, MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already imported into another account");
        assertThat(transactions).hasSize(8);
    }

    @Test
    void skippedAccountsKeepTheirRowsOutButTheOtherTransferLegStaysNeutral() {
        Preview preview = preview();
        Request request = withAccount(createEverything(preview), "acc-savings",
                new AccountMapping("acc-savings", FinaryMappingAction.SKIP, null, null));

        Result result = service.executeImport(request, MEMBER);

        assertThat(result).isEqualTo(new Result(1, 0, 1, 6, 7, 1));
        assertThat(transactionsByExternalId()).doesNotContainKey("actual_t-transfer-in");
        assertThat(transactionsByExternalId().get("actual_t-transfer-out").getCategoryRef()).isSameAs(defaultTransfer);
    }

    @Test
    void mapsOntoExistingAccountsAndCategoriesWithoutTouchingTheirBalance() {
        Account everyday = save(Account.builder().member(member).name("Everyday").type(AccountType.CHECKING)
                .currency("EUR").currentBalance(new BigDecimal("12.00")).build());
        Category food = save(Category.builder().member(member).name("Food").kind(CategoryKind.EXPENSE).build());
        Preview preview = preview();
        Request base = withAccount(createEverything(preview), "acc-checking",
                new AccountMapping("acc-checking", FinaryMappingAction.MAP_EXISTING, everyday.getId(), null));
        List<CategoryMapping> categoryMappings = base.categoryMappings().stream()
                .map(m -> m.sourceId().equals("cat-groceries")
                        ? new CategoryMapping("cat-groceries", CategoryMappingAction.MAP_EXISTING, food.getId(), null)
                        : m.sourceId().equals("cat-restaurants")
                        ? new CategoryMapping("cat-restaurants", CategoryMappingAction.UNCATEGORIZED, null, null)
                        : m)
                .toList();

        Result result = service.executeImport(new Request(base.fileToken(), "EUR", base.accountMappings(),
                categoryMappings), MEMBER);

        assertThat(result.accountsMapped()).isEqualTo(1);
        assertThat(everyday.getCurrentBalance()).isEqualByComparingTo("12.00");
        Transaction groceries = transactionsByExternalId().get("actual_t-split/1");
        assertThat(groceries.getAccount()).isSameAs(everyday);
        assertThat(groceries.getCategoryRef()).isSameAs(food);
        Transaction lunch = transactionsByExternalId().get("actual_t-split/2");
        assertThat(lunch.getCategoryRef()).isNull();
        assertThat(lunch.isCategoryManual()).isFalse();
        assertThat(lunch.getCategory()).isEqualTo("Restaurants");
    }

    @Test
    void rejectsACurrencyOtherThanTheBudgets() {
        Request request = createEverything(preview());

        assertThatThrownBy(() -> service.executeImport(new Request(request.fileToken(), "USD",
                request.accountMappings(), request.categoryMappings()), MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The budget currency is EUR, not USD");
        assertThat(accounts).isEmpty();
    }

    @Test
    void rejectsAnInvestmentAccountTargetBeforeWritingAnything() {
        Account pea = save(Account.builder().member(member).name("PEA").type(AccountType.PEA)
                .currency("EUR").currentBalance(BigDecimal.ZERO).build());
        Preview preview = preview();
        Request request = withAccount(createEverything(preview), "acc-savings",
                new AccountMapping("acc-savings", FinaryMappingAction.MAP_EXISTING, pea.getId(), null));

        assertThatThrownBy(() -> service.executeImport(request, MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Investment and property accounts");
        assertThat(accounts).containsExactly(pea);
        assertThat(transactions).isEmpty();
    }

    @Test
    void rejectsACategoryMappedOntoTheWrongKind() {
        Category expense = save(Category.builder().member(member).name("Shopping").kind(CategoryKind.EXPENSE).build());
        Request base = createEverything(preview());
        List<CategoryMapping> categoryMappings = base.categoryMappings().stream()
                .map(m -> m.sourceId().equals("cat-salary")
                        ? new CategoryMapping("cat-salary", CategoryMappingAction.MAP_EXISTING, expense.getId(), null)
                        : m)
                .toList();

        assertThatThrownBy(() -> service.executeImport(new Request(base.fileToken(), "EUR", base.accountMappings(),
                categoryMappings), MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category 'Salary' must map to a INCOME category");
        assertThat(transactions).isEmpty();
    }

    @Test
    void rejectsIncompleteMappings() {
        Request base = createEverything(preview());

        assertThatThrownBy(() -> service.executeImport(new Request(base.fileToken(), "EUR",
                base.accountMappings().subList(0, 1), base.categoryMappings()), MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Each Actual account and category must be mapped exactly once");
    }

    @Test
    void aPreviewIsSingleUseAndBoundToItsMember() {
        Request request = createEverything(preview());

        assertThatThrownBy(() -> service.executeImport(request, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ActualBudgetImportService.PREVIEW_EXPIRED);
        service.executeImport(request, MEMBER);
        assertThatThrownBy(() -> service.executeImport(request, MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ActualBudgetImportService.PREVIEW_EXPIRED);
    }

    @Test
    void anExpiredPreviewIsRefused() {
        Request request = createEverything(preview());
        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.executeImport(request, MEMBER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ActualBudgetImportService.PREVIEW_EXPIRED);
    }

    @Test
    void aRejectedUploadLeavesNoPreview() {
        MockMultipartFile file = new MockMultipartFile("file", "budget.zip", "application/zip", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.preview(file, MEMBER)).isInstanceOf(IllegalArgumentException.class);
    }

    private Preview preview() {
        return preview(ActualBudgetFixture.household());
    }

    private Preview preview(ActualBudgetFixture fixture) {
        return service.preview(new MockMultipartFile("file", "budget.zip", "application/zip", fixture.zip()), MEMBER);
    }

    private static TransactionRepository.StoredExternalId stored(String externalId, Long accountId) {
        return new TransactionRepository.StoredExternalId() {
            @Override
            public String getExternalId() {
                return externalId;
            }

            @Override
            public Long getAccountId() {
                return accountId;
            }
        };
    }

    private static Request createEverything(Preview preview) {
        List<AccountMapping> accountMappings = preview.accounts().stream()
                .map(a -> new AccountMapping(a.sourceId(), FinaryMappingAction.CREATE_NEW, null,
                        new NewAccountDetails(a.name(), a.suggestedType(), null, "EUR", null)))
                .toList();
        List<CategoryMapping> categoryMappings = preview.categories().stream()
                .map(c -> new CategoryMapping(c.sourceId(), CategoryMappingAction.CREATE_NEW, null, c.name()))
                .toList();
        return new Request(preview.fileToken(), "EUR", accountMappings, categoryMappings);
    }

    private static Request withAccount(Request request, String sourceId, AccountMapping replacement) {
        return new Request(request.fileToken(), request.currency(), request.accountMappings().stream()
                .map(m -> m.sourceId().equals(sourceId) ? replacement : m).toList(), request.categoryMappings());
    }

    private Account accountWithExternalId(String externalId) {
        return accounts.stream().filter(a -> externalId.equals(a.getExternalAccountId())).findFirst().orElseThrow();
    }

    private Map<String, Transaction> transactionsByExternalId() {
        return transactions.stream().collect(Collectors.toMap(Transaction::getExternalId, Function.identity()));
    }

    private Account save(Account account) {
        if (account.getId() == null) {
            account.setId(1000L + accounts.size());
        }
        if (accounts.stream().noneMatch(a -> a == account)) {
            accounts.add(account);
        }
        return account;
    }

    private Category save(Category category) {
        if (category.getId() == null) {
            category.setId(2000L + categories.size());
        }
        if (categories.stream().noneMatch(c -> Objects.equals(c.getId(), category.getId()))) {
            categories.add(category);
        }
        return category;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
