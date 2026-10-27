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
    @Sql("classpath:sql/transaction-repository-account-fetch.sql")
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

    @Test
    @Sql(scripts = "classpath:sql/transaction-repository-account-fetch.sql",
        statements = "INSERT INTO account (id, is_manual, hidden) VALUES (4, FALSE, TRUE)")
    void budgetQueriesSkipHiddenAccounts() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        persist(1L, 11L, LocalDate.of(2026, 6, 10), "visible");
        persist(4L, 11L, LocalDate.of(2026, 6, 10), "hidden-copy");
        persist(1L, 10L, LocalDate.of(2026, 6, 11), "visible-transfer");
        persist(4L, 10L, LocalDate.of(2026, 6, 11), "hidden-transfer");
        testEntityManager.flush();
        testEntityManager.clear();

        assertThat(transactionRepository.findByMemberIdAndDateBetween(1L, from, to))
            .extracting(Transaction::getDescription).containsExactly("visible", "visible-transfer");
        assertThat(transactionRepository.searchByMember(1L, from, to, null, null))
            .extracting(Transaction::getDescription).containsExactly("visible-transfer", "visible");
        assertThat(transactionRepository.findByMemberIdAndCategoryIdAndDateBetween(1L, 11L, from, to))
            .extracting(Transaction::getDescription).containsExactly("visible");
        assertThat(transactionRepository.findByMemberIdAndCategoryIdInAndDateBetween(1L, List.of(10L, 11L), from, to))
            .extracting(Transaction::getDescription).containsExactly("visible-transfer", "visible");
        assertThat(transactionRepository.findByMemberIdAndKindAndDateBetween(1L, CategoryKind.TRANSFER, from, to))
            .extracting(Transaction::getDescription).containsExactly("visible-transfer");
        assertThat(transactionRepository.sumByCategoryIdAndDateBetween(11L, from, to))
            .isEqualByComparingTo(BigDecimal.ONE);
        assertThat(transactionRepository.sumByCategoryIdInAndDateBetween(List.of(10L, 11L), from, to))
            .isEqualByComparingTo(BigDecimal.valueOf(2));
        assertThat(transactionRepository.sumByMemberIdAndKindAndDateBetween(1L, CategoryKind.EXPENSE, from, to))
            .isEqualByComparingTo(BigDecimal.ONE);

        // Asking for the hidden account by id still lists its rows (account detail views).
        assertThat(transactionRepository.searchByMember(1L, from, to, 4L, null))
            .extracting(Transaction::getDescription).containsExactly("hidden-transfer", "hidden-copy");
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
