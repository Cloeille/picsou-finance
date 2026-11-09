package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.RateBasis;
import com.picsou.model.SavingsInterestConfig;
import com.picsou.model.SavingsProduct;
import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SavingsInterestConfigRepository#insertIfAbsent} is native SQL that the Mockito test
 * cannot execute: the enum casts and the {@code ON CONFLICT} clause only exist on PostgreSQL.
 * This pins both the insert and the conflict path, including that a losing racer leaves the
 * surrounding transaction committable (a caught unique violation would not).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@RequiresPostgres
class SavingsInterestConfigRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.registerValidatedDatasource(registry, POSTGRES);
    }

    @Autowired SavingsInterestConfigRepository repository;
    @Autowired TestEntityManager em;

    private Long accountId() {
        FamilyMember member = em.persist(FamilyMember.builder()
            .displayName("Owner")
            .avatarColor("#6366f1")
            .build());
        Account account = em.persist(Account.builder()
            .member(member)
            .name("Livret A")
            .type(AccountType.LIVRET_A)
            .currency("EUR")
            .currentBalance(new BigDecimal("1000"))
            .isManual(false)
            .build());
        em.flush();
        return account.getId();
    }

    @Test
    void insertsTheDefaultConfigurationThroughTheEnumCasts() {
        Long accountId = accountId();

        int inserted = repository.insertIfAbsent(
            accountId, SavingsProduct.LIVRET_A.name(), new BigDecimal("1.70"),
            RateBasis.NET.name(), new BigDecimal("22950.00"));

        assertThat(inserted).isEqualTo(1);
        SavingsInterestConfig saved = repository.findByAccountId(accountId).orElseThrow();
        assertThat(saved.getProduct()).isEqualTo(SavingsProduct.LIVRET_A);
        assertThat(saved.getAnnualRate()).isEqualByComparingTo("1.70");
        assertThat(saved.getRateBasis()).isEqualTo(RateBasis.NET);
        assertThat(saved.getCeiling()).isEqualByComparingTo("22950.00");
    }

    @Test
    void aConflictingInsertKeepsTheExistingRowAndLeavesTheTransactionUsable() {
        Long accountId = accountId();
        repository.insertIfAbsent(accountId, SavingsProduct.LIVRET_A.name(), new BigDecimal("1.70"),
            RateBasis.NET.name(), new BigDecimal("22950.00"));

        int inserted = repository.insertIfAbsent(
            accountId, SavingsProduct.LEP.name(), new BigDecimal("2.50"),
            RateBasis.NET.name(), new BigDecimal("10000.00"));

        assertThat(inserted).isZero();
        SavingsInterestConfig kept = repository.findByAccountId(accountId).orElseThrow();
        assertThat(kept.getProduct()).isEqualTo(SavingsProduct.LIVRET_A);
        assertThat(kept.getAnnualRate()).isEqualByComparingTo("1.70");

        // The conflict did not abort the transaction: another insert still succeeds.
        Long otherAccount = accountId();
        assertThat(repository.insertIfAbsent(
            otherAccount, SavingsProduct.LDDS.name(), new BigDecimal("1.70"),
            RateBasis.NET.name(), new BigDecimal("12000.00"))).isEqualTo(1);
    }
}
