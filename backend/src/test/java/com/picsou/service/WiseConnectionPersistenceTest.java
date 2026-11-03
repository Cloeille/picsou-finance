package com.picsou.service;

import com.picsou.model.FamilyMember;
import com.picsou.model.WiseConnection;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.WiseConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Mockito tests cannot show: how the {@code wise_connection} row behaves across real
 * transactions. A sync's status write must leave a token stored meanwhile by a concurrent
 * connect alone, and {@link WiseStatusWriter} must outlive the sync's rollback.
 */
@DataJpaTest
@Import(WiseStatusWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/wise-connection-test-schema.sql")
class WiseConnectionPersistenceTest {

    private static final String TOKEN_A = "enc:token-a";
    private static final String TOKEN_B = "enc:token-b";

    @Autowired WiseStatusWriter statusWriter;
    @Autowired WiseConnectionRepository connectionRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void syncStatusWrite_keepsATokenAConcurrentConnectStoredMeanwhile() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long connectionId = seedConnection(tx);

        tx.execute(sync -> {
            WiseConnection loadedBySync = connectionRepository.findById(connectionId).orElseThrow();
            concurrent.execute(connect -> {
                WiseConnection replaced = connectionRepository.findById(connectionId).orElseThrow();
                replaced.setApiToken(TOKEN_B);
                return connectionRepository.save(replaced);
            });
            loadedBySync.setStatus("CONNECTED");
            loadedBySync.setLastSyncedAt(Instant.parse("2026-11-03T08:00:00Z"));
            return connectionRepository.save(loadedBySync);
        });

        assertThat(connectionRepository.findById(connectionId).orElseThrow().getApiToken()).isEqualTo(TOKEN_B);
    }

    @Test
    void markError_commitsEvenWhenTheSyncRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long connectionId = seedConnection(tx);

        assertThatThrownBy(() -> tx.execute(sync -> {
            statusWriter.markError(connectionId);
            throw new IllegalStateException("simulated sync failure after markError");
        })).isInstanceOf(IllegalStateException.class);

        WiseConnection reloaded = connectionRepository.findById(connectionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("ERROR");
        assertThat(reloaded.getApiToken()).isEqualTo(TOKEN_A);
    }

    @Test
    void markReauthRequired_commitsEvenWhenTheSyncRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long connectionId = seedConnection(tx);

        assertThatThrownBy(() -> tx.execute(sync -> {
            statusWriter.markReauthRequired(connectionId);
            throw new IllegalStateException("simulated refused token");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(connectionRepository.findById(connectionId).orElseThrow().getStatus()).isEqualTo("REAUTH_REQUIRED");
    }

    private Long seedConnection(TransactionTemplate tx) {
        return tx.execute(status -> {
            FamilyMember member = familyMemberRepository.save(FamilyMember.builder().displayName("Owner").build());
            return connectionRepository.save(WiseConnection.builder().member(member).apiToken(TOKEN_A).build()).getId();
        });
    }
}
