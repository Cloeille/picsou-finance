package com.picsou.service;

import com.picsou.model.SetupAudit;
import com.picsou.repository.SetupAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** A failed audit insert must not roll back the transaction of the operation it records. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SetupAuditServicePostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.sidecar.api-key", () -> "synthetic-sidecar-test-key-not-a-credential");
    }

    @Autowired SetupAuditService audit;
    @Autowired SetupAuditRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void aFailedAuditInsertLeavesTheCallersTransactionCommittable() {
        long before = repository.count();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.save(SetupAudit.builder().event("synthetic.caller.write").at(Instant.now()).build());
            // setup_audit.event is NOT NULL: this insert fails.
            audit.record(null, "synthetic-user", null, null);
        });

        assertThat(repository.count()).isEqualTo(before + 1);
    }
}
