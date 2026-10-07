package com.picsou.service;

import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.HomeBankImportDtos.AccountMapping;
import com.picsou.dto.HomeBankImportDtos.CategoryMapping;
import com.picsou.dto.HomeBankImportDtos.CategoryMappingAction;
import com.picsou.dto.HomeBankImportDtos.Preview;
import com.picsou.dto.HomeBankImportDtos.Request;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.model.Account;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end HomeBank persistence (real Flyway migrations, {@code ddl-auto=validate}, rollback and
 * duplicate-free re-import) against a throwaway PostgreSQL started by Testcontainers. Runs in CI.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HomeBankFileParser.class, FinaryPersistenceHelper.class, HomeBankImportPersistenceTest.TestServiceConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
@Testcontainers
@EnabledIf("dockerAvailable")
class HomeBankImportPersistenceTest {
    static {
        // docker-java otherwise negotiates API 1.32, which Docker Engine >= 28 refuses (see
        // EncryptedSessionTokenWidthMigrationTest).
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** CI sets PICSOU_REQUIRE_DOCKER_TESTS so a missing daemon fails instead of silently skipping. */
    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found. "
                    + "The HomeBank PostgreSQL tests cannot be skipped here. Needs Docker Engine >= 25.0.");
        }
        return available;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestServiceConfiguration {
        @Bean
        HomeBankImportService homeBankImportService(HomeBankFileParser parser, AccountRepository accounts,
                CategoryRepository categories, TransactionRepository transactions, FamilyMemberRepository members,
                BalanceSnapshotRepository snapshots, FinaryPersistenceHelper persistence) {
            return new HomeBankImportService(parser, accounts, categories, transactions, members, snapshots, persistence);
        }
    }

    private static final String ROLLBACK_CHECK = "homebank_test_reject_marker";
    private static final String PAYLOAD = """
        {"data":{
          "accounts":[
            {"id":"a12b3c4d-1111-4111-8111-111111111111","name":"Synthetic Checking","institution":"Synthetic Bank","type":"bank","currency":{"code":"EUR"},"initialAmount":{"amount":1250.125,"currencyCode":"EUR"},"isClosed":false},
            {"id":"a12b3c4d-2222-4222-8222-222222222222","name":"Synthetic Savings","institution":"Synthetic Bank","type":"savings","currency":{"code":"EUR"},"initialAmount":{"amount":50.00,"currencyCode":"EUR"},"isClosed":false}],
          "categories":[
            {"id":"b12b3c4d-2222-4222-8222-222222222222","name":"Fresh produce","parentID":"b12b3c4d-1111-4111-8111-111111111111","isIncomeType":false},
            {"id":"b12b3c4d-1111-4111-8111-111111111111","name":"Groceries","isIncomeType":false},
            {"id":"b12b3c4d-3333-4333-8333-333333333333","name":"Salary","isIncomeType":true}],
          "payees":[{"id":"c12b3c4d-1111-4111-8111-111111111111","name":"Synthetic Market"}],
          "transactions":[
            {"id":"d12b3c4d-1111-4111-8111-111111111111","accountID":"a12b3c4d-1111-4111-8111-111111111111","amount":{"amount":-12.34000001,"currencyCode":"EUR"},"date":{"year":2024,"month":2,"day":29},"category":{"id":"b12b3c4d-2222-4222-8222-222222222222"},"payee":{"id":"c12b3c4d-1111-4111-8111-111111111111"},"memo":"Fresh food note","isForecast":false},
            {"id":"d12b3c4d-2222-4222-8222-222222222222","accountID":"a12b3c4d-1111-4111-8111-111111111111","amount":{"amount":2400.55,"currencyCode":"EUR"},"date":{"year":2024,"month":3,"day":1},"category":{"id":"b12b3c4d-3333-4333-8333-333333333333"},"payee":{"id":"c12b3c4d-1111-4111-8111-111111111111"},"memo":"Monthly salary note","isForecast":false},
            {"id":"d12b3c4d-3333-4333-8333-333333333333","accountID":"a12b3c4d-1111-4111-8111-111111111111","amount":{"amount":-25.25,"currencyCode":"EUR"},"date":{"year":2024,"month":3,"day":2},"transferAccountID":"a12b3c4d-2222-4222-8222-222222222222","isForecast":false},
            {"id":"d12b3c4d-4444-4444-8444-444444444444","accountID":"a12b3c4d-2222-4222-8222-222222222222","amount":{"amount":25.25,"currencyCode":"EUR"},"date":{"year":2024,"month":3,"day":2},"transferAccountID":"a12b3c4d-1111-4111-8111-111111111111","isForecast":false}]},
          "manifest":{"schemaVersion":3,"platform":"iOS","appVersion":"synthetic","appBuild":"test","exportDate":"2024-03-03"}}
        """;

    @Autowired HomeBankImportService service;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired TransactionRepository transactions;
    @Autowired FamilyMemberRepository members;
    @Autowired BalanceSnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;
    private Long memberId;

    @BeforeEach
    void createSyntheticMember() {
        memberId = members.save(com.picsou.model.FamilyMember.builder()
                .displayName("Synthetic test member").build()).getId();
    }

    @Test
    void persistsExactLedgerReplaysDeduplicateAndRollbackRestoresTokenForRetry() {
        assertThat(members.findById(memberId)).isPresent();
        int accountsBefore = accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId).size();
        int categoriesBefore = categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId).size();
        int transactionsBefore = transactions.findAll().size();
        int snapshotsBefore = snapshots.findAll().size();

        Preview preview = preview(PAYLOAD);
        assertThat(preview.categories()).extracting("sourceId").containsSubsequence(
            "b12b3c4d-2222-4222-8222-222222222222", "b12b3c4d-1111-4111-8111-111111111111");
        Request request = request(preview);
        assertThatThrownBy(() -> service.executeImport(
                new Request(preview.fileToken(), List.of(
                    new AccountMapping("a12b3c4d-1111-4111-8111-111111111111", FinaryMappingAction.CREATE_NEW, null,
                        new com.picsou.dto.NewAccountDetails("wrong currency", com.picsou.model.AccountType.CHECKING, null, "EUR", "#6366f1")),
                    new AccountMapping("a12b3c4d-2222-4222-8222-222222222222", FinaryMappingAction.CREATE_NEW, null,
                        new com.picsou.dto.NewAccountDetails("wrong currency", com.picsou.model.AccountType.SAVINGS, null, "USD", "#6366f1"))),
                    request.categoryMappings()), memberId))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId)).hasSize(accountsBefore);
        assertThat(categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId)).hasSize(categoriesBefore);
        assertThat(transactions.findAll()).hasSize(transactionsBefore);
        assertThat(snapshots.findAll()).hasSize(snapshotsBefore);

        jdbc.execute("ALTER TABLE \"transaction\" ADD CONSTRAINT " + ROLLBACK_CHECK
            + " CHECK (description <> 'Fresh food note') NOT VALID");
        Preview failingPreview = preview(PAYLOAD);
        Request failingRequest = request(failingPreview);
        assertThatThrownBy(() -> service.executeImport(failingRequest, memberId)).isInstanceOf(RuntimeException.class);
        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId)).hasSize(accountsBefore);
        assertThat(categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId)).hasSize(categoriesBefore);
        assertThat(transactions.findAll()).hasSize(transactionsBefore);
        assertThat(snapshots.findAll()).hasSize(snapshotsBefore);

        jdbc.execute("ALTER TABLE \"transaction\" DROP CONSTRAINT " + ROLLBACK_CHECK);
        var result = service.executeImport(failingRequest, memberId);
        assertThat(result.accountsCreated()).isEqualTo(2);
        assertThat(result.categoriesCreated()).isEqualTo(3);
        assertThat(result.transactionsImported()).isEqualTo(4);
        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId)).hasSize(accountsBefore + 2);
        assertThat(categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId)).hasSize(categoriesBefore + 4);
        assertThat(transactions.findAll()).hasSize(transactionsBefore + 6); // four source rows and two openings

        List<Account> importedAccounts = accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(a -> a.getExternalAccountId() != null && a.getExternalAccountId().startsWith("homebank_"))
            .toList();
        Account checking = importedAccounts.stream().filter(a -> a.getExternalAccountId().endsWith("111111111111")).findFirst().orElseThrow();
        Account savings = importedAccounts.stream().filter(a -> a.getExternalAccountId().endsWith("222222222222")).findFirst().orElseThrow();
        assertThat(checking.getCurrentBalance()).isEqualByComparingTo("3613.08499999");
        assertThat(savings.getCurrentBalance()).isEqualByComparingTo("75.25");

        List<Transaction> rows = transactions.findByAccountIdOrderByDateDesc(checking.getId());
        assertThat(rows).hasSize(4);
        Transaction expense = rows.stream().filter(t -> "homebank_d12b3c4d-1111-4111-8111-111111111111".equals(t.getExternalId())).findFirst().orElseThrow();
        assertThat(expense.getAmount()).isEqualByComparingTo("-12.34000001");
        assertThat(expense.getDate().toString()).isEqualTo("2024-02-29");
        assertThat(expense.getDescription()).isEqualTo("Fresh food note");
        assertThat(expense.getCounterparty()).isEqualTo("Synthetic Market");
        assertThat(expense.getCategory()).isEqualTo("Fresh produce");
        assertThat(categoryKind(expense.getId())).isEqualTo(CategoryKind.EXPENSE);
        assertThat(jdbc.queryForObject("SELECT p.name FROM category c JOIN category p ON p.id=c.parent_id "
            + "WHERE c.slug=? AND c.member_id=?", String.class, "homebank_b12b3c4d-2222-4222-8222-222222222222", memberId))
            .isEqualTo("Groceries");

        Transaction income = rows.stream().filter(t -> "homebank_d12b3c4d-2222-4222-8222-222222222222".equals(t.getExternalId())).findFirst().orElseThrow();
        assertThat(income.getAmount()).isEqualByComparingTo("2400.55");
        assertThat(income.getDate().toString()).isEqualTo("2024-03-01");
        assertThat(income.getDescription()).isEqualTo("Monthly salary note");
        assertThat(categoryKind(income.getId())).isEqualTo(CategoryKind.INCOME);

        Transaction outLeg = rows.stream().filter(t -> "homebank_d12b3c4d-3333-4333-8333-333333333333".equals(t.getExternalId())).findFirst().orElseThrow();
        assertThat(outLeg.getAmount()).isEqualByComparingTo("-25.25");
        assertThat(categoryKind(outLeg.getId())).isEqualTo(CategoryKind.TRANSFER);
        assertThat(categoryManual(outLeg.getId())).isTrue();
        assertThat(transactions.findByAccountIdOrderByDateDesc(savings.getId())).anySatisfy(in -> {
            assertThat(in.getExternalId()).isEqualTo("homebank_d12b3c4d-4444-4444-8444-444444444444");
            assertThat(in.getAmount()).isEqualByComparingTo("25.25");
            assertThat(categoryKind(in.getId())).isEqualTo(CategoryKind.TRANSFER);
            assertThat(categoryManual(in.getId())).isTrue();
        });
        assertThat(rows).anySatisfy(opening -> {
            assertThat(opening.getExternalId()).isEqualTo("homebank_opening_a12b3c4d-1111-4111-8111-111111111111");
            assertThat(opening.getAmount()).isEqualByComparingTo("1250.125");
            assertThat(categoryKind(opening.getId())).isEqualTo(CategoryKind.TRANSFER);
            assertThat(categoryManual(opening.getId())).isTrue();
        });
        assertThat(snapshots.findByAccountIdOrderByDateAsc(checking.getId())).isNotEmpty();

        int importedRows = transactions.findAll().size();
        Preview replay = preview(PAYLOAD);
        var replayResult = service.executeImport(request(replay), memberId);
        assertThat(replayResult.transactionsImported()).isZero();
        assertThat(replayResult.transactionsSkipped()).isEqualTo(4);
        assertThat(transactions.findAll()).hasSize(importedRows);
        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(memberId)).hasSize(accountsBefore + 2);
        assertThat(categories.findAllByMemberIdOrderBySortOrderAscIdAsc(memberId)).hasSize(categoriesBefore + 4);
    }

    @Test
    void mappedManualAccountPersistsAdditiveOpeningAndReplayDoesNotDuplicateIt() {
        String sourceId = "e12b3c4d-1111-4111-8111-111111111111";
        var target = accounts.save(com.picsou.model.Account.builder()
                .member(members.findById(memberId).orElseThrow()).name("Existing manual")
                .type(com.picsou.model.AccountType.CHECKING).currency("EUR")
                .currentBalance(new java.math.BigDecimal("1000")).isManual(true).color("#6366f1").build());
        transactions.save(Transaction.builder().account(target).date(java.time.LocalDate.of(2024, 1, 1))
                .description("Legacy row").amount(new java.math.BigDecimal("1000"))
                .nativeCurrency("EUR").isManual(true).build());
        String payload = """
            {"data":{"accounts":[{"id":"%s","name":"Mapped account","institution":"Bank","type":"bank","currency":{"code":"EUR"},"initialAmount":{"amount":250,"currencyCode":"EUR"},"isClosed":false}],"categories":[],"payees":[],"transactions":[]},"manifest":{"schemaVersion":3,"platform":"iOS","appVersion":"synthetic","appBuild":"test","exportDate":"2024-03-03"}}
            """.formatted(sourceId);
        Preview preview = preview(payload);
        Request request = new Request(preview.fileToken(), List.of(
                new AccountMapping(sourceId, FinaryMappingAction.MAP_EXISTING, target.getId(), null)), List.of());

        service.executeImport(request, memberId);

        assertThat(accounts.findByIdAndMemberId(target.getId(), memberId).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("1250");
        assertThat(transactions.findByAccountIdOrderByDateDesc(target.getId()))
                .extracting(Transaction::getExternalId).containsExactlyInAnyOrder(null,
                        "homebank_opening_" + sourceId);
        Transaction opening = transactions.findByAccountIdOrderByDateDesc(target.getId()).stream()
                .filter(row -> ("homebank_opening_" + sourceId).equals(row.getExternalId())).findFirst().orElseThrow();
        assertThat(opening.getAmount()).isEqualByComparingTo("250");
        int transactionCount = transactions.findByAccountIdOrderByDateDesc(target.getId()).size();
        int snapshotCount = snapshots.findByAccountIdOrderByDateAsc(target.getId()).size();

        Preview replay = preview(payload);
        service.executeImport(new Request(replay.fileToken(), List.of(
                new AccountMapping(sourceId, FinaryMappingAction.MAP_EXISTING, target.getId(), null)), List.of()), memberId);

        assertThat(transactions.findByAccountIdOrderByDateDesc(target.getId())).hasSize(transactionCount);
        assertThat(snapshots.findByAccountIdOrderByDateAsc(target.getId())).hasSize(snapshotCount);
        assertThat(accounts.findByIdAndMemberId(target.getId(), memberId).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("1250");
    }

    private Preview preview(String json) {
        return service.preview(new MockMultipartFile("file", "synthetic.hbk", "application/octet-stream",
            rawDeflate(json.getBytes(StandardCharsets.UTF_8))), null, memberId);
    }

    private CategoryKind categoryKind(Long transactionId) {
        return jdbc.queryForObject("SELECT c.kind FROM category c JOIN \"transaction\" t ON t.category_id=c.id "
            + "WHERE t.id=?", (rs, row) -> CategoryKind.valueOf(rs.getString(1)), transactionId);
    }

    private boolean categoryManual(Long transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT category_manual FROM \"transaction\" WHERE id=?",
            Boolean.class, transactionId));
    }

    private Request request(Preview preview) {
        return new Request(preview.fileToken(), List.of(
            new AccountMapping("a12b3c4d-1111-4111-8111-111111111111", FinaryMappingAction.CREATE_NEW, null,
                new com.picsou.dto.NewAccountDetails("Synthetic Checking", com.picsou.model.AccountType.CHECKING, null, "EUR", "#6366f1")),
            new AccountMapping("a12b3c4d-2222-4222-8222-222222222222", FinaryMappingAction.CREATE_NEW, null,
                new com.picsou.dto.NewAccountDetails("Synthetic Savings", com.picsou.model.AccountType.SAVINGS, null, "EUR", "#6366f1"))),
            List.of(
                new CategoryMapping("b12b3c4d-2222-4222-8222-222222222222", CategoryMappingAction.CREATE_NEW, null, "Fresh produce"),
                new CategoryMapping("b12b3c4d-1111-4111-8111-111111111111", CategoryMappingAction.CREATE_NEW, null, "Groceries"),
                new CategoryMapping("b12b3c4d-3333-4333-8333-333333333333", CategoryMappingAction.CREATE_NEW, null, "Salary")));
    }

    private static byte[] rawDeflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return output.toByteArray();
    }
}
