package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.CaisseEpargnePort;
import com.picsou.port.PriceProviderPort;
import com.picsou.port.RevolutPort;
import com.picsou.port.RevolutPort.RevolutAccountData;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.CaisseEpargneSessionRepository;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.repository.RevolutSessionRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.budget.CategorizationService;
import com.picsou.service.budget.CategoryService;
import com.picsou.service.budget.RecurringDetectionService;
import com.picsou.service.sync.SourceSyncResult;
import com.picsou.service.sync.SyncProgressService;
import com.picsou.service.sync.SyncProvider;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:sync-service-currency;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.properties.hibernate.dialect=com.picsou.service.SyncServiceCurrencyMatchTest$AccountEnumH2Dialect"
})
@Sql("classpath:sql/sync-service-currency-test-schema.sql")
class SyncServiceCurrencyMatchTest {

    private static final String IBAN = "GB00SYNTHETICMULTICURRENCY";
    private static final String SESSION_ID = "synthetic-session";

    @Autowired AccountRepository accountRepository;
    @Autowired BalanceSnapshotRepository snapshotRepository;
    @Autowired DebtRepository debtRepository;
    @Autowired FamilyMemberRepository memberRepository;
    @Autowired TestEntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    private BankConnectorPort bankConnector;
    private AccountService accountService;
    private FamilyMember member;
    private SyncService syncService;

    @BeforeEach
    void setUp() {
        member = entityManager.persist(FamilyMember.builder()
            .displayName("Synthetic member").build());
        bankConnector = mock(BankConnectorPort.class);
        RequisitionRepository requisitionRepository = mock(RequisitionRepository.class);
        Requisition requisition = Requisition.builder()
            .id(1L).member(member).requisitionId(SESSION_ID)
            .institutionName("Synthetic bank").status(RequisitionStatus.LINKED)
            .logoBackfillAttemptedAt(Instant.now()).build();
        when(requisitionRepository.findByStatusAndMemberIdOrderByCreatedAtDesc(
            RequisitionStatus.LINKED, member.getId())).thenReturn(List.of(requisition));

        PriceProviderPort priceProvider = mock(PriceProviderPort.class);
        when(priceProvider.getFxRateToEur("GBP")).thenReturn(new BigDecimal("1.20"));
        accountService = spy(new AccountService(
            accountRepository, snapshotRepository, mock(AccountHoldingRepository.class),
            null, null, null, debtRepository, null, new PriceService(priceProvider, null),
            new LoanAmortizationService(), null, null, null, null, null, null));
        // Only response enrichment is stubbed; snapshot persistence and FX conversion run normally.
        doReturn(mock(AccountResponse.class)).when(accountService).toResponse(any(Account.class));
        syncService = new SyncService(bankConnector, accountRepository, requisitionRepository,
            null, accountService, null, null, mock(RecurringDetectionService.class), null, null,
            mock(BankTransactionImportService.class));
    }

    @Test
    void persistAccount_namedEnum_roundTripsCheckingType() {
        Account account = persistAccount("enum-checking", "EUR", null);
        entityManager.clear();

        Account reloaded = accountRepository.findByIdAndMemberId(account.getId(), member.getId()).orElseThrow();

        assertThat(reloaded.getType()).isEqualTo(AccountType.CHECKING);
        assertThat(reloaded.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void resyncAll_sharedIbanDifferentCurrencies_persistsSeparateAccountsAndEurSnapshots() {
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(
            data("uid-eur", "EUR", "100"), data("uid-gbp", "GBP", "200")));

        syncAndReload();

        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
        assertThat(accounts).hasSize(2);
        Account eur = accountInCurrency(accounts, "EUR");
        Account gbp = accountInCurrency(accounts, "GBP");
        assertThat(eur.getId()).isNotEqualTo(gbp.getId());
        assertThat(eur.getExternalAccountId()).isEqualTo("uid-eur");
        assertThat(gbp.getExternalAccountId()).isEqualTo("uid-gbp");
        assertThat(eur.getCurrentBalance()).isEqualByComparingTo("100");
        assertThat(gbp.getCurrentBalance()).isEqualByComparingTo("200");
        assertSnapshot(eur, "100");
        assertSnapshot(gbp, "240");

        syncAndReload();

        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
            .extracting(Account::getId).containsExactlyInAnyOrder(eur.getId(), gbp.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
        assertSnapshot(eur, "100");
        assertSnapshot(gbp, "240");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "EUR")
    void resyncAll_rotatedUids_matchesEachCurrencyAndDefaultsMissingCurrency(String eurCurrency) {
        Account eur = persistAccount("old-eur", "EUR", null);
        Account gbp = persistAccount("old-gbp", "GBP", null);
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(
            data("new-eur", eurCurrency, "100"), data("new-gbp", "GBP", "200")));

        syncAndReload();

        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
        assertThat(accounts).extracting(Account::getId)
            .containsExactlyInAnyOrder(eur.getId(), gbp.getId());
        Account updatedEur = accountInCurrency(accounts, "EUR");
        Account updatedGbp = accountInCurrency(accounts, "GBP");
        assertThat(updatedEur.getExternalAccountId()).isEqualTo("new-eur");
        assertThat(updatedGbp.getExternalAccountId()).isEqualTo("new-gbp");
        assertThat(updatedEur.getCurrentBalance()).isEqualByComparingTo("100");
        assertThat(updatedGbp.getCurrentBalance()).isEqualByComparingTo("200");
        assertSnapshot(updatedEur, "100");
        assertSnapshot(updatedGbp, "240");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "EUR")
    void resyncAll_deletedEurAccount_blocksRotatedEurUidButAllowsGbp(String eurCurrency) {
        Account deleted = persistAccount("deleted-eur", "EUR", Instant.now());
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(
            data("new-eur", eurCurrency, "100"), data("uid-gbp", "GBP", "200")));

        syncAndReload();

        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
        assertThat(accounts).hasSize(1);
        Account gbp = accountInCurrency(accounts, "GBP");
        assertThat(gbp.getExternalAccountId()).isEqualTo("uid-gbp");
        assertThat(gbp.getCurrentBalance()).isEqualByComparingTo("200");
        assertThat(accountRepository.findByIdAndMemberId(deleted.getId(), member.getId())).isEmpty();
        assertThat(snapshotRepository.count()).isEqualTo(1);
        assertSnapshot(gbp, "240");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"EUR", "GBP"})
    void revolutDiscoveryAndConfirm_sharedIban_updatesOnlyMatchingCurrencyAndKeepsEurSnapshots(String walletCurrency) {
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(
            data("uid-eur", "EUR", "100"), data("uid-gbp", "GBP", "200")));
        syncAndReload();
        List<Account> imported = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
        Account eur = accountInCurrency(imported, "EUR");
        Account gbp = accountInCurrency(imported, "GBP");
        assertSnapshot(gbp, "240");
        boolean gbpWallet = "GBP".equals(walletCurrency);
        String walletId = gbpWallet ? "revolut-gbp" : "revolut-eur";
        SyncProgressService progress = new SyncProgressService();
        RevolutSyncService revolut = revolutService(progress, List.of(
            wallet(walletId, walletCurrency, gbpWallet ? "200" : "125")));

        assertThat(progress.startIfIdle(member.getId(), SyncProvider.REVOLUT)).isTrue();
        revolut.discover(member.getId(), "+33000000000", "000000");

        var preview = progress.status(member.getId(), SyncProvider.REVOLUT);
        assertThat(preview.done()).isTrue();
        assertThat(preview.error()).isNull();
        assertThat(preview.discovered()).singleElement().satisfies(account -> {
            assertThat(account.externalId()).isEqualTo(walletId);
            assertThat(account.alreadyImported()).isTrue();
        });
        assertThat(accountRepository.findByIdAndMemberId(eur.getId(), member.getId()).orElseThrow()
            .getCurrentBalance()).isEqualByComparingTo("100");

        assertThat(revolut.confirmSync(member.getId(), List.of(walletId), false, false)).hasSize(1);
        entityManager.flush();
        entityManager.clear();

        List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
        assertThat(accounts).extracting(Account::getId).containsExactlyInAnyOrder(eur.getId(), gbp.getId());
        Account updatedEur = accountInCurrency(accounts, "EUR");
        assertThat(updatedEur.getExternalAccountId()).isEqualTo(gbpWallet ? "uid-eur" : "revolut-eur");
        assertThat(updatedEur.getCurrentBalance()).isEqualByComparingTo(gbpWallet ? "100" : "125");
        Account updatedGbp = accountInCurrency(accounts, "GBP");
        assertThat(updatedGbp.getExternalAccountId()).isEqualTo(gbpWallet ? "revolut-gbp" : "uid-gbp");
        assertThat(updatedGbp.getCurrentBalance()).isEqualByComparingTo("200");
        assertSnapshot(updatedEur, gbpWallet ? "100" : "125");
        assertSnapshot(updatedGbp, "240");
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    @Test
    void caisseEpargneSync_sharedIban_linksLoanToImportedEurAccount() {
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(
            data("uid-eur", "EUR", "100"), data("uid-gbp", "GBP", "200")));
        syncAndReload();
        List<Account> imported = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
        Account eur = accountInCurrency(imported, "EUR");
        Account gbp = accountInCurrency(imported, "GBP");
        CaisseEpargnePort port = mock(CaisseEpargnePort.class);
        CaisseEpargnePort.LoanDetails loan = new CaisseEpargnePort.LoanDetails(
            new BigDecimal("10000"), new BigDecimal("0.045"), new BigDecimal("230"), new BigDecimal("2"),
            LocalDate.of(2026, 1, 10), LocalDate.of(2026, 2, 5), LocalDate.of(2028, 1, 5), 24, IBAN);
        when(port.fetchAccounts("synthetic-state")).thenReturn(new CaisseEpargnePort.AccountsSnapshot(
            List.of(new CaisseEpargnePort.AccountData("synthetic-loan", "LOAN", "Synthetic loan",
                new BigDecimal("-9000"), "EUR", null, false, null, null, null, null, null, null, null,
                List.of(), true, loan)), List.of()));
        CaisseEpargneSession session = CaisseEpargneSession.builder()
            .id(1L).member(member).sessionState("synthetic-encrypted-state").active(true)
            .syncStatus(CaisseEpargneSyncStatus.IDLE).build();
        CaisseEpargneSessionRepository sessions = mock(CaisseEpargneSessionRepository.class);
        when(sessions.findByMemberIdForUpdate(member.getId())).thenReturn(Optional.of(session));
        when(sessions.findByIdAndMemberIdForUpdate(session.getId(), member.getId()))
            .thenReturn(Optional.of(session));
        when(sessions.findByMemberId(member.getId())).thenReturn(Optional.of(session));
        CryptoEncryption encryption = mock(CryptoEncryption.class);
        when(encryption.decrypt(session.getSessionState())).thenReturn("synthetic-state");
        CaisseEpargneSyncService caisseEpargne = new CaisseEpargneSyncService(
            port, sessions, accountRepository, mock(TransactionRepository.class), memberRepository,
            debtRepository, accountService, mock(CategorizationService.class), mock(CategoryService.class),
            encryption, new TransactionTemplate(transactionManager), Runnable::run, null);

        assertThat(caisseEpargne.queueSync(member.getId()).syncStatus()).isEqualTo(CaisseEpargneSyncStatus.SUCCESS);
        entityManager.flush();
        entityManager.clear();

        Account savedLoan = accountRepository.findByExternalAccountIdAndMemberId("ce_synthetic-loan", member.getId())
            .orElseThrow();
        Debt debt = debtRepository.findByAccountId(savedLoan.getId()).orElseThrow();
        assertThat(debt.getLinkedAccount().getId()).isEqualTo(eur.getId());
        assertThat(savedLoan.getCurrentBalance()).isEqualByComparingTo("9000");
        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId())).hasSize(3);
        assertThat(snapshotRepository.count()).isEqualTo(3);
        assertSnapshot(savedLoan, "9000");
        assertSnapshot(eur, "100");
        assertSnapshot(gbp, "240");
    }

    @Test
    void revolutConfirm_deletedEur_blocksEurButAllowsNewGbpWithSharedIban() {
        Account deleted = persistAccount("deleted-eur", "EUR", Instant.now());
        SyncProgressService progress = new SyncProgressService();
        RevolutSyncService revolut = revolutService(progress, List.of(
            wallet("revolut-eur", "EUR", "100"), wallet("revolut-gbp", "GBP", "200")));
        progress.startIfIdle(member.getId(), SyncProvider.REVOLUT);
        revolut.discover(member.getId(), "+33000000000", "000000");

        assertThat(progress.status(member.getId(), SyncProvider.REVOLUT).error()).isNull();
        assertThat(revolut.confirmSync(member.getId(), List.of("revolut-eur", "revolut-gbp"), false, false))
            .hasSize(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
            .singleElement().satisfies(account -> {
                assertThat(account.getExternalAccountId()).isEqualTo("revolut-gbp");
                assertThat(account.getCurrency()).isEqualTo("GBP");
                assertThat(account.getCurrentBalance()).isEqualByComparingTo("200");
            });
        assertThat(accountRepository.findByIdAndMemberId(deleted.getId(), member.getId())).isEmpty();
    }

    @Test
    void revolutConfirm_voluntaryRestore_keepsOtherCurrencyDeleted() {
        Account eur = persistAccount("deleted-eur", "EUR", Instant.now());
        Account gbp = persistAccount("deleted-gbp", "GBP", Instant.now());
        eur.setProvider("Revolut");
        gbp.setProvider("Revolut");
        entityManager.flush();
        entityManager.clear();
        SyncProgressService progress = new SyncProgressService();
        RevolutSyncService revolut = revolutService(progress, List.of(wallet("rotated-eur", "EUR", "125")));
        progress.startIfIdle(member.getId(), SyncProvider.REVOLUT);
        revolut.discover(member.getId(), "+33000000000", "000000");

        assertThat(revolut.confirmSync(member.getId(), List.of("rotated-eur"), false, true)).hasSize(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
            .singleElement().satisfies(account -> {
                assertThat(account.getId()).isEqualTo(eur.getId());
                assertThat(account.getExternalAccountId()).isEqualTo("rotated-eur");
                assertThat(account.getCurrentBalance()).isEqualByComparingTo("125");
            });
        assertThat(accountRepository.existsSoftDeletedByIbanAndCurrencyAndMemberId(IBAN, "GBP", member.getId()))
            .isTrue();
    }

    private RevolutSyncService revolutService(SyncProgressService progress, List<RevolutAccountData> harvested) {
        RevolutPort port = mock(RevolutPort.class);
        when(port.sync("+33000000000", "000000", member.getId())).thenReturn(harvested);
        return new RevolutSyncService(port, mock(RevolutSessionRepository.class), accountRepository,
            mock(SharedSidecarTransactionImportService.class), memberRepository, accountService,
            mock(CategorizationService.class), null, null, new TransactionTemplate(transactionManager), progress);
    }

    private RevolutAccountData wallet(String uid, String currency, String balance) {
        return new RevolutAccountData(uid, "Synthetic wallet", AccountType.CHECKING, IBAN,
            new BigDecimal(balance), currency, null, List.of());
    }

    private Account persistAccount(String uid, String currency, Instant deletedAt) {
        return accountRepository.saveAndFlush(Account.builder()
            .member(member).name("Synthetic " + currency).type(AccountType.CHECKING)
            .currency(currency).externalAccountId(uid).iban(IBAN).deletedAt(deletedAt).build());
    }

    private BankConnectorPort.AccountData data(String uid, String currency, String balance) {
        return new BankConnectorPort.AccountData(
            uid, "Synthetic account", IBAN, currency, new BigDecimal(balance));
    }

    private void syncAndReload() {
        assertThat(syncService.resyncAllReporting(member.getId()).status())
            .isEqualTo(SourceSyncResult.Status.SYNCED);
        entityManager.flush();
        entityManager.clear();
    }

    private Account accountInCurrency(List<Account> accounts, String currency) {
        return accounts.stream().filter(account -> currency.equals(account.getCurrency()))
            .findFirst().orElseThrow();
    }

    private void assertSnapshot(Account account, String expectedEur) {
        BalanceSnapshot snapshot = snapshotRepository.findByAccountIdAndDate(account.getId(), LocalDate.now())
            .orElseThrow();
        assertThat(snapshot.getBalance()).isEqualByComparingTo(expectedEur);
        assertThat(snapshot.getInvestedAmount()).isEqualByComparingTo(expectedEur);
    }

    public static class AccountEnumH2Dialect extends H2Dialect {
        @Override
        public void contributeTypes(TypeContributions contributions, ServiceRegistry serviceRegistry) {
            super.contributeTypes(contributions, serviceRegistry);
            // Account uses PostgreSQL NAMED_ENUM; this H2 fixture stores its string representation.
            contributions.getTypeConfiguration().getJdbcTypeRegistry()
                .addDescriptor(SqlTypes.NAMED_ENUM, VarcharJdbcType.INSTANCE);
        }
    }
}
