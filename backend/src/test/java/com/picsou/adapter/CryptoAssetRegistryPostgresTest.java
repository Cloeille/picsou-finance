package com.picsou.adapter;

import com.picsou.model.CryptoAsset;
import com.picsou.repository.CryptoAssetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CryptoAssetRegistry#learn} is reached from the exchange sync's transaction through
 * PriceService and must commit on its own ({@code REQUIRES_NEW}), whatever the caller holds. A
 * read-only caller transaction is the sharpest probe of that: joined, PostgreSQL rejects the
 * INSERT with SQLSTATE 25006. H2 tolerates writes in read-only transactions, so only a real
 * PostgreSQL shows it. As in {@code BudgetSeedWriteOnReadPostgresTest}, there is deliberately no
 * class-level {@code @Transactional}: it would collapse the boundary under test.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CryptoAssetRegistryPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static final String LEARNED = "ZZTEST";

    @Autowired CryptoAssetRegistry registry;
    @Autowired CryptoAssetRepository repository;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void cleanUp() {
        repository.findById(LEARNED).ifPresent(repository::delete);
    }

    @Test
    void learnWritesFromWithinAReadOnlyTransaction() {
        newReadOnlyTemplate().executeWithoutResult(status ->
            registry.learn(LEARNED, "zz-test", "ZZ Test"));

        CryptoAsset row = repository.findById(LEARNED).orElseThrow();
        assertThat(row.getSource()).isEqualTo(CryptoAsset.Source.AUTO);
        assertThat(row.getCoingeckoId()).isEqualTo("zz-test");
        assertThat(registry.find(LEARNED)).contains(new CryptoAssetRegistry.Coin("zz-test", "ZZ Test"));
    }

    @Test
    void learningASeededTickerKeepsTheCuratedRow() {
        newReadOnlyTemplate().executeWithoutResult(status ->
            registry.learn("BTC", "some-other-id", "X"));

        CryptoAsset btc = repository.findById("BTC").orElseThrow();
        assertThat(btc.getCoingeckoId()).isEqualTo("bitcoin");
        assertThat(btc.getSource()).isEqualTo(CryptoAsset.Source.CURATED);
    }

    @Test
    void migrationSeedsTheCuratedRegistry() {
        assertThat(repository.count()).isGreaterThanOrEqualTo(50);
        assertThat(repository.findById("NEXO")).get()
            .extracting(CryptoAsset::getCoingeckoId).isEqualTo("nexo");
        assertThat(repository.findById("CRO")).get()
            .extracting(CryptoAsset::getCoingeckoId).isEqualTo("crypto-com-chain");
    }

    private TransactionTemplate newReadOnlyTemplate() {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setReadOnly(true);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }
}
