package com.picsou.service;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.model.SidecarSession;
import com.picsou.repository.SidecarSessionRepository;
import com.picsou.model.SidecarSyncStatus;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.*;
import com.picsou.adapter.CorumAdapter;
import com.picsou.adapter.SofidyAdapter;
import com.picsou.repository.*;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Real transactions, migrations and account writes; Mockito only at the external port. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CorumSyncService.class, SofidySyncService.class, ScpiPositionService.class,
    ScpiFundReconciler.class, ScpiFundService.class, CorumLifeWriter.class,
    CorumLifeOperationWriter.class, com.picsou.service.budget.CategoryService.class,
    com.picsou.finary.FinaryPersistenceHelper.class,
    AccountService.class, PeerLoanTransactionLinks.class,
    ScpiSyncSessionPostgresTest.Transactions.class})
class ScpiSyncSessionPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @TestConfiguration
    static class Transactions {
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
        @Bean("corumSyncExecutor") Executor corumExecutor() { return Runnable::run; }
        @Bean("corumReloginBuckets") java.util.Map<Long, io.github.bucket4j.Bucket> corumReloginBuckets() {
            return new com.picsou.config.RateLimitConfig().corumReloginBuckets();
        }
        @Bean("sofidySyncExecutor") Executor sofidyExecutor() { return Runnable::run; }
    }

    @Autowired CorumSyncService corum;
    @Autowired SofidySyncService sofidy;
    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired ScpiPositionRepository positions;
    @Autowired SidecarSessionRepository sessions;
    @Autowired ScpiProviderFundRepository funds;
    @Autowired ScpiFundService fundService;
    @MockitoBean CorumAdapter corumPort;
    @MockitoBean SofidyAdapter sofidyPort;
    @MockitoBean CryptoEncryption encryption;
    // The real AccountService, so the Life balance snapshot is written to PostgreSQL; only its
    // collaborators outside the persistence layer are mocked.
    @MockitoBean PriceService priceService;
    @MockitoBean LoanAmortizationService loanAmortizationService;
    @MockitoBean AccountAccessResolver accessResolver;
    @MockitoBean BankLogoResolver bankLogoResolver;
    @MockitoBean CryptoLogoService cryptoLogoService;
    @MockitoBean InstrumentLogoService instrumentLogoService;
    @Autowired BalanceSnapshotRepository snapshots;
    @Autowired AccountHoldingRepository holdings;
    @Autowired TransactionRepository transactions;
    @Autowired CategoryRepository categories;
    FamilyMember member;
    Account account;
    ScpiPosition position;

    @BeforeEach
    void seed() {
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        member = members.saveAndFlush(FamilyMember.builder().displayName("SCPI concurrency").build());
        account = accounts.saveAndFlush(Account.builder().member(member).name("Fund")
            .type(AccountType.SCPI).currentBalance(new BigDecimal("900")).build());
        position = positions.saveAndFlush(ScpiPosition.builder().member(member).account(account)
            .shareCount(new BigDecimal("3")).withdrawalPriceEur(new BigDecimal("300"))
            .corumFundCode("FUND").sofidyFundCode("FUND").build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CORUM_DELETE", "CORUM_REPLACE", "SOFIDY_DELETE", "SOFIDY_REPLACE"})
    void staleSnapshotCannotChangePersistedBalancesOrReplacementStatus(String scenario) throws Exception {
        boolean isCorum = scenario.startsWith("CORUM");
        boolean replace = scenario.endsWith("REPLACE");
        if (isCorum) {
            sessions.saveAndFlush(SidecarSession.create(SidecarBank.CORUM, member, "old-state", Instant.now()));
        } else {
            sessions.saveAndFlush(SidecarSession.create(SidecarBank.SOFIDY, member, "old-state", Instant.now()));
        }
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        if (isCorum) {
            when(corumPort.fetchPortfolio("old-state")).thenAnswer(inv -> {
                awaitRelease(fetching, release);
                return new CorumAdapter.Portfolio(new CorumAdapter.Snapshot("contract", "PP", "EUR",
                    new BigDecimal("600"), null, true, List.of(new CorumAdapter.Holding("FUND", "Fund",
                        new BigDecimal("2"), new BigDecimal("300"), null, null, null))), List.of());
            });
        } else {
            when(sofidyPort.fetchSnapshot("old-state")).thenAnswer(inv -> {
                awaitRelease(fetching, release);
                return new SofidyAdapter.Snapshot("EUR", new BigDecimal("600"), null, true,
                    List.of(new SofidyAdapter.Holding("FUND", "Fund", new BigDecimal("2"),
                        new BigDecimal("300"), new BigDecimal("600"))));
            });
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                if (isCorum) { corum.queueSync(member.getId()); }
                else { sofidy.queueSync(member.getId()); }
            } catch (Throwable ex) { failure.set(ex); }
        });
        worker.start();
        try {
            assertThat(fetching.await(10, TimeUnit.SECONDS)).isTrue();
            if (isCorum) {
                corum.clearSession(member.getId());
                if (replace) {
                    sessions.saveAndFlush(SidecarSession.create(SidecarBank.CORUM, member, "new-state", Instant.now()));
                }
            } else {
                sofidy.clearSession(member.getId());
                if (replace) {
                    sessions.saveAndFlush(SidecarSession.create(SidecarBank.SOFIDY, member, "new-state", Instant.now()));
                }
            }
        } finally {
            release.countDown();
            worker.join(10000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(accounts.findById(account.getId()).orElseThrow().getCurrentBalance())
            .isEqualByComparingTo("900");
        assertThat(positions.findById(position.getId()).orElseThrow().getShareCount())
            .isEqualByComparingTo("3");
        if (isCorum) {
            var current = sessions.findByBankAndMemberId(SidecarBank.CORUM, member.getId());
            if (replace) { assertThat(current.orElseThrow().getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE); }
            else { assertThat(current).isEmpty(); }
        } else {
            var current = sessions.findByBankAndMemberId(SidecarBank.SOFIDY, member.getId());
            if (replace) { assertThat(current.orElseThrow().getSyncStatus()).isEqualTo(SidecarSyncStatus.IDLE); }
            else { assertThat(current).isEmpty(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sofidyRejectsMissingTotalOrQuantityWithoutChangingPersistedAccount(boolean missingQuantity) {
        sessions.saveAndFlush(SidecarSession.create(SidecarBank.SOFIDY, member, "old-state", Instant.now()));
        when(sofidyPort.fetchSnapshot("old-state")).thenReturn(new SofidyAdapter.Snapshot("EUR",
            missingQuantity ? BigDecimal.ZERO : null, null, true,
            missingQuantity ? List.of(new SofidyAdapter.Holding("FUND", "Fund", null,
                new BigDecimal("300"), null)) : List.of()));

        assertThat(sofidy.queueSync(member.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        assertThat(accounts.findById(account.getId()).orElseThrow().getCurrentBalance())
            .isEqualByComparingTo("900");
        assertThat(positions.findById(position.getId()).orElseThrow().getShareCount())
            .isEqualByComparingTo("3");
    }

    /** #232 end to end on the real schema: create, re-sync without a duplicate, delete, not recreated. */
    @Test
    void corumSyncCreatesTheAccountOnceAndDoesNotRecreateADeletedOne() {
        FamilyMember fresh = members.saveAndFlush(FamilyMember.builder().displayName("SCPI auto").build());
        when(corumPort.fetchPortfolio("auto-state")).thenReturn(new CorumAdapter.Portfolio(
            corumUsa(), List.of()));

        sessions.saveAndFlush(SidecarSession.create(SidecarBank.CORUM, fresh, "auto-state", Instant.now()));
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);

        List<Account> created = accounts.findAllByMemberIdOrderByCreatedAtAsc(fresh.getId());
        assertThat(created).singleElement().satisfies(a -> {
            assertThat(a.getName()).isEqualTo("CORUM USA");
            assertThat(a.getType()).isEqualTo(AccountType.SCPI);
            assertThat(a.getCurrentBalance()).isEqualByComparingTo("1800");
        });
        assertThat(funds.findByMemberIdAndProviderAndFundCode(fresh.getId(), ScpiProvider.CORUM, "US"))
            .hasValueSatisfying(f -> assertThat(f.getLastOutcome()).isEqualTo(ScpiFundOutcome.UPDATED));

        // What AccountService.delete does (covered by AccountServiceDeleteTest),
        // replayed here on the real schema: tombstone, unlink, soft-delete.
        Account doomed = created.get(0);
        ScpiPosition linked = positions.findByAccountIdAndMemberId(doomed.getId(), fresh.getId()).orElseThrow();
        fundService.tombstone(fresh.getId(), ScpiProvider.CORUM, linked.getCorumFundCode());
        linked.setCorumFundCode(null);
        positions.saveAndFlush(linked);
        doomed.setDeletedAt(Instant.now());
        accounts.saveAndFlush(doomed);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);

        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(fresh.getId())).isEmpty();
        assertThat(funds.findByMemberIdAndProviderAndFundCode(fresh.getId(), ScpiProvider.CORUM, "US"))
            .hasValueSatisfying(f -> {
                assertThat(f.isIgnored()).isTrue();
                assertThat(f.getLastOutcome()).isEqualTo(ScpiFundOutcome.IGNORED);
            });
    }

    /** A CORUM Life contract end to end on the real schema: create once, re-sync, delete, not recreated. */
    @Test
    void corumLifeSyncCreatesTheAccountOnceAndDoesNotRecreateADeletedOne() {
        FamilyMember fresh = members.saveAndFlush(FamilyMember.builder().displayName("CORUM Life").build());
        when(corumPort.fetchPortfolio("life-state")).thenReturn(new CorumAdapter.Portfolio(
            null, List.of(lifeContract("1001"))));

        sessions.saveAndFlush(SidecarSession.create(SidecarBank.CORUM, fresh, "life-state", Instant.now()));
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);

        List<Account> created = accounts.findAllByMemberIdOrderByCreatedAtAsc(fresh.getId());
        assertThat(created).singleElement().satisfies(a -> {
            assertThat(a.getName()).isEqualTo("CORUM Life");
            assertThat(a.getType()).isEqualTo(AccountType.ASSURANCE_VIE);
            assertThat(a.getExternalAccountId()).isEqualTo("corum_life_1001");
            assertThat(a.isManual()).isFalse();
            assertThat(a.getCurrentBalance()).isEqualByComparingTo("1534.37");
            assertThat(a.getCashBalance()).isEqualByComparingTo("0");
            // The V125 columns, read back from PostgreSQL.
            assertThat(a.getProviderInvestedEur()).isEqualByComparingTo("1480");
            assertThat(a.getProviderPaidInEur()).isEqualByComparingTo("1500");
            assertThat(a.getScheduledPaymentEur()).isEqualByComparingTo("50");
            assertThat(a.getScheduledPaymentCadence()).isEqualTo(RecurringCadence.MONTHLY);
            assertThat(a.getOpenedAt()).isEqualTo(LocalDate.of(2024, 1, 15));
        });
        assertThat(holdings.findByAccount_Id(created.get(0).getId()))
            .extracting(AccountHolding::getTicker, AccountHolding::getProviderWealthTier)
            .containsExactlyInAnyOrder(
                tuple("CORUM-LIFE-XL", WealthTier.REAL_ESTATE),
                tuple("CORUM-LIFE-FGCORUM", null));
        // Two syncs on one day: one snapshot, the contract's total against its own cost.
        assertThat(snapshots.findByAccountIdAndDate(created.get(0).getId(), LocalDate.now()))
            .hasValueSatisfying(snapshot -> {
                assertThat(snapshot.getBalance()).isEqualByComparingTo("1534.37");
                assertThat(snapshot.getInvestedAmount()).isEqualByComparingTo("1480");
            });

        Account doomed = created.get(0);
        doomed.setDeletedAt(Instant.now());
        accounts.saveAndFlush(doomed);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);

        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(fresh.getId())).isEmpty();
    }

    /** All-or-nothing: a life write the database refuses also undoes the SCPI funds written before it. */
    @Test
    void corumLifeWriteFailureRollsBackTheScpiFundsOfTheSameRun() {
        FamilyMember fresh = members.saveAndFlush(FamilyMember.builder().displayName("CORUM rollback").build());
        // external_account_id is VARCHAR(255): this contract passes validation, then fails on write.
        when(corumPort.fetchPortfolio("rollback-state")).thenReturn(new CorumAdapter.Portfolio(
            corumUsa(), List.of(lifeContract("9".repeat(300)))));

        sessions.saveAndFlush(SidecarSession.create(SidecarBank.CORUM, fresh, "rollback-state", Instant.now()));
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);

        assertThat(accounts.findAllByMemberIdOrderByCreatedAtAsc(fresh.getId())).isEmpty();
        assertThat(funds.findByMemberIdAndProviderAndFundCode(fresh.getId(), ScpiProvider.CORUM, "US")).isEmpty();
    }

    /** V127 on the real schema: remembered credentials re-log in an expired session, a rejected password clears them. */
    @Test
    void corumRememberedCredentialsReLogInAndARejectedPasswordClearsThem() {
        when(encryption.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        FamilyMember fresh = members.saveAndFlush(FamilyMember.builder().displayName("CORUM relogin").build());
        when(corumPort.authenticate("client", "pw")).thenReturn("first-state", "fresh-state");
        when(corumPort.fetchPortfolio("first-state")).thenReturn(new CorumAdapter.Portfolio(corumUsa(), List.of()));
        when(corumPort.fetchPortfolio("expired-state"))
            .thenThrow(new com.picsou.exception.SyncException("expired", null, "SESSION_EXPIRED"));
        when(corumPort.fetchPortfolio("fresh-state")).thenReturn(new CorumAdapter.Portfolio(corumUsa(), List.of()));

        assertThat(corum.authenticate("client", "pw", true, fresh.getId()).credentialsRemembered()).isTrue();
        SidecarSession stored = sessions.findByBankAndMemberId(SidecarBank.CORUM, fresh.getId()).orElseThrow();
        assertThat(stored.getEncryptedCredentials()).contains("\"password\":\"pw\"");

        // The cookie died: the next sync logs in again and persists the new session.
        stored.updateSessionState("expired-state", Instant.now());
        sessions.saveAndFlush(stored);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        SidecarSession relogged = sessions.findByBankAndMemberId(SidecarBank.CORUM, fresh.getId()).orElseThrow();
        assertThat(relogged.getSessionState()).isEqualTo("fresh-state");
        assertThat(relogged.isActive()).isTrue();

        when(corumPort.authenticate("client", "pw"))
            .thenThrow(new com.picsou.exception.SyncException("rejected", null, "INVALID_CREDENTIALS"));
        relogged.updateSessionState("expired-state", Instant.now());
        sessions.saveAndFlush(relogged);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.FAILED);
        SidecarSession rejected = sessions.findByBankAndMemberId(SidecarBank.CORUM, fresh.getId()).orElseThrow();
        assertThat(rejected.getEncryptedCredentials()).isNull();
        assertThat(rejected.isActive()).isFalse();
        assertThat(rejected.getLastSyncError()).isEqualTo(com.picsou.port.SidecarErrorCode.INVALID_CREDENTIALS);

        // Logging in again without "remember" stores nothing; disconnecting removes the row.
        when(corumPort.authenticate("client", "pw2")).thenReturn("first-state");
        assertThat(corum.authenticate("client", "pw2", false, fresh.getId()).credentialsRemembered()).isFalse();
        assertThat(sessions.findByBankAndMemberId(SidecarBank.CORUM, fresh.getId()).orElseThrow().getEncryptedCredentials()).isNull();
        corum.clearSession(fresh.getId());
        assertThat(sessions.findByBankAndMemberId(SidecarBank.CORUM, fresh.getId())).isEmpty();
    }

    /** A complete CORUM snapshot holding one fund (US, 10 units at 180 EUR). */
    private static CorumAdapter.Snapshot corumUsa() {
        return new CorumAdapter.Snapshot("contract", "PP", "EUR", new BigDecimal("1800"), null, true,
            List.of(new CorumAdapter.Holding("US", "CORUM USA", new BigDecimal("10"), new BigDecimal("180"),
                null, new BigDecimal("1800"), null)));
    }

    /** CORUM Life operations end to end on the real schema (V126, the external-id unique index). */
    @Test
    void corumLifeOperationsAreWrittenOnceAndKeptOutOfTheBudget() {
        FamilyMember fresh = members.saveAndFlush(FamilyMember.builder().displayName("CORUM ops").build());
        List<CorumAdapter.LifeOperation> operations = List.of(
            new CorumAdapter.LifeOperation("10", "couponDetachment", "couponDetachment", LocalDate.of(2026, 10, 31),
                new BigDecimal("3.21"), List.of("XL")),
            new CorumAdapter.LifeOperation("9", "scheduledPayment", "progressiveSavings", LocalDate.of(2026, 10, 5),
                new BigDecimal("50"), null),
            // Two legs of one switch under one id: keyed by sign, both stored.
            new CorumAdapter.LifeOperation("7", "arbitrage", "arbitrage", LocalDate.of(2026, 6, 15),
                new BigDecimal("-100"), null),
            new CorumAdapter.LifeOperation("7", "arbitrage", "arbitrage", LocalDate.of(2026, 6, 15),
                new BigDecimal("100"), null));
        CorumAdapter.LifeContract base = lifeContract("2002");
        when(corumPort.fetchPortfolio("ops-state")).thenReturn(new CorumAdapter.Portfolio(null, List.of(
            new CorumAdapter.LifeContract(base.contractCode(), base.currency(), base.totalValuationEur(),
                base.valuationDate(), base.valuationLineCount(), base.netInvestedEur(), base.paidInEur(),
                base.openedAt(), base.scheduledPayment(), true, base.holdings(), operations))));

        sessions.saveAndFlush(SidecarSession.create(SidecarBank.CORUM, fresh, "ops-state", Instant.now()));
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);
        assertThat(corum.queueSync(fresh.getId()).syncStatus()).isEqualTo(SidecarSyncStatus.SUCCESS);

        Account contract = accounts.findAllByMemberIdOrderByCreatedAtAsc(fresh.getId()).getFirst();
        List<Transaction> rows = transactions.findByAccountIdOrderByDateDesc(contract.getId());
        assertThat(rows).extracting(Transaction::getExternalId, Transaction::isBudgetNeutral, Transaction::getTxType)
            .containsExactlyInAnyOrder(
                tuple("corum:10", true, TransactionType.DIVIDEND),
                tuple("corum:9", false, TransactionType.DEPOSIT),
                tuple("corum:7:-", true, null),
                tuple("corum:7:+", true, null));
        Category investment = categories.findAllByMemberIdOrderBySortOrderAscIdAsc(fresh.getId()).stream()
            .filter(c -> "investissement".equals(c.getSlug())).findFirst().orElseThrow();
        Transaction payment = rows.stream().filter(r -> "corum:9".equals(r.getExternalId())).findFirst().orElseThrow();
        assertThat(transactions.findById(payment.getId()).orElseThrow().getCategoryRef().getId())
            .isEqualTo(investment.getId());

        // Only the payment reaches the budget and the global list.
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        assertThat(transactions.searchByMember(fresh.getId(), from, to, null, null))
            .extracting(Transaction::getExternalId).containsExactly("corum:9");
        assertThat(transactions.searchByMember(fresh.getId(), from, to, contract.getId(), null))
            .extracting(Transaction::getExternalId).containsExactly("corum:9");
        assertThat(transactions.findByMemberIdAndDateBetween(fresh.getId(), from, to))
            .extracting(Transaction::getExternalId).containsExactly("corum:9");
        assertThat(transactions.findByMemberIdAndKindAndDateBetween(fresh.getId(), CategoryKind.TRANSFER, from, to))
            .extracting(Transaction::getExternalId).containsExactly("corum:9");
    }

    /**
     * A complete CORUM Life contract worth 1534.37 EUR: one SCPI unit line (realEstate) and the euro
     * fund (euroLife).
     */
    private static CorumAdapter.LifeContract lifeContract(String code) {
        return new CorumAdapter.LifeContract(code, "EUR", new BigDecimal("1534.37"), null, 3,
            new BigDecimal("1480"), new BigDecimal("1500"), LocalDate.of(2024, 1, 15),
            new CorumAdapter.ScheduledPayment(new BigDecimal("50"), "monthly"), true, List.of(
            new CorumAdapter.LifeHolding("XL", "CORUM XL", new BigDecimal("2.65955"), new BigDecimal("195"),
                new BigDecimal("518.61"), new BigDecimal("480"), null, false, "realEstate"),
            new CorumAdapter.LifeHolding("FGCORUM", "CORUM EuroLife (fonds euros)", new BigDecimal("1015.76"),
                BigDecimal.ONE, new BigDecimal("1015.76"), new BigDecimal("1000"), null, true, "euroLife")),
            null);
    }

    private void awaitRelease(CountDownLatch fetching, CountDownLatch release) throws InterruptedException {
        fetching.countDown();
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
