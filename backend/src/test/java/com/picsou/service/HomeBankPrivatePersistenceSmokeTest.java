package com.picsou.service;

import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.HomeBankImportDtos.AccountMapping;
import com.picsou.dto.HomeBankImportDtos.CategoryMapping;
import com.picsou.dto.HomeBankImportDtos.CategoryMappingAction;
import com.picsou.dto.HomeBankImportDtos.Request;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Private-fixture opt-in; never run against an existing user database. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HomeBankFileParser.class, FinaryPersistenceHelper.class,
        HomeBankImportPersistenceTest.TestServiceConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "PICSOU_HOMEBANK_TEST_JDBC_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PICSOU_HOMEBANK_PASSWORD", matches = ".+")
class HomeBankPrivatePersistenceSmokeTest {
    @Autowired HomeBankImportService service;
    @Autowired HomeBankFileParser parser;
    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired TransactionRepository transactions;

    @Test
    void actualClearImportAndEncryptedReimportPreserveBalancesAndDoNotDuplicate() throws Exception {
        String fixtureDir = System.getProperty("picsou.homebank.fixtureDir");
        assumeTrue(fixtureDir != null && !fixtureDir.isBlank(), "Private fixture opt-in required");
        Path directory = Path.of(fixtureDir);
        Path clearPath = directory.resolve("homebank-clear.hbk");
        Path encryptedPath = directory.resolve("homebank-encrypted.hbexport");
        assumeTrue(Files.isRegularFile(clearPath) && Files.isRegularFile(encryptedPath), "Private fixtures absent");
        byte[] clearBytes = Files.readAllBytes(clearPath);
        var source = parser.parse(clearBytes, clearPath.getFileName().toString(), null);
        FamilyMember member = members.save(FamilyMember.builder().displayName("Private fixture test member").build());
        Long memberId = member.getId();
        Category existingIncome = categories.save(Category.builder().member(member).name("Existing income")
                .kind(CategoryKind.INCOME).build());
        Category existingExpense = categories.save(Category.builder().member(member).name("Existing expense")
                .kind(CategoryKind.EXPENSE).build());
        var preview = service.preview(new MockMultipartFile("file", clearPath.getFileName().toString(),
                "application/octet-stream", clearBytes), null, memberId);
        var accountMappings = preview.accounts().stream().map(account -> new AccountMapping(account.sourceId(),
                FinaryMappingAction.CREATE_NEW, null, new NewAccountDetails(account.name(), account.suggestedType(),
                    account.institution(), account.currency(), "#6366f1"))).toList();
        var initialCategoryMappings = preview.categories().stream().map(category -> new CategoryMapping(category.sourceId(),
                CategoryMappingAction.CREATE_NEW, null, category.name())).toList();
        boolean incompatibleHierarchy = preview.categories().stream().anyMatch(category -> category.parentSourceId() != null
                && preview.categories().stream().anyMatch(parent -> parent.sourceId().equals(category.parentSourceId())
                        && parent.income() != category.income()));
        if (incompatibleHierarchy) {
            long accountCount = accounts.count();
            long transactionCount = transactions.count();
            long categoryCount = categories.count();
            assertThatThrownBy(() -> service.executeImport(new Request(preview.fileToken(), accountMappings,
                    initialCategoryMappings), memberId)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Category parent kind does not match");
            assertThat(accounts.count()).isEqualTo(accountCount);
            assertThat(transactions.count()).isEqualTo(transactionCount);
            assertThat(categories.count()).isEqualTo(categoryCount);
        }
        // Explicit manual association chosen by the user, never flatten or change the source kind.
        var categoryMappings = preview.categories().stream().map(category -> {
            boolean incompatible = category.parentSourceId() != null && preview.categories().stream()
                    .anyMatch(parent -> parent.sourceId().equals(category.parentSourceId())
                            && parent.income() != category.income());
            return incompatible
                    ? new CategoryMapping(category.sourceId(), CategoryMappingAction.MAP_EXISTING,
                            category.income() ? existingIncome.getId() : existingExpense.getId(), null)
                    : new CategoryMapping(category.sourceId(), CategoryMappingAction.CREATE_NEW, null, category.name());
        }).toList();
        var result = service.executeImport(new Request(preview.fileToken(), accountMappings, categoryMappings), memberId);
        assertThat(result.accountsCreated()).isEqualTo(source.accounts().size());
        assertThat(result.transactionsImported()).isEqualTo(preview.totalTransactions());
        for (var sourceAccount : source.accounts()) {
            var account = accounts.findByExternalAccountIdAndMemberId("homebank_" + sourceAccount.id(), memberId).orElseThrow();
            BigDecimal expected = source.transactions().stream()
                    .filter(row -> !row.forecast() && row.accountId().equals(sourceAccount.id()))
                    .map(row -> row.amount()).reduce(sourceAccount.initialBalance(), BigDecimal::add);
            // Boolean assertions prevent private monetary values appearing in diagnostics.
            assertThat(account.getCurrentBalance().compareTo(expected) == 0)
                    .as("private account ledger balance equality").isTrue();
            var requestedType = accountMappings.stream().filter(mapping -> mapping.sourceId().equals(sourceAccount.id()))
                    .findFirst().orElseThrow().newAccount().type();
            assertThat(account.getType()).isEqualTo(requestedType);
        }
        long rowsBeforeReplay = transactions.count();
        var encrypted = service.preview(new MockMultipartFile("file", encryptedPath.getFileName().toString(),
                "application/octet-stream", Files.readAllBytes(encryptedPath)),
                System.getenv("PICSOU_HOMEBANK_PASSWORD"), memberId);
        var replay = service.executeImport(new Request(encrypted.fileToken(), accountMappings, categoryMappings), memberId);
        assertThat(replay.accountsCreated()).isZero();
        assertThat(replay.categoriesCreated()).isZero();
        assertThat(replay.transactionsImported()).isZero();
        assertThat(replay.transactionsSkipped()).isEqualTo(source.transactions().size());
        assertThat(transactions.count()).isEqualTo(rowsBeforeReplay);
        System.out.printf("Private persisted HomeBank smoke: accounts=%d sourceTransactions=%d replayImported=%d%n",
                result.accountsCreated(), result.transactionsImported(), replay.transactionsImported());
    }

}
