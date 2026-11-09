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
import static org.mockito.Mockito.verifyNoInteractions;
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
    private BankTransactionImportService bankTransactionImportService;
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
        bankTransactionImportService = mock(BankTransactionImportService.class);
        syncService = new SyncService(bankConnector, accountRepository, requisitionRepository,
            null, accountService, null, null, mock(RecurringDetectionService.class), null, null,
            bankTransactionImportService);
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

    @Test
    void resyncAll_legacyMisparsedCurrencies_reconcilesBeforeSnapshotsAndRepeatedSync() {
        Account eur = persistAccount("uid-eur", "EUR", null);
        Account gbp = persistAccount("uid-gbp", "EUR", null);
        for (Account account : List.of(eur, gbp)) {
            account.setIban(null);
            account.setCurrentBalance(new BigDecimal("100"));
            accountService.upsertSnapshotFromNative(account, new BigDecimal("100"), LocalDate.now());
        }
        entityManager.flush();
        entityManager.clear();
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(
            data("uid-eur", "EUR", "100"), data("uid-gbp", "GBP", "200")));

        for (int sync = 0; sync < 2; sync++) {
            syncAndReload();

            List<Account> accounts = accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId());
            assertThat(accounts).extracting(Account::getCurrency).containsExactlyInAnyOrder("EUR", "GBP");
            Account updatedEur = accountInCurrency(accounts, "EUR");
            Account updatedGbp = accountInCurrency(accounts, "GBP");
            assertThat(updatedEur.getId()).isEqualTo(eur.getId());
            assertThat(updatedGbp.getId()).isEqualTo(gbp.getId());
            assertThat(updatedEur.getExternalAccountId()).isEqualTo("uid-eur");
            assertThat(updatedGbp.getExternalAccountId()).isEqualTo("uid-gbp");
            assertThat(updatedEur.getIban()).isEqualTo(IBAN);
            assertThat(updatedGbp.getIban()).isEqualTo(IBAN);
            assertThat(updatedEur.getCurrentBalance()).isEqualByComparingTo("100");
            assertThat(updatedGbp.getCurrentBalance()).isEqualByComparingTo("200");
            assertThat(snapshotRepository.count()).isEqualTo(2);
            assertSnapshot(updatedEur, "100");
            assertSnapshot(updatedGbp, "240");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Revolut", "Synthetic bank"})
    void resyncAll_legacyUidWithSidecarIban_preservesBothConnectorAccounts(String legacyProvider) {
        Account legacy = persistAccount("uid-legacy", "EUR", null);
        legacy.setIban(null);
        legacy.setProvider(legacyProvider);
        Account sidecar = persistAccount("revolut-eur", "EUR", null);
        sidecar.setProvider("Revolut");
        sidecar.setCurrentBalance(new BigDecimal("50"));
        accountService.upsertSnapshotFromNative(sidecar, new BigDecimal("50"), LocalDate.now());
        entityManager.flush();
        entityManager.clear();
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data("uid-legacy", "EUR", "100")));

        for (int sync = 0; sync < 2; sync++) {
            syncAndReload();

            assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
                .extracting(Account::getId).containsExactlyInAnyOrder(legacy.getId(), sidecar.getId());
            Account updatedLegacy = accountRepository.findByExternalAccountIdAndMemberId("uid-legacy", member.getId())
                .orElseThrow();
            assertThat(updatedLegacy.getId()).isEqualTo(legacy.getId());
            assertThat(updatedLegacy.getIban()).isNull();
            assertThat(updatedLegacy.getProvider()).isEqualTo(legacyProvider);
            assertThat(updatedLegacy.getCurrentBalance()).isEqualByComparingTo("100");
            assertSnapshot(updatedLegacy, "100");
            Account unchangedSidecar = accountRepository.findByIbanAndCurrencyAndMemberId(IBAN, "EUR", member.getId())
                .orElseThrow();
            assertThat(unchangedSidecar.getId()).isEqualTo(sidecar.getId());
            assertThat(unchangedSidecar.getExternalAccountId()).isEqualTo("revolut-eur");
            assertThat(unchangedSidecar.getRequisitionId()).isNull();
            assertThat(unchangedSidecar.getCurrentBalance()).isEqualByComparingTo("50");
            assertSnapshot(unchangedSidecar, "50");
            assertThat(snapshotRepository.count()).isEqualTo(2);
        }

        SyncProgressService progress = new SyncProgressService();
        RevolutSyncService revolut = revolutService(progress, List.of(wallet("revolut-eur", "EUR", "125")));
        assertThat(progress.startIfIdle(member.getId(), SyncProvider.REVOLUT)).isTrue();
        revolut.discover(member.getId(), "+33000000000", "000000");
        var preview = progress.status(member.getId(), SyncProvider.REVOLUT);
        assertThat(preview.error()).isNull();
        assertThat(preview.discovered()).singleElement().satisfies(account ->
            assertThat(account.alreadyImported()).isTrue());
        assertThat(revolut.confirmSync(member.getId(), List.of("revolut-eur"), false, false)).hasSize(1);
        entityManager.flush();
        entityManager.clear();

        Account updatedSidecar = accountRepository.findByIbanAndCurrencyAndMemberId(IBAN, "EUR", member.getId())
            .orElseThrow();
        assertThat(updatedSidecar.getId()).isEqualTo(sidecar.getId());
        assertThat(updatedSidecar.getExternalAccountId()).isEqualTo("revolut-eur");
        assertThat(updatedSidecar.getCurrentBalance()).isEqualByComparingTo("125");
        assertSnapshot(updatedSidecar, "125");
        Account unchangedLegacy = accountRepository.findByExternalAccountIdAndMemberId("uid-legacy", member.getId())
            .orElseThrow();
        assertThat(unchangedLegacy.getId()).isEqualTo(legacy.getId());
        assertThat(unchangedLegacy.getIban()).isNull();
        assertThat(unchangedLegacy.getCurrentBalance()).isEqualByComparingTo("100");
        assertSnapshot(unchangedLegacy, "100");
        assertThat(accountRepository.count()).isEqualTo(2);
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Revolut", "Boursorama"})
    void resyncAll_deletedLegacyUidWithActiveConnectorIban_leavesConnectorUntouched(String provider) {
        Account deleted = persistAccount("uid-legacy", "EUR", Instant.now());
        deleted.setIban(null);
        deleted.setCurrentBalance(new BigDecimal("100"));
        accountService.upsertSnapshotFromNative(deleted, new BigDecimal("100"), LocalDate.now());
        Account sidecar = persistAccount("sidecar-eur", "EUR", null);
        sidecar.setProvider(provider);
        sidecar.setCurrentBalance(new BigDecimal("50"));
        Instant lastSyncedAt = Instant.parse("2026-01-01T00:00:00Z");
        sidecar.setLastSyncedAt(lastSyncedAt);
        accountService.upsertSnapshotFromNative(sidecar, new BigDecimal("50"), LocalDate.now());
        entityManager.flush();
        entityManager.clear();
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data("uid-legacy", "EUR", "125")));

        for (int sync = 0; sync < 2; sync++) {
            syncAndReload();

            assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
                .singleElement().satisfies(account -> {
                    assertThat(account.getId()).isEqualTo(sidecar.getId());
                    assertThat(account.getExternalAccountId()).isEqualTo("sidecar-eur");
                    assertThat(account.getProvider()).isEqualTo(provider);
                    assertThat(account.getRequisitionId()).isNull();
                    assertThat(account.getIban()).isEqualTo(IBAN);
                    assertThat(account.getCurrency()).isEqualTo("EUR");
                    assertThat(account.getCurrentBalance()).isEqualByComparingTo("50");
                    assertThat(account.getLastSyncedAt()).isEqualTo(lastSyncedAt);
                    assertSnapshot(account, "50");
                });
            assertThat(accountRepository.existsSoftDeletedByExternalAccountIdAndMemberId(
                "uid-legacy", member.getId())).isTrue();
            // Repository snapshot lookups join active accounts and hide this deleted account's history.
            Object[] deletedSnapshot = (Object[]) entityManager.getEntityManager().createNativeQuery(
                "SELECT balance, invested_amount FROM balance_snapshot WHERE account_id = :accountId AND date = :date")
                .setParameter("accountId", deleted.getId())
                .setParameter("date", LocalDate.now())
                .getSingleResult();
            assertThat((BigDecimal) deletedSnapshot[0]).isEqualByComparingTo("100");
            assertThat((BigDecimal) deletedSnapshot[1]).isEqualByComparingTo("100");
            assertThat(snapshotRepository.count()).isEqualTo(2);
        }
        verifyNoInteractions(bankTransactionImportService);
    }

    @Test
    void resyncAll_deletedUidForOtherMember_allowsIbanRotationRecovery() {
        FamilyMember otherMember = entityManager.persist(FamilyMember.builder()
            .displayName("Other synthetic member").build());
        accountRepository.saveAndFlush(Account.builder()
            .member(otherMember).name("Deleted synthetic account").type(AccountType.CHECKING)
            .currency("EUR").externalAccountId("rotated-eur").deletedAt(Instant.now()).build());
        Account existing = persistAccount("old-eur", "EUR", null);
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data("rotated-eur", "EUR", "125")));

        syncAndReload();

        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
            .singleElement().satisfies(account -> {
                assertThat(account.getId()).isEqualTo(existing.getId());
                assertThat(account.getExternalAccountId()).isEqualTo("rotated-eur");
                assertThat(account.getCurrentBalance()).isEqualByComparingTo("125");
                assertSnapshot(account, "125");
            });
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
    @ValueSource(booleans = {false, true})
    void resyncAll_legacyUidWithDeletedSidecar_preservesAccountsOnRevolutReconnect(boolean confirmSelection) {
        Account legacy = persistAccount("uid-legacy", "EUR", null);
        legacy.setIban(null);
        Account deleted = persistAccount("revolut-deleted", "EUR", Instant.now());
        deleted.setProvider("Revolut");
        deleted.setCurrentBalance(new BigDecimal("50"));
        accountService.upsertSnapshotFromNative(deleted, new BigDecimal("50"), LocalDate.now());
        entityManager.flush();
        entityManager.clear();
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data("uid-legacy", "EUR", "100")));

        syncAndReload();
        syncAndReload();

        assertThat(accountRepository.findByIdAndMemberId(deleted.getId(), member.getId())).isEmpty();
        SyncProgressService progress = new SyncProgressService();
        RevolutSyncService revolut = revolutService(progress, List.of(wallet("revolut-deleted", "EUR", "125")));
        if (confirmSelection) {
            assertThat(progress.startIfIdle(member.getId(), SyncProvider.REVOLUT)).isTrue();
            revolut.discover(member.getId(), "+33000000000", "000000");
            assertThat(progress.status(member.getId(), SyncProvider.REVOLUT).error()).isNull();
            assertThat(revolut.confirmSync(member.getId(), List.of("revolut-deleted"), false, true)).hasSize(1);
        } else {
            assertThat(revolut.sync(member.getId(), "+33000000000", "000000", false)).hasSize(1);
        }
        entityManager.flush();
        entityManager.clear();
        syncAndReload();

        Account updatedLegacy = accountRepository.findByExternalAccountIdAndMemberId("uid-legacy", member.getId())
            .orElseThrow();
        assertThat(updatedLegacy.getId()).isEqualTo(legacy.getId());
        assertThat(updatedLegacy.getIban()).isNull();
        assertThat(updatedLegacy.getCurrentBalance()).isEqualByComparingTo("100");
        assertSnapshot(updatedLegacy, "100");
        Account restoredSidecar = accountRepository.findByIbanAndCurrencyAndMemberId(IBAN, "EUR", member.getId())
            .orElseThrow();
        assertThat(restoredSidecar.getId()).isEqualTo(deleted.getId());
        assertThat(restoredSidecar.getExternalAccountId()).isEqualTo("revolut-deleted");
        assertThat(restoredSidecar.getCurrentBalance()).isEqualByComparingTo("125");
        assertSnapshot(restoredSidecar, "125");
        assertThat(accountRepository.findAllByMemberIdOrderByCreatedAtAsc(member.getId()))
            .extracting(Account::getId).containsExactlyInAnyOrder(legacy.getId(), deleted.getId());
        assertThat(snapshotRepository.count()).isEqualTo(2);
    }

    @Test
    void resyncAll_legacyUidWithDeletedSidecarInOtherCurrency_backfillsIban() {
        Account legacy = persistAccount("uid-legacy", "EUR", null);
        legacy.setIban(null);
        Account deleted = persistAccount("revolut-deleted", "GBP", Instant.now());
        deleted.setProvider("Revolut");
        entityManager.flush();
        entityManager.clear();
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data("uid-legacy", "EUR", "100")));

        syncAndReload();

        Account updated = accountRepository.findByIbanAndCurrencyAndMemberId(IBAN, "EUR", member.getId())
            .orElseThrow();
        assertThat(updated.getId()).isEqualTo(legacy.getId());
        assertThat(updated.getCurrentBalance()).isEqualByComparingTo("100");
        assertThat(accountRepository.findByIdAndMemberId(deleted.getId(), member.getId())).isEmpty();
        assertSnapshot(updated, "100");
    }

    @Test
    void resyncAll_legacyUidWithDeletedSidecarForOtherMember_backfillsIban() {
        Account legacy = persistAccount("uid-legacy", "EUR", null);
        legacy.setIban(null);
        FamilyMember otherMember = entityManager.persist(FamilyMember.builder()
            .displayName("Other synthetic member").build());
        Account deleted = accountRepository.saveAndFlush(Account.builder()
            .member(otherMember).name("Synthetic EUR").type(AccountType.CHECKING)
            .currency("EUR").externalAccountId("revolut-deleted").iban(IBAN)
            .provider("Revolut").deletedAt(Instant.now()).build());
        entityManager.flush();
        entityManager.clear();
        when(bankConnector.fetchBalances(SESSION_ID)).thenReturn(List.of(data("uid-legacy", "EUR", "100")));

        syncAndReload();

        Account updated = accountRepository.findByIbanAndCurrencyAndMemberId(IBAN, "EUR", member.getId())
            .orElseThrow();
        assertThat(updated.getId()).isEqualTo(legacy.getId());
        assertThat(updated.getCurrentBalance()).isEqualByComparingTo("100");
        assertThat(accountRepository.findByIdAndMemberId(deleted.getId(), otherMember.getId())).isEmpty();
        assertSnapshot(updated, "100");
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
