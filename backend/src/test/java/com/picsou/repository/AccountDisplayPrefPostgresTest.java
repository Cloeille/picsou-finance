package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The display-pref upsert is native SQL, so the mocked {@code AccountServiceTest} never runs it.
 * This exercises it on the real migrations: each endpoint writes only its own column, and
 * concurrent first writes on one (member, account) merge instead of hitting the unique key.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountDisplayPrefPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired AccountDisplayPrefRepository prefs;
    @Autowired AccountService service;

    @Test
    void collapseAndOrderEachKeepTheOtherColumn() {
        FamilyMember member = members.save(FamilyMember.builder().displayName("Display prefs").build());
        Account a = account(member, "A");
        Account b = account(member, "B");

        service.setListCollapsed(a.getId(), member.getId(), true);
        service.setDisplayOrder(member.getId(), List.of(b.getId(), a.getId()));
        assertThat(service.findById(a.getId(), member.getId()).listCollapsed()).isTrue();
        assertThat(service.findById(a.getId(), member.getId()).listPosition()).isEqualTo(1);

        var expanded = service.setListCollapsed(a.getId(), member.getId(), false);
        assertThat(expanded.listPosition()).isEqualTo(1);
        assertThat(service.findById(a.getId(), member.getId()).listPosition()).isEqualTo(1);
        assertThat(service.findById(b.getId(), member.getId()).listPosition()).isEqualTo(0);

        service.setDisplayOrder(member.getId(), List.of());
        assertThat(service.findById(b.getId(), member.getId()).listPosition()).isNull();
        assertThat(prefs.findByMemberId(member.getId())).hasSize(2);
    }

    @Test
    void concurrentFirstWritesOnOneAccountMergeIntoOneRow() throws Exception {
        FamilyMember member = members.save(FamilyMember.builder().displayName("Race").build());
        Account a = account(member, "Race");
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                Callable<Object> write = i % 2 == 0
                    ? () -> { start.await(); return service.setListCollapsed(a.getId(), member.getId(), true); }
                    : () -> { start.await(); return service.setDisplayOrder(member.getId(), List.of(a.getId())); };
                results.add(pool.submit(write));
            }
            start.countDown();
            for (Future<?> result : results) result.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(prefs.findByMemberId(member.getId())).hasSize(1);
        var stored = service.findById(a.getId(), member.getId());
        assertThat(stored.listCollapsed()).isTrue();
        assertThat(stored.listPosition()).isEqualTo(0);
    }

    private Account account(FamilyMember member, String name) {
        return accounts.save(Account.builder().name(name).type(AccountType.CHECKING).currency("EUR")
            .currentBalance(BigDecimal.TEN).member(member).isManual(true).build());
    }
}
