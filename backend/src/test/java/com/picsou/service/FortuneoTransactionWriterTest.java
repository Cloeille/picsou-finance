package com.picsou.service;

import com.picsou.model.Account;
import com.picsou.model.Transaction;
import com.picsou.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves that Fortuneo transaction replacement is atomic and idempotent. */
@DataJpaTest
@Import(FortuneoTransactionWriter.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/transaction-repository-test-schema.sql")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FortuneoTransactionWriterTest {

    @Autowired FortuneoTransactionWriter transactionWriter;
    @Autowired TransactionRepository transactionRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    private static final Long ACCOUNT_ID = 1L;
    private LocalDate cutoff;

    @BeforeEach
    void seedHistory() {
        cutoff = LocalDate.of(2026, 6, 1);
        inTransaction(() -> transactionRepository.saveAndFlush(Transaction.builder()
            .account(accountReference())
            .date(cutoff.plusDays(1))
            .description("previous synchronized row")
            .amount(new BigDecimal("10"))
            .nativeCurrency("EUR")
            .isManual(false)
            .build()));
    }

    @Test
    void insertionFailureAfterDeletion_rollsBackTheCompleteReplacement() {
        assertThatThrownBy(() -> inTransaction(() ->
            transactionWriter.replaceRecentTransactions(
                ACCOUNT_ID,
                cutoff,
                List.of(numericOverflow("invalid replacement", null))
            )
        )).isInstanceOf(RuntimeException.class);

        assertPreviousRowWasPreserved();
    }

    @Test
    void reconciliationFailure_rollsBackDeletesAndInsertsTogether() {
        assertThatThrownBy(() -> inTransaction(() -> {
            Transaction obsolete = transactionRepository
                .findByAccountIdOrderByDateDesc(ACCOUNT_ID)
                .getFirst();
            transactionWriter.reconcileHistory(
                List.of(obsolete),
                List.of(numericOverflow("invalid replacement", "tx-1"))
            );
        })).isInstanceOf(RuntimeException.class);

        assertPreviousRowWasPreserved();
    }

    @Test
    void reconciliation_replacesTheWindowEraRowWithItsIdentifiedCounterpart() {
        inTransaction(() -> {
            Transaction windowEraRow = transactionRepository
                .findByAccountIdOrderByDateDesc(ACCOUNT_ID)
                .getFirst();
            Transaction identified = Transaction.builder()
                .account(accountReference())
                .externalId("tx-1")
                .date(windowEraRow.getDate())
                .description("same row, now identified")
                .amount(new BigDecimal("10"))
                .nativeCurrency("EUR")
                .isManual(false)
                .build();

            transactionWriter.reconcileHistory(List.of(windowEraRow), List.of(identified));
        });

        assertThat(transactionRepository.findByAccountIdOrderByDateDesc(ACCOUNT_ID))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.getExternalId()).isEqualTo("tx-1");
                assertThat(row.getDescription()).isEqualTo("same row, now identified");
            });
    }

    @Test
    void theSameExternalIdCannotBeStoredTwiceOnOneAccount() {
        assertThatThrownBy(() -> inTransaction(() ->
            transactionWriter.reconcileHistory(List.of(), List.of(
                duplicateOf("tx-dup", "first copy"),
                duplicateOf("tx-dup", "second copy")
            ))
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rowsWithoutExternalIdsRemainUnconstrained() {
        inTransaction(() -> transactionWriter.reconcileHistory(List.of(), List.of(
            duplicateOf(null, "first unidentified row"),
            duplicateOf(null, "second unidentified row")
        )));

        assertThat(transactionRepository.findByAccountIdOrderByDateDesc(ACCOUNT_ID))
            .extracting(Transaction::getDescription)
            .containsExactlyInAnyOrder(
                "previous synchronized row",
                "first unidentified row",
                "second unidentified row"
            );
    }

    private Account accountReference() {
        return entityManager.getReference(Account.class, ACCOUNT_ID);
    }

    private Transaction numericOverflow(String description, String externalId) {
        return Transaction.builder()
            .account(accountReference())
            .externalId(externalId)
            .date(cutoff.plusDays(2))
            .description(description)
            .amount(new BigDecimal("123456789012345678901234567890"))
            .nativeCurrency("EUR")
            .isManual(false)
            .build();
    }

    private Transaction duplicateOf(String externalId, String description) {
        return Transaction.builder()
            .account(accountReference())
            .externalId(externalId)
            .date(cutoff.plusDays(3))
            .description(description)
            .amount(BigDecimal.ONE)
            .nativeCurrency("EUR")
            .isManual(false)
            .build();
    }

    private void assertPreviousRowWasPreserved() {
        assertThat(transactionRepository.findByAccountIdOrderByDateDesc(ACCOUNT_ID))
            .singleElement()
            .satisfies(previous -> {
                assertThat(previous.getDescription()).isEqualTo("previous synchronized row");
                assertThat(previous.getAmount()).isEqualByComparingTo("10");
            });
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }
}
