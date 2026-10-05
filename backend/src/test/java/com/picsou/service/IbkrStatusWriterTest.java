package com.picsou.service;

import com.picsou.model.FamilyMember;
import com.picsou.model.IbkrConnection;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.IbkrConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the one property {@link IbkrStatusWriter} exists for: the ERROR status write
 * commits in its own REQUIRES_NEW transaction, so it survives the caller's rollback.
 *
 * <p>{@code IbkrSyncService.syncWithConnection} calls {@code markError} and then rethrows,
 * which marks the surrounding {@code @Transactional} sync rollback-only on the manual path.
 * A Mockito test can only verify that {@code markError} was <em>invoked</em>
 * ({@code IbkrSyncServiceTest}); this slice test proves the write actually <em>persists</em>
 * across that rollback — the exact behavior a naïve in-transaction save gets wrong.
 *
 * <p>Flyway is disabled and the schema hand-rolled (H2 cannot run the PostgreSQL-flavoured
 * migrations — docs/conventions/testing.md). {@code @Transactional(NOT_SUPPORTED)} switches
 * off @DataJpaTest's test-wrapping transaction so the caller/inner transaction pair is
 * controlled explicitly with a {@link TransactionTemplate}, like production does via AOP.
 */
@DataJpaTest
@Import(IbkrStatusWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/ibkr-status-writer-test-schema.sql")
class IbkrStatusWriterTest {

    @Autowired IbkrStatusWriter statusWriter;
    @Autowired IbkrConnectionRepository connectionRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void markError_commitsEvenWhenTheCallingTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long connectionId = tx.execute(status -> {
            FamilyMember member = familyMemberRepository.save(
                FamilyMember.builder().displayName("Owner").build());
            return connectionRepository.save(IbkrConnection.builder()
                .member(member)
                .token("enc-token")
                .queryId("enc-query")
                .build()).getId();
        });
        assertThat(connectionId).isNotNull();

        // Caller transaction: mark ERROR through the REQUIRES_NEW bean, then fail —
        // exactly the manual-sync shape (markError, rethrow, outer rollback).
        assertThatThrownBy(() -> tx.execute(status -> {
            statusWriter.markError(connectionId);
            throw new IllegalStateException("simulated sync failure after markError");
        })).isInstanceOf(IllegalStateException.class);

        // The outer transaction rolled back; the REQUIRES_NEW write must have survived.
        assertThat(connectionRepository.findById(connectionId).orElseThrow().getStatus())
            .isEqualTo("ERROR");
    }

    /** Control case: without the failure, the caller's own writes commit normally. */
    @Test
    void markError_isVisibleAfterAPlainCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long connectionId = tx.execute(status -> {
            FamilyMember member = familyMemberRepository.save(
                FamilyMember.builder().displayName("Owner").build());
            return connectionRepository.save(IbkrConnection.builder()
                .member(member)
                .token("enc-token")
                .queryId("enc-query")
                .build()).getId();
        });

        tx.execute(status -> {
            statusWriter.markError(connectionId);
            return null;
        });

        assertThat(connectionRepository.findById(connectionId).orElseThrow().getStatus())
            .isEqualTo("ERROR");
    }
}
