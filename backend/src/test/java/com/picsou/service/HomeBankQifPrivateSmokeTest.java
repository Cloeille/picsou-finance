package com.picsou.service;

import com.picsou.dto.FinaryMappingAction;
import com.picsou.dto.HomeBankImportDtos.*;
import com.picsou.dto.NewAccountDetails;
import com.picsou.finary.FinaryPersistenceHelper;
import com.picsou.imports.homebank.HomeBankFileParser;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in (-Dpicsou.homebank.qifFile=...): imports a private desktop QIF export into a throwaway
 * Testcontainers PostgreSQL. Kept apart from {@link HomeBankQifPersistenceTest} so CI's
 * "Skipped: 0" check stays strict. Never commit the file or its figures.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({HomeBankFileParser.class, FinaryPersistenceHelper.class, HomeBankQifPersistenceTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true"})
@EnabledIfSystemProperty(named = "picsou.homebank.qifFile", matches = ".+")
@Testcontainers
@EnabledIf("dockerAvailable")
class HomeBankQifPrivateSmokeTest {
    static {
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Autowired HomeBankImportService service;
    @Autowired HomeBankFileParser parser;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired FamilyMemberRepository members;

    @Test
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
