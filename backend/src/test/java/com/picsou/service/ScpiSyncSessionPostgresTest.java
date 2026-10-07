package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.*;
import com.picsou.port.CorumPort;
import com.picsou.port.SofidyPort;
import com.picsou.repository.*;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Real transactions, migrations and account writes; Mockito only at the external port. */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CorumSyncService.class, SofidySyncService.class, ScpiPositionService.class,
    ScpiSyncSessionPostgresTest.Transactions.class})
class ScpiSyncSessionPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class Transactions {
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
        @Bean("corumSyncExecutor") Executor corumExecutor() { return Runnable::run; }
        @Bean("sofidySyncExecutor") Executor sofidyExecutor() { return Runnable::run; }
    }

    @Autowired CorumSyncService corum;
    @Autowired SofidySyncService sofidy;
    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired ScpiPositionRepository positions;
    @Autowired CorumSessionRepository corumSessions;
    @Autowired SofidySessionRepository sofidySessions;
    @MockitoBean CorumPort corumPort;
    @MockitoBean SofidyPort sofidyPort;
    @MockitoBean CryptoEncryption encryption;
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
            corumSessions.saveAndFlush(CorumSession.create(member, "old-state", Instant.now()));
        } else {
            sofidySessions.saveAndFlush(SofidySession.create(member, "old-state", Instant.now()));
        }
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        if (isCorum) {
            when(corumPort.fetchSnapshot("old-state")).thenAnswer(inv -> {
                awaitRelease(fetching, release);
                return new CorumPort.Snapshot("contract", "PP", "EUR", new BigDecimal("600"), null,
                    true, List.of(new CorumPort.Holding("FUND", "Fund", new BigDecimal("2"),
                        new BigDecimal("300"), null, null, null)));
            });
        } else {
            when(sofidyPort.fetchSnapshot("old-state")).thenAnswer(inv -> {
                awaitRelease(fetching, release);
                return new SofidyPort.Snapshot("EUR", new BigDecimal("600"), null, true,
                    List.of(new SofidyPort.Holding("FUND", "Fund", new BigDecimal("2"),
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
                    corumSessions.saveAndFlush(CorumSession.create(member, "new-state", Instant.now()));
                }
            } else {
                sofidy.clearSession(member.getId());
                if (replace) {
                    sofidySessions.saveAndFlush(SofidySession.create(member, "new-state", Instant.now()));
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
            var current = corumSessions.findByMemberId(member.getId());
            if (replace) { assertThat(current.orElseThrow().getSyncStatus()).isEqualTo(CorumSyncStatus.IDLE); }
            else { assertThat(current).isEmpty(); }
        } else {
            var current = sofidySessions.findByMemberId(member.getId());
            if (replace) { assertThat(current.orElseThrow().getSyncStatus()).isEqualTo(SofidySyncStatus.IDLE); }
            else { assertThat(current).isEmpty(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sofidyRejectsMissingTotalOrQuantityWithoutChangingPersistedAccount(boolean missingQuantity) {
        sofidySessions.saveAndFlush(SofidySession.create(member, "old-state", Instant.now()));
        when(sofidyPort.fetchSnapshot("old-state")).thenReturn(new SofidyPort.Snapshot("EUR",
            missingQuantity ? BigDecimal.ZERO : null, null, true,
            missingQuantity ? List.of(new SofidyPort.Holding("FUND", "Fund", null,
                new BigDecimal("300"), null)) : List.of()));

        assertThat(sofidy.queueSync(member.getId()).syncStatus()).isEqualTo(SofidySyncStatus.FAILED);
        assertThat(accounts.findById(account.getId()).orElseThrow().getCurrentBalance())
            .isEqualByComparingTo("900");
        assertThat(positions.findById(position.getId()).orElseThrow().getShareCount())
            .isEqualByComparingTo("3");
    }

    private void awaitRelease(CountDownLatch fetching, CountDownLatch release) throws InterruptedException {
        fetching.countDown();
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
