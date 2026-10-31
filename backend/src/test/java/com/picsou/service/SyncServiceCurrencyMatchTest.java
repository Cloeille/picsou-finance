package com.picsou.service;

import com.picsou.dto.AccountResponse;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.BalanceSnapshot;
import com.picsou.model.FamilyMember;
import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import com.picsou.port.BankConnectorPort;
import com.picsou.port.PriceProviderPort;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.BalanceSnapshotRepository;
import com.picsou.repository.RequisitionRepository;
import com.picsou.service.budget.RecurringDetectionService;
import com.picsou.service.sync.SourceSyncResult;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/sync-service-currency-test-schema.sql")
class SyncServiceCurrencyMatchTest {

    private static final String IBAN = "GB00SYNTHETICMULTICURRENCY";
    private static final String SESSION_ID = "synthetic-session";

    @Autowired AccountRepository accountRepository;
    @Autowired BalanceSnapshotRepository snapshotRepository;
    @Autowired TestEntityManager entityManager;

    private BankConnectorPort bankConnector;
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
        AccountService accountService = spy(new AccountService(
            accountRepository, snapshotRepository, mock(AccountHoldingRepository.class),
            null, null, null, null, null, new PriceService(priceProvider, null),
            null, null, null, null, null, null, null));
        // Only response enrichment is stubbed; snapshot persistence and FX conversion run normally.
        doReturn(mock(AccountResponse.class)).when(accountService).toResponse(any(Account.class));
        syncService = new SyncService(bankConnector, accountRepository, requisitionRepository,
            null, accountService, null, null, mock(RecurringDetectionService.class), null, null,
            mock(BankTransactionImportService.class));
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
}
