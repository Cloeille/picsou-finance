package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.Category;
import com.picsou.model.CategoryKind;
import com.picsou.model.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlMergeMode;

import jakarta.persistence.PersistenceUnitUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/transaction-repository-test-schema.sql")
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
class TransactionRepositoryAccountFetchTest {

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    TestEntityManager testEntityManager;

    @Test
    @Sql(statements = {
        "ALTER TABLE account ADD cash_balance DECIMAL(20, 8)",
        "ALTER TABLE account ADD color VARCHAR(7)",
        "ALTER TABLE account ADD created_at TIMESTAMP",
        "ALTER TABLE account ADD currency VARCHAR(10)",
        "ALTER TABLE account ADD current_balance DECIMAL(20, 8)",
        "ALTER TABLE account ADD external_account_id VARCHAR(100)",
        "ALTER TABLE account ADD hidden BOOLEAN",
        "ALTER TABLE account ADD iban VARCHAR(34)",
        "ALTER TABLE account ADD last_synced_at TIMESTAMP",
        "ALTER TABLE account ADD logo_key VARCHAR(32)",
        "ALTER TABLE account ADD logo_url VARCHAR(255)",
        "ALTER TABLE account ADD name VARCHAR(100)",
        "ALTER TABLE account ADD opened_at DATE",
        "ALTER TABLE account ADD parent_account_id BIGINT",
        "ALTER TABLE account ADD payment_due_amount DECIMAL(20, 8)",
        "ALTER TABLE account ADD payment_due_date DATE",
        "ALTER TABLE account ADD provider VARCHAR(100)",
        "ALTER TABLE account ADD requisition_id BIGINT",
        "ALTER TABLE account ADD reward_points BIGINT",
        "ALTER TABLE account ADD ticker VARCHAR(20)",
        "ALTER TABLE account ADD type VARCHAR(100)",
        "ALTER TABLE account ADD updated_at TIMESTAMP",
        "UPDATE account SET hidden = FALSE",
        "CREATE TABLE category (id BIGINT PRIMARY KEY, member_id BIGINT NOT NULL, kind VARCHAR(20) NOT NULL)",
        "INSERT INTO category (id, member_id, kind) VALUES (10, 1, 'TRANSFER')",
        "INSERT INTO category (id, member_id, kind) VALUES (11, 1, 'EXPENSE')"
    })
    void kindAndDateQueryFetchesAccountAndPreservesFilters() {
        testEntityManager.getEntityManager().createNativeQuery("UPDATE account SET member_id = 2 WHERE id = 2")
            .executeUpdate();
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        persist(1L, 10L, LocalDate.of(2026, 6, 10), "matching");
        persist(1L, 11L, LocalDate.of(2026, 6, 11), "wrong-kind");
        persist(2L, 10L, LocalDate.of(2026, 6, 12), "wrong-member");
        persist(1L, 10L, LocalDate.of(2026, 7, 1), "outside-date");
        testEntityManager.flush();
        testEntityManager.clear();

        List<Transaction> result = transactionRepository.findByMemberIdAndKindAndDateBetween(
            1L, CategoryKind.TRANSFER, from, to);

        assertThat(result).extracting(Transaction::getDescription).containsExactly("matching");
        PersistenceUnitUtil persistenceUnitUtil = testEntityManager.getEntityManager()
            .getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistenceUnitUtil.isLoaded(result.get(0), "account")).isTrue();

        testEntityManager.clear();
        List<Transaction> dateRangeResult = transactionRepository.findByMemberIdAndDateBetween(1L, from, to);

        assertThat(dateRangeResult).extracting(Transaction::getDescription)
            .containsExactly("matching", "wrong-kind");
        assertThat(dateRangeResult).allSatisfy(transaction ->
            assertThat(persistenceUnitUtil.isLoaded(transaction, "account")).isTrue());
    }

    private void persist(long accountId, long categoryId, LocalDate date, String description) {
        Transaction transaction = Transaction.builder()
            .account(testEntityManager.getEntityManager().getReference(Account.class, accountId))
            .categoryRef(testEntityManager.getEntityManager().getReference(Category.class, categoryId))
            .date(date)
            .description(description)
            .amount(BigDecimal.ONE)
            .build();
        transactionRepository.save(transaction);
    }
}
