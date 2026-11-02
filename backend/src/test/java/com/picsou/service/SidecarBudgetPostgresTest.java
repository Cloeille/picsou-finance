package com.picsou.service;

import com.picsou.dto.CashflowPeriod;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Budget;
import com.picsou.model.BudgetSettings;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.CategorizationRule;
import com.picsou.model.FamilyMember;
import com.picsou.model.RuleMatchType;
import com.picsou.model.RuleSource;
import com.picsou.model.Transaction;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.BankConnectorPort.TransactionData;
import com.picsou.port.SidecarTransaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BudgetRepository;
import com.picsou.repository.BudgetSettingsRepository;
import com.picsou.repository.CategoryRepository;
import com.picsou.repository.CategorizationRuleRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.BudgetService;
import com.picsou.service.budget.CashflowService;
import com.picsou.service.budget.CategorizationService;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PostgreSQL/Flyway acceptance coverage for the shared sidecar ledger and budget readers.
 * All account and transaction payloads in this test are synthetic; no bank is contacted.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SidecarBudgetPostgresTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate CYCLE_START = LocalDate.of(2026, 9, 15);
    private static final LocalDate CYCLE_END = LocalDate.of(2026, 10, 14);
    private static final String IBAN = "FR7630006000011234567890189";
    private static final List<String> BOURSO_PROVIDER_FAMILY = List.of(
        "BoursoBank", "Boursorama", "Boursorama Banque");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.sidecar.api-key", () -> "synthetic-sidecar-test-key-not-a-credential");
    }

    @Autowired FamilyMemberRepository memberRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired CategorizationRuleRepository ruleRepository;
    @Autowired BudgetRepository budgetRepository;
    @Autowired BudgetSettingsRepository settingsRepository;
    @Autowired SharedSidecarTransactionImportService importer;
    @Autowired CategorizationService categorizationService;
    @Autowired BudgetService budgetService;
    @Autowired CashflowService cashflowService;

    private FamilyMember member;
    private Account account;
    private Category groceries;
    private Category transfer;

    @BeforeEach
    void setUpMemberLedger() {
        member = memberRepository.save(FamilyMember.builder().displayName("Synthetic PostgreSQL member").build());
        account = accountRepository.save(Account.builder()
            .member(member).name("Synthetic Bourso checking").type(AccountType.CHECKING)
            .provider("BoursoBank").externalAccountId("synthetic-account-" + member.getId())
            .iban(IBAN).currency("EUR").isManual(false).build());
        groceries = categoryRepository.save(Category.builder().member(member).name("Groceries")
            .slug("synthetic-groceries").kind(CategoryKind.EXPENSE).build());
        transfer = categoryRepository.save(Category.builder().member(member).name("Legacy manual choice")
            .slug("synthetic-legacy-choice").kind(CategoryKind.TRANSFER).build());
        ruleRepository.save(CategorizationRule.builder().member(member).matchType(RuleMatchType.KEYWORD)
            .pattern("market").category(groceries).priority(1).source(RuleSource.USER).build());
        settingsRepository.save(BudgetSettings.builder().member(member).cycleStartDay(15).build());
        budgetRepository.save(Budget.builder().member(member).category(groceries)
            .monthlyLimit(new BigDecimal("100.00")).build());
    }

    @Test
    void sharedImportPreservesLegacyRowsUsesStrictMultisetAndFeedsRealBudgetAndCashflow() {
        Transaction legacy = transactionRepository.save(Transaction.builder()
            .account(account).date(LocalDate.of(2026, 9, 20)).description("Market groceries")
            .amount(new BigDecimal("-10.00")).externalId("legacy-eb-reference")
            .categoryRef(transfer).categoryManual(true).isManual(false).nativeCurrency("EUR").build());

        List<SidecarTransaction> syntheticSnapshot = List.of(
            sidecar("sidecar-duplicate-1", "2026-09-20", "Market groceries", "-10.00"),
            sidecar("sidecar-duplicate-2", "2026-09-20", "Market groceries", "-10.00"),
            sidecar("sidecar-current-1", "2026-09-21", "Market groceries", "-12.00"),
            sidecar("sidecar-current-2", "2026-10-03", "Market groceries", "-8.00"),
            sidecar("sidecar-outside-cycle", "2026-09-14", "Market groceries", "-99.00"));
        var categorizationContext = categorizationService.loadContext(member.getId());

        assertThat(importer.importFor(account, syntheticSnapshot, categorizationContext, "bourso")).isEqualTo(4);
        assertThat(importer.importFor(account, syntheticSnapshot, categorizationContext, "bourso")).isZero();

        List<Transaction> stored = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
        assertThat(stored).hasSize(5);
        Transaction preserved = stored.stream().filter(row -> row.getId().equals(legacy.getId())).findFirst().orElseThrow();
        assertThat(preserved.getExternalId()).isEqualTo("legacy-eb-reference");
        assertThat(preserved.getCategoryRef().getId()).isEqualTo(transfer.getId());
        assertThat(preserved.isCategoryManual()).isTrue();

        assertThat(stored.stream().filter(row -> row.getDate().equals(LocalDate.of(2026, 9, 20)))
            .collect(Collectors.groupingBy(row -> row.getDate() + "|" + row.getDescription() + "|" + row.getAmount(),
                Collectors.counting())))
            .containsExactly(Map.entry("2026-09-20|Market groceries|-10.00000000", 2L));
        List<Transaction> imported = stored.stream().filter(row -> row.getExternalId() != null
            && row.getExternalId().startsWith("sidecar-")).toList();
        assertThat(imported).hasSize(4);
        assertThat(imported).allSatisfy(row -> {
            assertThat(row.getExternalTransactionId()).startsWith("bourso:");
            assertThat(row.getCategoryRef().getId()).isEqualTo(groceries.getId());
        });
        assertThat(imported).extracting(Transaction::getExternalId)
            .containsExactlyInAnyOrder("sidecar-duplicate-2", "sidecar-current-1", "sidecar-current-2",
                "sidecar-outside-cycle");

        try (MockedStatic<LocalDate> dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(TODAY);
            var budget = budgetService.findAll(member.getId()).getFirst();
            assertThat(budget.cycleStart()).isEqualTo(CYCLE_START);
            assertThat(budget.cycleEnd()).isEqualTo(CYCLE_END);
            assertThat(budget.spent()).isEqualByComparingTo("30.00");

            var cashflow = cashflowService.compute(member.getId(), CashflowPeriod.CYCLE, TODAY);
            assertThat(cashflow.from()).isEqualTo(CYCLE_START);
            assertThat(cashflow.to()).isEqualTo(CYCLE_END);
            assertThat(cashflow.expense()).isEqualByComparingTo("30.00");

            List<Long> transactionIdsBeforeResync = stored.stream().map(Transaction::getId).sorted().toList();
            assertThat(importer.importFor(account, syntheticSnapshot, categorizationContext, "bourso")).isZero();
            List<Transaction> afterResync = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
            assertThat(afterResync).hasSize(5).extracting(Transaction::getId)
                .containsExactlyInAnyOrderElementsOf(transactionIdsBeforeResync);
            assertThat(afterResync.stream().filter(row -> row.getExternalId() != null
                && row.getExternalId().startsWith("sidecar-")).toList())
                .allSatisfy(row -> assertThat(row.getCategoryRef().getId()).isEqualTo(groceries.getId()));
            assertThat(budgetService.findAll(member.getId()).getFirst().spent()).isEqualByComparingTo("30.00");
            assertThat(cashflowService.compute(member.getId(), CashflowPeriod.CYCLE, TODAY).expense())
                .isEqualByComparingTo("30.00");
        }
    }

    @Test
    void ibanMatchingIsMemberAndProviderScopedAndSoftDeletedAccountsStayTombstoned() {
        FamilyMember otherMember = memberRepository.save(FamilyMember.builder()
            .displayName("Other synthetic member").build());
        Account sameIbanDifferentProvider = accountRepository.save(Account.builder()
            .member(member).name("Same IBAN, other provider").type(AccountType.CHECKING)
            .provider("Enable Banking").externalAccountId("synthetic-eb-id").iban(IBAN).build());
        Account otherMemberAccount = accountRepository.save(Account.builder()
            .member(otherMember).name("Other member account").type(AccountType.CHECKING)
            .provider("BoursoBank").externalAccountId("synthetic-other-member").iban(IBAN).build());

        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProvider(IBAN, member.getId(), "BoursoBank"))
            .get().extracting(Account::getId).isEqualTo(account.getId());
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProvider(IBAN, member.getId(), "Enable Banking"))
            .get().extracting(Account::getId).isEqualTo(sameIbanDifferentProvider.getId());
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProvider(IBAN, otherMember.getId(), "BoursoBank"))
            .get().extracting(Account::getId).isEqualTo(otherMemberAccount.getId());

        Account tombstone = Account.builder().member(member).name("Deleted synthetic account")
            .type(AccountType.CHECKING).provider("BoursoBank").externalAccountId("synthetic-deleted-id")
            .iban("FR7612345678901234567890124").deletedAt(Instant.parse("2026-01-01T00:00:00Z"))
            .build();
        accountRepository.saveAndFlush(tombstone);
        assertThat(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(
            "synthetic-deleted-id", member.getId())).isTrue();
        assertThat(accountRepository.existsSoftDeletedByIbanAndMemberIdAndProvider(
            tombstone.getIban(), member.getId(), "BoursoBank")).isTrue();
        assertThat(accountRepository.findByExternalAccountIdAndMemberId("synthetic-deleted-id", member.getId()))
            .isEmpty();
    }

    @Test
    void explicitBoursoProviderFamilyIsExactAndMemberScoped() {
        FamilyMember otherMember = memberRepository.save(FamilyMember.builder()
            .displayName("Alias member").build());
        Account boursorama = accountRepository.save(Account.builder().member(member).name("Boursorama account")
            .type(AccountType.CHECKING).provider("Boursorama").externalAccountId("alias-boursorama")
            .iban("FR7612345678901234567890125").build());
        Account boursoramaBanque = accountRepository.save(Account.builder().member(member).name("Boursorama Banque account")
            .type(AccountType.CHECKING).provider("Boursorama Banque").externalAccountId("alias-boursorama-banque")
            .iban("FR7612345678901234567890126").build());
        Account unrelated = accountRepository.save(Account.builder().member(member).name("Other bank")
            .type(AccountType.CHECKING).provider("Boursorama Banque Pro").externalAccountId("alias-lookalike")
            .iban("FR7612345678901234567890127").build());
        Account lowerCase = accountRepository.save(Account.builder().member(member).name("Lower-case provider")
            .type(AccountType.CHECKING).provider("boursorama banque").externalAccountId("alias-lowercase")
            .iban("FR7612345678901234567890128").build());
        Account anotherMemberAlias = accountRepository.save(Account.builder().member(otherMember)
            .name("Other member Boursorama").type(AccountType.CHECKING).provider("Boursorama Banque")
            .externalAccountId("other-member-alias").iban("FR7612345678901234567890129").build());

        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            boursorama.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY))
            .get().extracting(Account::getId).isEqualTo(boursorama.getId());
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            boursoramaBanque.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY))
            .get().extracting(Account::getId).isEqualTo(boursoramaBanque.getId());
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            unrelated.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY)).isEmpty();
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            lowerCase.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY)).isEmpty();
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            anotherMemberAlias.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY)).isEmpty();
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            anotherMemberAlias.getIban(), otherMember.getId(), BOURSO_PROVIDER_FAMILY))
            .get().extracting(Account::getId).isEqualTo(anotherMemberAlias.getId());

        Account deletedAlias = accountRepository.saveAndFlush(Account.builder().member(member)
            .name("Deleted Boursorama alias").type(AccountType.CHECKING).provider("Boursorama Banque")
            .externalAccountId("deleted-boursorama-alias").iban("FR7612345678901234567890130")
            .deletedAt(Instant.parse("2026-01-01T00:00:00Z")).build());
        Account deletedUnrelated = accountRepository.saveAndFlush(Account.builder().member(member)
            .name("Deleted unrelated bank").type(AccountType.CHECKING).provider("Enable Banking")
            .externalAccountId("deleted-unrelated-bank").iban("FR7612345678901234567890131")
            .deletedAt(Instant.parse("2026-01-01T00:00:00Z")).build());
        assertThat(accountRepository.existsSoftDeletedByIbanAndMemberIdAndProviderIn(
            deletedAlias.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY)).isTrue();
        assertThat(accountRepository.existsSoftDeletedByIbanAndMemberIdAndProviderIn(
            deletedUnrelated.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY)).isFalse();
        assertThat(accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            deletedAlias.getIban(), member.getId(), BOURSO_PROVIDER_FAMILY)).isEmpty();
    }

    @Test
    void ebTransactionsAreKeptWhenBoursoReplaysTheSameOperationsOnTheMatchedAccount() {
        account.setProvider("Boursorama Banque");
        account.setExternalAccountId("eb-account-" + member.getId());
        accountRepository.saveAndFlush(account);
        LocalDate operationDate = LocalDate.of(2026, 9, 20);
        List<Transaction> ebRows = List.of(
            Transaction.builder().account(account).date(operationDate).description("Market groceries")
                .amount(new BigDecimal("-10.00")).externalTransactionId("eb-operation-1")
                .categoryRef(transfer).categoryManual(true).nativeCurrency("EUR").isManual(false).build(),
            Transaction.builder().account(account).date(operationDate).description("Market groceries")
                .amount(new BigDecimal("-10.00")).externalTransactionId("eb-operation-2")
                .categoryRef(transfer).categoryManual(true).nativeCurrency("EUR").isManual(false).build());
        transactionRepository.saveAll(ebRows);

        Account matchedByVerifiedIban = accountRepository.findFirstByIbanAndMemberIdAndProviderIn(
            IBAN, member.getId(), BOURSO_PROVIDER_FAMILY).orElseThrow();
        assertThat(matchedByVerifiedIban.getId()).isEqualTo(account.getId());
        matchedByVerifiedIban.setProvider("BoursoBank");
        matchedByVerifiedIban.setExternalAccountId("bourso_synthetic-account");
        accountRepository.saveAndFlush(matchedByVerifiedIban);

        List<SidecarTransaction> repeatedOperations = List.of(
            sidecar("bourso-operation-1", "2026-09-20", "Market groceries", "-10.00"),
            sidecar("bourso-operation-2", "2026-09-20", "Market groceries", "-10.00"));
        var context = categorizationService.loadContext(member.getId());
        assertThat(importer.importFor(matchedByVerifiedIban, repeatedOperations, context, "bourso")).isZero();
        assertThat(importer.importFor(matchedByVerifiedIban, repeatedOperations, context, "bourso")).isZero();

        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
            .filteredOn(row -> IBAN.equals(row.getIban())).hasSize(1)
            .extracting(Account::getId).containsExactly(account.getId());
        List<Transaction> stored = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
        assertThat(stored).hasSize(2)
            .extracting(Transaction::getExternalTransactionId)
            .containsExactlyInAnyOrder("eb-operation-1", "eb-operation-2");
        assertThat(stored).allSatisfy(row -> {
            assertThat(row.getCategoryRef().getId()).isEqualTo(transfer.getId());
            assertThat(row.isCategoryManual()).isTrue();
        });
    }

    @Test
    void bankImportMatchesSidecarTransactionsAsAReciprocalStrictMultiset() {
        account.setProvider("BoursoBank");
        account.setExternalAccountId("bourso_synthetic-account");
        accountRepository.saveAndFlush(account);
        var context = categorizationService.loadContext(member.getId());
        List<SidecarTransaction> sidecarPurchases = List.of(
            sidecar("bourso-operation-1", "2026-09-20", "Market groceries", "-10.00"),
            sidecar("bourso-operation-2", "2026-09-20", "Market groceries", "-10.00"));
        assertThat(importer.importFor(account, sidecarPurchases, context, "bourso")).isEqualTo(2);

        // Enable Banking's upsert refreshes the shared account's external ID before the
        // transaction import, even though the preceding Bourso sync wrote its own ID there.
        account.setExternalAccountId("eb-account-" + member.getId());
        accountRepository.saveAndFlush(account);
        BankConnectorPort connector = mock(BankConnectorPort.class);
        String ebAccountId = account.getExternalAccountId();
        List<TransactionData> ebPurchases = List.of(
            new TransactionData("eb-operation-1", LocalDate.of(2026, 9, 20), "Market groceries",
                new BigDecimal("-10.00"), "EUR", null),
            new TransactionData("eb-operation-2", LocalDate.of(2026, 9, 20), "Market groceries",
                new BigDecimal("-10.00"), "EUR", null),
            new TransactionData("eb-operation-3", LocalDate.of(2026, 9, 20), "Market groceries",
                new BigDecimal("-10.00"), "EUR", null));
        when(connector.fetchTransactions(eq("eb-session"), eq(ebAccountId), any(LocalDate.class)))
            .thenReturn(ebPurchases);
        BankTransactionImportService bankImporter = new BankTransactionImportService(connector, transactionRepository, 90);

        assertThat(bankImporter.importFor(account, "eb-session")).isEqualTo(1);
        assertThat(bankImporter.importFor(account, "eb-session")).isZero();
        verify(connector, times(2)).fetchTransactions(eq("eb-session"), eq(ebAccountId), any(LocalDate.class));

        List<Transaction> stored = transactionRepository.findByAccountIdAndIsManualFalse(account.getId());
        assertThat(stored).hasSize(3);
        assertThat(stored.stream().filter(row -> row.getExternalId() != null).toList())
            .extracting(Transaction::getExternalId)
            .containsExactlyInAnyOrder("bourso-operation-1", "bourso-operation-2");
        assertThat(stored.stream().filter(row -> row.getExternalId() != null).toList())
            .extracting(Transaction::getExternalTransactionId)
            .containsExactlyInAnyOrder("bourso:bourso-operation-1", "bourso:bourso-operation-2");
        assertThat(stored.stream().filter(row -> row.getExternalId() != null).toList())
            .allSatisfy(row -> assertThat(row.getCategoryRef().getId()).isEqualTo(groceries.getId()));
        assertThat(stored.stream().filter(row -> "eb-operation-3".equals(row.getExternalTransactionId())))
            .hasSize(1);
    }

    @Test
    void postgresPreservesRoundedAmountBoundedLabelAndLongIdAcrossCrossProviderReplay() {
        LocalDate operationDate = LocalDate.of(2026, 9, 20);
        String externalId = "synthetic-reference-" + "x".repeat(140);
        String description = "🪙".repeat(260);
        BigDecimal incomingAmount = new BigDecimal("1.123456789");
        SidecarTransaction sidecarTransaction = new SidecarTransaction(externalId, operationDate, description,
            incomingAmount, "Synthetic market", "EXPENSE");
        var context = categorizationService.loadContext(member.getId());

        assertThat(importer.importFor(account, List.of(sidecarTransaction), context, "bourso")).isEqualTo(1);

        Transaction persisted = transactionRepository.findByAccountIdAndIsManualFalse(account.getId()).getFirst();
        Long persistedId = persisted.getId();
        String persistedExternalId = persisted.getExternalId();
        String persistedExternalTransactionId = persisted.getExternalTransactionId();
        assertThat(externalId.codePointCount(0, externalId.length())).isGreaterThan(128);
        assertThat(persisted.getAmount()).isEqualByComparingTo("1.12345679");
        assertThat(persisted.getAmount().scale()).isEqualTo(8);
        assertThat(persisted.getDescription()).isEqualTo("🪙".repeat(255));
        assertThat(persisted.getDescription().codePointCount(0, persisted.getDescription().length())).isEqualTo(255);
        assertThat(persistedExternalId).isEqualTo(externalId);
        assertThat(persistedExternalTransactionId).startsWith("bourso:").hasSizeLessThanOrEqualTo(128);
        assertThat(importer.importFor(account, List.of(sidecarTransaction), context, "bourso")).isZero();

        persisted.setCategoryRef(transfer);
        persisted.setCategoryManual(true);
        transactionRepository.saveAndFlush(persisted);

        BankConnectorPort connector = mock(BankConnectorPort.class);
        String ebAccountId = account.getExternalAccountId();
        when(connector.fetchTransactions(eq("synthetic-eb-session"), eq(ebAccountId), any(LocalDate.class)))
            .thenReturn(List.of(new TransactionData(externalId, operationDate, description, incomingAmount, "EUR", null)));
        BankTransactionImportService bankImporter = new BankTransactionImportService(connector, transactionRepository, 90);

        assertThat(bankImporter.importFor(account, "synthetic-eb-session")).isZero();
        verify(connector).fetchTransactions(eq("synthetic-eb-session"), eq(ebAccountId), any(LocalDate.class));

        List<Transaction> afterCrossProviderReplay = transactionRepository
            .findByAccountIdAndIsManualFalse(account.getId());
        assertThat(afterCrossProviderReplay).hasSize(1);
        Transaction unchanged = afterCrossProviderReplay.getFirst();
        assertThat(unchanged.getId()).isEqualTo(persistedId);
        assertThat(unchanged.getExternalId()).isEqualTo(persistedExternalId);
        assertThat(unchanged.getExternalTransactionId()).isEqualTo(persistedExternalTransactionId);
        assertThat(unchanged.getAmount()).isEqualByComparingTo("1.12345679");
        assertThat(unchanged.getDescription()).isEqualTo("🪙".repeat(255));
        assertThat(unchanged.getCategoryRef().getId()).isEqualTo(transfer.getId());
        assertThat(unchanged.isCategoryManual()).isTrue();
    }

    private static SidecarTransaction sidecar(String id, String date, String description, String amount) {
        return new SidecarTransaction(id, LocalDate.parse(date), description,
            new BigDecimal(amount), "Synthetic market", null);
    }
}
