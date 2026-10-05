package com.picsou.service;

import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.HomeBankImportDtos.*;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.model.AccountType;
import com.picsou.model.CategoryKind;
import com.picsou.model.FamilyMember;
import com.picsou.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in tests: only point datasource variables at a new, disposable PostgreSQL database. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HomeBankFileParser.class, FinaryPersistenceHelper.class, HomeBankQifPersistenceTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@EnabledIfEnvironmentVariable(named = "PICSOU_HOMEBANK_TEST_JDBC_URL", matches = ".+")
class HomeBankQifPersistenceTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        HomeBankImportService homeBankImportService(HomeBankFileParser parser, AccountRepository accounts,
                CategoryRepository categories, TransactionRepository transactions, FamilyMemberRepository members,
                BalanceSnapshotRepository snapshots, FinaryPersistenceHelper persistence) {
            return new HomeBankImportService(parser, accounts, categories, transactions, members, snapshots, persistence);
        }
    }

    @Autowired HomeBankImportService service;
    @Autowired HomeBankFileParser parser;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired TransactionRepository transactions;
    @Autowired FamilyMemberRepository members;
    @Autowired JdbcTemplate jdbc;

    private static final String SYNTHETIC = """
            !Account
            NSynthetic Checking
            TBank
            ^
            !Type:Bank
            D2024/02/29
            T-12.34000001
            PSynthetic Market
            Mrollback-qif-marker
            LFood:Fresh
            ^
            D2024/03/01
            T-30.00
            PSynthetic Split
            Msplit note
            L
            SFood:Fresh
            Efresh note
            $-10.00
            SFood:Other
            Eother note
            $-20.00
            ^
            D2024/03/02
            T-25.25
            L[Synthetic Savings]
            ^
            !Account
            NSynthetic Savings
            TBank
            ^
            !Type:Bank
            D2024/03/02
            T25.25
            L[Synthetic Checking]
            ^
            """;

    @Test
    void qifSplitLedgerIsAtomicAndRepeatImportDoesNotDuplicate() {
        long member = newMember();
        byte[] bytes = SYNTHETIC.getBytes(StandardCharsets.UTF_8);
        Preview preview = preview(bytes, member);
        assertThat(preview.totalTransactions()).isEqualTo(5);
        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(member)).isEmpty();
        assertThat(categories.findAllByMemberIdOrderBySortOrderAscIdAsc(member)).isEmpty();
        Request request = request(preview);

        jdbc.execute("ALTER TABLE \"transaction\" ADD CONSTRAINT homebank_qif_rollback_test "
                + "CHECK (description <> 'rollback-qif-marker') NOT VALID");
        try {
            assertThatThrownBy(() -> service.executeImport(request, member)).isInstanceOf(RuntimeException.class);
            assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(member)).isEmpty();
            assertThat(categories.findAllByMemberIdOrderBySortOrderAscIdAsc(member)).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE \"transaction\" DROP CONSTRAINT homebank_qif_rollback_test");
        }
        assertThat(service.executeImport(request, member).transactionsImported()).isEqualTo(5);
        assertBalances(bytes, member);
        var stored = accounts.findAllByMemberIdOrderByCreatedAtAsc(member).stream()
                .flatMap(account -> transactions.findByAccountIdAndDateBetweenOrderByDateAsc(
                        account.getId(), java.time.LocalDate.of(2024, 1, 1), java.time.LocalDate.of(2024, 12, 31)).stream())
                .filter(row -> !row.getExternalId().startsWith("homebank_opening_"))
                .toList();
        assertThat(stored).hasSize(5);
        assertThat(stored.stream().filter(row -> categories.findByIdAndMemberId(row.getCategoryRef().getId(), member)
                        .orElseThrow().getKind() == CategoryKind.TRANSFER).count())
                .isEqualTo(2);
        assertThat(service.executeImport(request(preview(bytes, member)), member).transactionsImported()).isZero();
        assertBalances(bytes, member);
    }

    @Test
    @EnabledIfSystemProperty(named = "picsou.homebank.qifFile", matches = ".+")
    void privateDesktopExportPersistsExactPerAccountSumsAndReplaysWithoutDuplicates() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("picsou.homebank.qifFile")));
        long member = newMember();
        Preview preview = preview(bytes, member);
        assertThat(preview.accounts().size() == 8).isTrue();
        assertThat(preview.totalTransactions() == 646).isTrue();
        int imported = service.executeImport(request(preview), member).transactionsImported();
        assertThat(imported == preview.totalTransactions()).isTrue();
        assertBalances(bytes, member);
        var replay = service.executeImport(request(preview(bytes, member)), member);
        assertThat(replay.transactionsImported() == 0).isTrue();
        assertThat(replay.transactionsSkipped() == imported).isTrue();
        assertBalances(bytes, member);
        System.out.println("Private QIF verified: accounts=8, normalizedRows=646, ledgerEquality=true, replayDuplicates=0");
    }

    private long newMember() {
        return members.save(FamilyMember.builder().displayName("Synthetic QIF test member").build()).getId();
    }

    private Preview preview(byte[] bytes, long member) {
        return service.preview(new MockMultipartFile("file", "desktop.qif", "application/octet-stream", bytes),
                null, "EUR", member);
    }

    private static Request request(Preview preview) {
        return new Request(preview.fileToken(), preview.accounts().stream().map(account ->
                new AccountMapping(account.sourceId(), FinaryMappingAction.CREATE_NEW, null,
                        new NewAccountDetails(account.name(), AccountType.CHECKING, null, account.currency(), "#6366f1")))
                .toList(), preview.categories().stream().map(category ->
                new CategoryMapping(category.sourceId(), CategoryMappingAction.CREATE_NEW, null, category.name())).toList());
    }

    private void assertBalances(byte[] bytes, long member) {
        var parsed = parser.parse(bytes, "desktop.qif", null, "EUR");
        for (var source : parsed.accounts()) {
            var account = accounts.findByExternalAccountIdAndMemberId("homebank_" + source.id(), member).orElseThrow();
            BigDecimal expected = parsed.transactions().stream().filter(row -> row.accountId().equals(source.id()))
                    .map(row -> row.amount()).reduce(source.initialBalance(), BigDecimal::add);
            // Boolean assertions intentionally avoid exposing private amounts in failure reports.
            assertThat(expected.compareTo(transactions.sumAmountByAccountId(account.getId())) == 0).isTrue();
            assertThat(expected.compareTo(account.getCurrentBalance()) == 0).isTrue();
        }
    }
}
