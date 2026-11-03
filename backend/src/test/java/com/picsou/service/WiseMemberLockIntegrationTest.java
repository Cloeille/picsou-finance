package com.picsou.service;

import com.picsou.repository.WiseConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WiseConnectionRepository#lockMember} against a real PostgreSQL: while one transaction
 * holds a member's Wise lock, another transaction for the same member waits and one for another
 * member does not. H2 has no advisory locks, and a mock cannot show that the native query maps
 * the function's {@code void} result, so this is the only place the lock is actually exercised.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIf("dockerAvailable")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WiseMemberLockIntegrationTest {

    static {
        // Same reason as AccountOwnershipReplaceIntegrationTest: docker-java negotiates down to
        // an API version modern engines refuse, which looks like "no Docker".
        System.setProperty("api.version", System.getProperty("api.version", "1.44"));
    }

    private static final long MEMBER_X = 1001L;
    private static final long MEMBER_Y = 1002L;
    private static final long WAIT_SECONDS = 10;

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.parseBoolean(System.getenv("PICSOU_REQUIRE_DOCKER_TESTS"))) {
            throw new IllegalStateException(
                "PICSOU_REQUIRE_DOCKER_TESTS is set but no Docker environment was found.");
        }
        return available;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.out-of-order", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired WiseConnectionRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void lockMember_blocksTheSameMemberUntilTheHolderCommitsAndNeverAnotherMember() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.execute(status -> {
                repository.lockMember(MEMBER_X);
                held.countDown();
                await(release);
                return null;
            }));
            assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> sameMember = pool.submit(() -> tx.execute(status -> repository.lockMember(MEMBER_X)));
            awaitWaitingAdvisoryLock();
            assertThatThrownBy(() -> sameMember.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            Future<?> otherMember = pool.submit(() -> tx.execute(status -> repository.lockMember(MEMBER_Y)));
            assertThat(otherMember.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(sameMember.isDone()).isFalse();

            release.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(sameMember.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void lockMember_outsideATransaction_isRefused() {
        assertThatThrownBy(() -> repository.lockMember(MEMBER_X))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /** Polls PostgreSQL until a session is queued behind an advisory lock, instead of sleeping. */
    private void awaitWaitingAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("no session ever waited on the advisory lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
