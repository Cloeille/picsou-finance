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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** PostgreSQL/Flyway acceptance coverage for the shared sidecar ledger and budget readers.
 * All account and transaction payloads in this test are synthetic; no bank is contacted.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SidecarBudgetPostgresTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate CYCLE_START = LocalDate.of(2026, 9, 15);
    private static final LocalDate CYCLE_END = LocalDate.of(2026, 10, 14);
    private static final String IBAN = "FR7612345678901234567890123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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

    private static SidecarTransaction sidecar(String id, String date, String description, String amount) {
        return new SidecarTransaction(id, LocalDate.parse(date), description,
            new BigDecimal(amount), "Synthetic market", null);
    }
}
