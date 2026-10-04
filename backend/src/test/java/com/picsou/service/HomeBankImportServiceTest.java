package com.picsou.service;

import com.picsou.dto.HomeBankImportDtos.Preview;
import com.picsou.dto.HomeBankImportDtos.Request;
import com.picsou.dto.HomeBankImportDtos.AccountMapping;
import com.picsou.dto.HomeBankImportDtos.CategoryMapping;
import com.picsou.dto.HomeBankImportDtos.CategoryMappingAction;
import com.picsou.dto.FinaryMappingAction;
import com.picsou.imports.ImportPreviewStore;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.imports.homebank.ParsedHomeBankData;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.model.Transaction;
import com.picsou.repository.*;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.dto.NewAccountDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeBankImportServiceTest {
    @Mock HomeBankFileParser parser;
    @Mock AccountRepository accountRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock BalanceSnapshotRepository balanceSnapshotRepository;
    @Mock FinaryPersistenceHelper persistenceHelper;

    @Test
    void preview_isReadOnlyAndReportsLedgerBalanceExcludingForecasts() {
        var account = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR",
                new BigDecimal("10.20"), false);
        var regular = new ParsedHomeBankData.SourceTransaction("t1", "a", LocalDate.of(2024, 1, 2),
                new BigDecimal("3.40"), "EUR", "Marché", "memo", null, null, false);
        var forecast = new ParsedHomeBankData.SourceTransaction("t2", "a", LocalDate.of(2024, 1, 3),
                new BigDecimal("99"), "EUR", null, null, null, null, true);
        when(parser.parse(any(), eq("bank.hbk"), isNull()))
                .thenReturn(new ParsedHomeBankData(List.of(account), List.of(), List.of(regular, forecast)));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(7L)).thenReturn(List.of());
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper,
                new ImportPreviewStore<>(Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
                        Duration.ofMinutes(30), 32));

        Preview result = service.preview(new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1}), null, 7L);

        assertThat(result.accounts()).singleElement().satisfies(preview -> {
            assertThat(preview.balance()).isEqualByComparingTo("13.60");
            assertThat(preview.transactionCount()).isEqualTo(1);
        });
        assertThat(result.totalTransactions()).isEqualTo(1);
        assertThat(result.forecastTransactions()).isEqualTo(1);
        assertThat(result.sampleTransactions()).hasSize(1);
        verifyNoInteractions(transactionRepository, familyMemberRepository, balanceSnapshotRepository, persistenceHelper);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void incompleteCategoryMappingsFailBeforeAnyAccountWrite() {
        var account = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR", BigDecimal.ZERO, false);
        var category = new ParsedHomeBankData.SourceCategory("c", "Courses", null, false);
        when(parser.parse(any(), eq("bank.hbk"), isNull())).thenReturn(new ParsedHomeBankData(List.of(account), List.of(category), List.of()));
        var store = new ImportPreviewStore<ParsedHomeBankData>(Clock.systemUTC(), Duration.ofMinutes(30), 32);
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper, store);
        Preview preview = service.preview(new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1}), null, 7L);
        when(familyMemberRepository.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(new com.picsou.model.FamilyMember()));
        var request = new Request(preview.fileToken(), List.of(new AccountMapping("a", FinaryMappingAction.SKIP, null, null)), List.of());

        assertThatThrownBy(() -> service.executeImport(request, 7L)).isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository, never()).save(any());
        assertThat(store.get(preview.fileToken(), 7L)).isNotNull();
    }

    @Test
    void childFirstCategoriesAreCreatedParentFirstAndMappedTransactionIsManual() {
        var member = new FamilyMember();
        var account = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR",
                BigDecimal.ZERO, false);
        var child = new ParsedHomeBankData.SourceCategory("child", "Marché", "parent", false);
        var parent = new ParsedHomeBankData.SourceCategory("parent", "Vie", null, false);
        var transaction = new ParsedHomeBankData.SourceTransaction("tx", "a", LocalDate.of(2024, 1, 2),
                new BigDecimal("-3.25"), "EUR", "Payee", " ", "child", null, false);
        when(parser.parse(any(), eq("bank.hbk"), isNull())).thenReturn(
                new ParsedHomeBankData(List.of(account), List.of(child, parent), List.of(transaction)));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(7L)).thenReturn(List.of());
        when(familyMemberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
        when(familyMemberRepository.findById(7L)).thenReturn(Optional.of(member));
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("homebank_a", 7L)).thenReturn(false);
        when(accountRepository.findByExternalAccountIdAndMemberId("homebank_a", 7L)).thenReturn(Optional.empty());
        when(accountRepository.save(any())).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        List<Category> savedCategories = new ArrayList<>();
        when(categoryRepository.save(any())).thenAnswer(invocation -> {
            Category saved = invocation.getArgument(0);
            saved.setId((long) savedCategories.size() + 1);
            savedCategories.add(saved);
            return saved;
        });
        List<Transaction> savedTransactions = new ArrayList<>();
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenReturn(false);
        when(transactionRepository.findByAccountMemberIdAndExternalIdIn(eq(7L), anyCollection())).thenReturn(List.of());
        when(transactionRepository.sumAmountByAccountId(10L)).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.save(any())).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            savedTransactions.add(saved);
            return saved;
        });

        var store = new ImportPreviewStore<ParsedHomeBankData>(Clock.systemUTC(), Duration.ofMinutes(30), 8);
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper, store);
        Preview preview = service.preview(new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1}), null, 7L);
        var request = new Request(preview.fileToken(),
                List.of(new AccountMapping("a", FinaryMappingAction.CREATE_NEW, null,
                        new NewAccountDetails("Compte", AccountType.CHECKING, null, "EUR", null))),
                List.of(new CategoryMapping("child", CategoryMappingAction.CREATE_NEW, null, "Marché"),
                        new CategoryMapping("parent", CategoryMappingAction.CREATE_NEW, null, "Vie")));

        var result = service.executeImport(request, 7L);

        assertThat(result.accountsCreated()).isEqualTo(1);
        assertThat(result.categoriesCreated()).isEqualTo(2);
        List<Category> importedCategories = savedCategories.stream()
                .filter(category -> category.getKind() != CategoryKind.TRANSFER).toList();
        assertThat(importedCategories).extracting(Category::getSlug)
                .containsExactly("homebank_parent", "homebank_child");
        assertThat(importedCategories.get(1).getParent()).isSameAs(importedCategories.get(0));
        assertThat(savedTransactions).anySatisfy(saved -> {
            if ("homebank_tx".equals(saved.getExternalId())) {
                assertThat(saved.getDescription()).isEqualTo(" ");
                assertThat(saved.isCategoryManual()).isTrue();
                assertThat(saved.getAmount()).isEqualByComparingTo("-3.25");
            }
        });
    }

    @Test
    void lateOversizedTransactionRejectsTheWholeImportBeforeAnyWrites() {
        var account = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR",
                BigDecimal.ZERO, false);
        var first = new ParsedHomeBankData.SourceTransaction("tx1", "a", LocalDate.of(2024, 1, 2),
                BigDecimal.ONE, "EUR", null, null, null, null, false);
        var invalid = new ParsedHomeBankData.SourceTransaction("tx2", "a", LocalDate.of(2024, 1, 3),
                BigDecimal.ONE, "EUR", null, "x".repeat(256), null, null, false);
        when(parser.parse(any(), eq("bank.hbk"), isNull())).thenReturn(
                new ParsedHomeBankData(List.of(account), List.of(), List.of(first, invalid)));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(7L)).thenReturn(List.of());
        when(familyMemberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(new FamilyMember()));
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("homebank_a", 7L)).thenReturn(false);
        when(accountRepository.findByExternalAccountIdAndMemberId("homebank_a", 7L)).thenReturn(Optional.empty());
        var store = new ImportPreviewStore<ParsedHomeBankData>(Clock.systemUTC(), Duration.ofMinutes(30), 8);
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper, store);
        Preview preview = service.preview(new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1}), null, 7L);
        var request = new Request(preview.fileToken(),
                List.of(new AccountMapping("a", FinaryMappingAction.CREATE_NEW, null,
                        new NewAccountDetails("Compte", AccountType.CHECKING, null, "EUR", null))), List.of());

        assertThatThrownBy(() -> service.executeImport(request, 7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("text exceeds");

        verify(accountRepository, never()).save(any());
        verify(categoryRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        assertThat(store.get(preview.fileToken(), 7L)).isNotNull();
    }

    @Test
    void archivedHomeBankCategorySlugRejectsBeforeCreatingTheAccount() {
        var account = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR",
                BigDecimal.ZERO, false);
        var category = new ParsedHomeBankData.SourceCategory("c", "Courses", null, false);
        when(parser.parse(any(), eq("bank.hbk"), isNull())).thenReturn(
                new ParsedHomeBankData(List.of(account), List.of(category), List.of()));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        Category archived = Category.builder().slug("homebank_c").kind(CategoryKind.EXPENSE).archived(true).build();
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(7L)).thenReturn(List.of(archived));
        when(familyMemberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(new FamilyMember()));
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId("homebank_a", 7L)).thenReturn(false);
        when(accountRepository.findByExternalAccountIdAndMemberId("homebank_a", 7L)).thenReturn(Optional.empty());
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper,
                new ImportPreviewStore<>(Clock.systemUTC(), Duration.ofMinutes(30), 8));
        Preview preview = service.preview(new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1}), null, 7L);
        var request = new Request(preview.fileToken(),
                List.of(new AccountMapping("a", FinaryMappingAction.CREATE_NEW, null,
                        new NewAccountDetails("Compte", AccountType.CHECKING, null, "EUR", null))),
                List.of(new CategoryMapping("c", CategoryMappingAction.CREATE_NEW, null, "Courses")));

        assertThatThrownBy(() -> service.executeImport(request, 7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archived");

        verify(accountRepository, never()).save(any());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void mappedAccountWithDifferentCurrencyIsRejectedBeforeWrites() {
        assertUnsafeExistingAccountRejected(Account.builder().id(9L).type(AccountType.CHECKING)
                .currency("USD").currentBalance(BigDecimal.ZERO).isManual(false).build());
    }

    @Test
    void investmentAccountCannotBeSelectedAsAnImportTarget() {
        assertUnsafeExistingAccountRejected(Account.builder().id(9L).type(AccountType.PEA)
                .currency("EUR").currentBalance(BigDecimal.ZERO).isManual(false).build());
    }

    @Test
    void repreviewAndReimportDoesNotDuplicateStableAccountsCategoriesOrTransactions() {
        var member = new FamilyMember();
        var sourceAccount = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR",
                BigDecimal.ONE, false);
        var sourceCategory = new ParsedHomeBankData.SourceCategory("c", "Courses", null, false);
        var sourceTransaction = new ParsedHomeBankData.SourceTransaction("tx", "a", LocalDate.of(2024, 1, 2),
                new BigDecimal("2"), "EUR", "Magasin", null, "c", null, false);
        ParsedHomeBankData imported = new ParsedHomeBankData(List.of(sourceAccount), List.of(sourceCategory),
                List.of(sourceTransaction));
        when(parser.parse(any(), eq("bank.hbk"), isNull())).thenReturn(imported);
        List<Account> storedAccounts = new ArrayList<>();
        List<Category> storedCategories = new ArrayList<>();
        List<Transaction> storedTransactions = new ArrayList<>();
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenAnswer(invocation -> storedAccounts);
        when(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(anyString(), eq(7L))).thenReturn(false);
        when(accountRepository.findByExternalAccountIdAndMemberId(anyString(), eq(7L))).thenAnswer(invocation ->
                storedAccounts.stream().filter(saved -> invocation.getArgument(0).equals(saved.getExternalAccountId())).findFirst());
        when(accountRepository.findByIdAndMemberId(anyLong(), eq(7L))).thenAnswer(invocation ->
                storedAccounts.stream().filter(saved -> saved.getId().equals(invocation.getArgument(0))).findFirst());
        when(accountRepository.save(any())).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(10L);
                storedAccounts.add(saved);
            }
            return saved;
        });
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(7L)).thenAnswer(invocation -> storedCategories);
        when(categoryRepository.save(any())).thenAnswer(invocation -> {
            Category saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId((long) storedCategories.size() + 1);
                storedCategories.add(saved);
            }
            return saved;
        });
        when(transactionRepository.findByAccountMemberIdAndExternalIdIn(eq(7L), anyCollection())).thenAnswer(invocation -> {
            var ids = (java.util.Collection<String>) invocation.getArgument(1);
            return storedTransactions.stream().filter(saved -> ids.contains(saved.getExternalId())).toList();
        });
        when(transactionRepository.existsByAccountIdAndExternalId(anyLong(), anyString())).thenAnswer(invocation ->
                storedTransactions.stream().anyMatch(saved -> saved.getAccount().getId().equals(invocation.getArgument(0))
                        && saved.getExternalId().equals(invocation.getArgument(1))));
        when(transactionRepository.sumAmountByAccountId(10L)).thenAnswer(invocation -> storedTransactions.stream()
                .filter(saved -> saved.getAccount().getId().equals(10L)).map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        when(transactionRepository.save(any())).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            storedTransactions.add(saved);
            return saved;
        });
        when(familyMemberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));
        when(familyMemberRepository.findById(7L)).thenReturn(Optional.of(member));
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper,
                new ImportPreviewStore<>(Clock.systemUTC(), Duration.ofMinutes(30), 8));
        var file = new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1});
        var accountMapping = new AccountMapping("a", FinaryMappingAction.CREATE_NEW, null,
                new NewAccountDetails("Compte", AccountType.CHECKING, null, "EUR", null));
        var categoryMapping = new CategoryMapping("c", CategoryMappingAction.CREATE_NEW, null, "Courses");

        Preview firstPreview = service.preview(file, null, 7L);
        service.executeImport(new Request(firstPreview.fileToken(), List.of(accountMapping), List.of(categoryMapping)), 7L);
        Preview secondPreview = service.preview(file, null, 7L);
        var secondResult = service.executeImport(
                new Request(secondPreview.fileToken(), List.of(accountMapping), List.of(categoryMapping)), 7L);

        assertThat(secondResult.accountsCreated()).isZero();
        assertThat(secondResult.categoriesCreated()).isZero();
        assertThat(secondResult.transactionsImported()).isZero();
        assertThat(storedAccounts).hasSize(1);
        assertThat(storedCategories).hasSize(2);
        assertThat(storedTransactions).hasSize(2);
        assertThat(storedAccounts.get(0).getCurrentBalance()).isEqualByComparingTo("3");

        Account alternate = Account.builder().id(20L).member(member).name("Other")
                .type(AccountType.CHECKING).currency("EUR").currentBalance(BigDecimal.TEN).isManual(true).build();
        storedAccounts.add(alternate);
        Preview remapPreview = service.preview(file, null, 7L);
        var remappedRequest = new Request(remapPreview.fileToken(),
                List.of(new AccountMapping("a", FinaryMappingAction.MAP_EXISTING, 20L, null)),
                List.of(categoryMapping));

        assertThatThrownBy(() -> service.executeImport(remappedRequest, 7L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different account");
        assertThat(storedTransactions).hasSize(2);
        assertThat(alternate.getCurrentBalance()).isEqualByComparingTo("10");
    }

    private void assertUnsafeExistingAccountRejected(Account target) {
        var source = new ParsedHomeBankData.SourceAccount("a", "Compte", "Banque", "bank", "EUR",
                BigDecimal.ZERO, false);
        when(parser.parse(any(), eq("bank.hbk"), isNull())).thenReturn(new ParsedHomeBankData(List.of(source), List.of(), List.of()));
        when(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(categoryRepository.findAllByMemberIdOrderBySortOrderAscIdAsc(7L)).thenReturn(List.of());
        when(familyMemberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(new FamilyMember()));
        when(accountRepository.findByIdAndMemberId(9L, 7L)).thenReturn(Optional.of(target));
        var service = new HomeBankImportService(parser, accountRepository, categoryRepository, transactionRepository,
                familyMemberRepository, balanceSnapshotRepository, persistenceHelper,
                new ImportPreviewStore<>(Clock.systemUTC(), Duration.ofMinutes(30), 8));
        Preview preview = service.preview(new MockMultipartFile("file", "bank.hbk", "application/octet-stream", new byte[]{1}), null, 7L);
        var request = new Request(preview.fileToken(),
                List.of(new AccountMapping("a", FinaryMappingAction.MAP_EXISTING, 9L, null)), List.of());

        assertThatThrownBy(() -> service.executeImport(request, 7L)).isInstanceOf(IllegalArgumentException.class);

        verify(accountRepository, never()).save(any());
        verify(categoryRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }
}
