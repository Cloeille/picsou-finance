package com.picsou.service;

import com.picsou.model.FamilyMember;
import com.picsou.model.SimplefinConnection;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.SimplefinConnectionRepository;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the one property {@link SimplefinStatusWriter} exists for: the ERROR status write
 * commits in its own REQUIRES_NEW transaction, so it survives the caller's rollback.
 *
 * <p>{@code SimplefinSyncService.syncWithConnection} calls {@code markError} and then rethrows,
 * which rolls back the surrounding {@code @Transactional} sync. A Mockito test can only verify
 * that {@code markError} was <em>invoked</em> ({@code SimplefinSyncServiceTest}); this slice
 * test proves the write actually <em>persists</em> across that rollback.
 *
 * <p>Same shape as {@link IbkrStatusWriterTest}: Flyway disabled, hand-rolled H2 schema,
 * {@code NOT_SUPPORTED} so the caller/inner transaction pair is driven with a
 * {@link TransactionTemplate}.
 */
@DataJpaTest
@Import(SimplefinStatusWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/simplefin-status-writer-test-schema.sql")
class SimplefinStatusWriterTest {

    private static final String ENCRYPTED_ACCESS = "enc:ciphertext-not-a-real-url";

    @Autowired SimplefinStatusWriter statusWriter;
    @Autowired SimplefinConnectionRepository connectionRepository;
    @Autowired FamilyMemberRepository familyMemberRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void markError_commitsEvenWhenTheCallingTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long connectionId = seedConnection(tx, "Owner");

        // Caller transaction: mark ERROR through the REQUIRES_NEW bean, then fail —
        // exactly the sync shape (markError, rethrow, outer rollback).
        assertThatThrownBy(() -> tx.execute(status -> {
            statusWriter.markError(connectionId);
            throw new IllegalStateException("simulated sync failure after markError");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(connectionRepository.findById(connectionId).orElseThrow().getStatus())
            .isEqualTo("ERROR");
    }

    /** Control case: without the failure, the status is visible after a plain commit. */
    @Test
    void markError_isVisibleAfterAPlainCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long connectionId = seedConnection(tx, "Owner");

        tx.execute(status -> {
            statusWriter.markError(connectionId);
            return null;
        });

        assertThat(connectionRepository.findById(connectionId).orElseThrow().getStatus())
            .isEqualTo("ERROR");
    }

    /** The error flag must not touch the stored credential or the last successful sync time. */
    @Test
    void markError_changesOnlyTheStatus() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Instant lastSync = Instant.parse("2026-01-02T03:04:05Z");
        Long connectionId = tx.execute(status -> connectionRepository.save(SimplefinConnection.builder()
            .member(familyMemberRepository.save(FamilyMember.builder().displayName("Owner").build()))
            .accessUrl(ENCRYPTED_ACCESS)
            .lastSyncedAt(lastSync)
            .build()).getId());

        statusWriter.markError(connectionId);

        SimplefinConnection reloaded = connectionRepository.findById(connectionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("ERROR");
        assertThat(reloaded.getAccessUrl()).isEqualTo(ENCRYPTED_ACCESS);
        assertThat(reloaded.getLastSyncedAt()).isEqualTo(lastSync);
    }

    /** Scoping: marking one member's connection never flips another member's. */
    @Test
    void markError_leavesOtherMembersConnectionsUntouched() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long failing = seedConnection(tx, "Failing");
        Long healthy = seedConnection(tx, "Healthy");

        statusWriter.markError(failing);

        assertThat(connectionRepository.findById(failing).orElseThrow().getStatus()).isEqualTo("ERROR");
        assertThat(connectionRepository.findById(healthy).orElseThrow().getStatus()).isEqualTo("CONNECTED");
    }

    /** The connection was disconnected while the sync ran: logged, not thrown, nothing resurrected. */
    @Test
    void markError_onAMissingRow_isANoOp() {
        long missingId = 987_654L;

        assertThatCode(() -> statusWriter.markError(missingId)).doesNotThrowAnyException();

        assertThat(connectionRepository.findById(missingId)).isEmpty();
    }

    private Long seedConnection(TransactionTemplate tx, String memberName) {
        return tx.execute(status -> {
            FamilyMember member = familyMemberRepository.save(
                FamilyMember.builder().displayName(memberName).build());
            return connectionRepository.save(SimplefinConnection.builder()
                .member(member)
                .accessUrl(ENCRYPTED_ACCESS)
                .build()).getId();
        });
    }
}
