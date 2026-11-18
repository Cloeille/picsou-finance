package com.picsou.repository;

import com.picsou.model.LouveOAuthClientRegistration;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forget step is one compare-and-delete statement. Two instances forgetting the same refused
 * client, interleaved with a new registration, must neither fail nor remove the newer row: a
 * derived delete (SELECT, then delete by id) raised an optimistic-lock failure here.
 * Real Hibernate and Spring Data on in-memory H2, no Spring context.
 */
class LouveOAuthClientRepositoryTest {

    private static final String REDIRECT = "https://picsou.example/sync/louve/callback";

    private final CountDownLatch deleting = new CountDownLatch(1);
    private final CountDownLatch replaced = new CountDownLatch(1);
    private final AtomicBoolean pauseNextDelete = new AtomicBoolean(false);
    private SessionFactory sessions;

    @BeforeEach
    void setUp() {
        Configuration cfg = new Configuration().addAnnotatedClass(LouveOAuthClientRegistration.class);
        cfg.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        cfg.setProperty("hibernate.connection.url",
            "jdbc:h2:mem:louve_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        cfg.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        cfg.getProperties().put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
            if (sql.toLowerCase().startsWith("delete from louve_oauth_client")
                    && Thread.currentThread().getName().equals("late-forget")
                    && pauseNextDelete.compareAndSet(true, false)) {
                deleting.countDown();
                try {
                    replaced.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return sql;
        });
        sessions = cfg.buildSessionFactory();
    }

    @AfterEach
    void tearDown() {
        sessions.close();
    }

    private <T> T inTransaction(Function<LouveOAuthClientRepository, T> work) {
        try (EntityManager em = sessions.createEntityManager()) {
            em.getTransaction().begin();
            T result = work.apply(new JpaRepositoryFactory(em).getRepository(LouveOAuthClientRepository.class));
            em.getTransaction().commit();
            return result;
        }
    }

    private int delete(String redirectUri, String clientId) {
        return inTransaction(repository -> repository.deleteRegistration(redirectUri, clientId));
    }

    private java.util.Optional<LouveOAuthClientRegistration> find() {
        return inTransaction(repository -> repository.findByRedirectUri(REDIRECT));
    }

    private void register(String clientId) {
        LouveOAuthClientRegistration row = LouveOAuthClientRegistration.builder()
            .redirectUri(REDIRECT).clientId(clientId).registeredAt(Instant.EPOCH).build();
        ReflectionTestUtils.setField(row, "createdAt", Instant.EPOCH);
        ReflectionTestUtils.setField(row, "updatedAt", Instant.EPOCH);
        inTransaction(repository -> repository.saveAndFlush(row));
    }

    @Test
    void aLateForgetOfTheSameClientRemovesNothingAndDoesNotFail() throws Exception {
        register("old");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> lateCount = new AtomicReference<>();
        pauseNextDelete.set(true);
        Thread late = new Thread(() -> {
            try {
                lateCount.set(delete(REDIRECT, "old"));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "late-forget");
        late.start();
        assertThat(deleting.await(10, TimeUnit.SECONDS)).as("the late delete reached the database").isTrue();

        // Meanwhile another instance forgets the same client and a member registers again.
        assertThat(delete(REDIRECT, "old")).isEqualTo(1);
        register("new");
        replaced.countDown();
        late.join(10_000);

        assertThat(failure.get()).isNull();
        assertThat(lateCount.get()).isZero();
        assertThat(find())
            .hasValueSatisfying(row -> assertThat(row.getClientId()).isEqualTo("new"));
    }

    @Test
    void onlyTheRefusedClientOfThatRedirectUriIsRemoved() {
        register("old");

        assertThat(delete(REDIRECT, "other")).isZero();
        assertThat(delete("https://elsewhere/cb", "old")).isZero();
        assertThat(delete(REDIRECT, "old")).isEqualTo(1);
        assertThat(find()).isEmpty();
    }
}
