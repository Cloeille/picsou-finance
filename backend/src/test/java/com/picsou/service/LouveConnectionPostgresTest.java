package com.picsou.service;

import com.picsou.adapter.LouveAdapter;
import com.picsou.adapter.LouveOAuthClient;
import com.picsou.config.CryptoEncryption;
import com.picsou.model.FamilyMember;
import com.picsou.model.LouveConnection;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.LouveConnectionRepository;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The louve_connection schema (V153) against the entity, and the property the row lock exists
 * for: two runs that both find an expiring token refresh it once. Louve rotates refresh tokens, so
 * a second refresh with the same one would end the member's connection.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({LouveSyncService.class, LouveConnectionPostgresTest.Beans.class})
class LouveConnectionPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @TestConfiguration
    static class Beans {
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired LouveSyncService service;
    @Autowired LouveConnectionRepository connections;
    @Autowired FamilyMemberRepository members;
    @MockitoBean LouveAdapter adapter;
    @MockitoBean LouveOAuthClient oauth;
    @MockitoBean CryptoEncryption encryption;
    @MockitoBean ScpiFundReconciler reconciler;
    @MockitoBean LouveClientRegistry clients;

    @Test
    void twoRunsFindingAnExpiringTokenRefreshItOnce() throws Exception {
        when(encryption.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        FamilyMember member = members.saveAndFlush(FamilyMember.builder().displayName("Louve refresh").build());
        LouveConnection row = connections.saveAndFlush(LouveConnection.builder().member(member).tokenState("tokens")
            .status(LouveConnection.STATUS_CONNECTED).build());
        LouveSyncService.Run run = new LouveSyncService.Run(member.getId(), row.getId(), row.getGrantGeneration());
        when(adapter.needsRefresh("tokens")).thenReturn(true);
        when(adapter.needsRefresh("rotated")).thenReturn(false);
        AtomicInteger refreshes = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(adapter.refresh("tokens")).thenAnswer(inv -> {
            refreshes.incrementAndGet();
            refreshing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return "rotated";
        });

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> service.freshState(run, null));
        assertThat(refreshing.await(10, TimeUnit.SECONDS)).as("the first run is refreshing").isTrue();
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> service.freshState(run, null));
        Thread.sleep(300); // the second run is now waiting on the row lock, not refreshing
        release.countDown();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
            .containsExactly("rotated", "rotated");
        assertThat(refreshes.get()).isEqualTo(1);
        assertThat(connections.findByMemberId(member.getId()))
            .hasValueSatisfying(stored -> assertThat(stored.getTokenState()).isEqualTo("rotated"));
    }

    @Autowired TransactionTemplate transactions;

    @Test
    void aRowLockHeldTooLongIsATemporaryContentionNotAWait() throws Exception {
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        FamilyMember member = members.saveAndFlush(FamilyMember.builder().displayName("Louve lock").build());
        LouveConnection row = connections.saveAndFlush(LouveConnection.builder().member(member).tokenState("tokens")
            .status(LouveConnection.STATUS_CONNECTED).build());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            connections.findByMemberIdForUpdate(member.getId());
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        CompletableFuture<Throwable> waiter = CompletableFuture.supplyAsync(() -> {
            try {
                service.freshState(new LouveSyncService.Run(member.getId(), row.getId(), 0), null);
                return null;
            } catch (Throwable ex) {
                return ex;
            }
        });
        Throwable outcome = waiter.get(20, TimeUnit.SECONDS);
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(outcome).isInstanceOfSatisfying(LouveSyncService.Transient.class,
            ex -> assertThat(ex.getCode()).isEqualTo("UPSTREAM_UNAVAILABLE"));
        assertThat(waitedMillis).isLessThan(15_000);
        assertThat(connections.findByMemberId(member.getId()))
            .hasValueSatisfying(stored -> {
                assertThat(stored.getTokenState()).isEqualTo("tokens");
                assertThat(stored.getStatus()).isEqualTo(LouveConnection.STATUS_CONNECTED);
            });
    }

    @Test
    void aDisconnectRolledBackByItsCallerRevokesNothingAndOneCommittedRevokesOnce() {
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        FamilyMember member = members.saveAndFlush(FamilyMember.builder().displayName("Louve rollback").build());
        connections.saveAndFlush(LouveConnection.builder().member(member).tokenState("tokens")
            .status(LouveConnection.STATUS_CONNECTED).build());

        transactions.executeWithoutResult(status -> {
            service.deleteConnection(member.getId());
            status.setRollbackOnly(); // the account deletion around it fails later
        });

        org.mockito.Mockito.verify(adapter, org.mockito.Mockito.never()).revoke(anyString());
        assertThat(connections.findByMemberId(member.getId())).isPresent();

        transactions.executeWithoutResult(status -> service.deleteConnection(member.getId()));

        org.mockito.Mockito.verify(adapter, org.mockito.Mockito.times(1)).revoke("tokens");
        assertThat(connections.findByMemberId(member.getId())).isEmpty();
    }

    @Test
    void theAccountDeletionLockRefusesToRunOutsideATransaction() {
        FamilyMember member = members.saveAndFlush(FamilyMember.builder().displayName("Louve lock").build());

        // Without one, the lock would be released at once instead of guarding the deletion.
        assertThatThrownBy(() -> service.lockMemberForAccountDeletion(member.getId()))
            .isInstanceOf(IllegalTransactionStateException.class);
        transactions.executeWithoutResult(status -> service.lockMemberForAccountDeletion(member.getId()));
    }
}
