package com.picsou.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AccountHoldingRepository#existsForReadableAccount} decides whether the wealth pyramid
 * and the diversification breakdown treat an account as a balance or split it line by line, so
 * a wrong answer here silently reshapes both.
 *
 * <p>Exercised against a real database rather than a mock because the risk is entirely in the
 * JPQL: the co-ownership branch is the half a naive {@code account.member.id = :memberId}
 * predicate gets wrong, and a mocked repository would assert nothing about it.
 *
 * <p>Flyway is disabled and the schema hand-rolled, as in {@link TransactionRepositoryTest} —
 * the real migrations are PostgreSQL-flavoured and cannot run on H2.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql("classpath:sql/account-holding-repository-test-schema.sql")
class AccountHoldingRepositoryTest {

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;
    private static final Long STRANGER = 99L;

    @Autowired
    AccountHoldingRepository repository;

    @Test
    void findsALineOnAnAccountTheMemberOwns() {
        assertThat(repository.existsForReadableAccount(1L, OWNER)).isTrue();
    }

    @Test
    void findsALineOnAJointAccountOwnedBySomeoneElse() {
        // Account 2 belongs to member 2; member 1 reaches it through account_ownership. This is
        // the case that breaks under an owning-member-only predicate — a household's shared
        // brokerage account would report "no holdings" and be tiered as a flat balance.
        assertThat(repository.existsForReadableAccount(2L, OWNER)).isTrue();
    }

    @Test
    void saysNoForAnAccountTheMemberCannotRead() {
        // Member 2 owns account 2 but has no claim on account 1, and nobody has co-owned it.
        assertThat(repository.existsForReadableAccount(1L, OTHER)).isFalse();
        assertThat(repository.existsForReadableAccount(1L, STRANGER)).isFalse();
    }

    @Test
    void saysNoForAnOwnedAccountThatSimplyHoldsNothing() {
        // Account 3 is member 1's and carries no line: readable, but empty. Distinguishing this
        // from "not yours" is what the balance-only branch depends on.
        assertThat(repository.existsForReadableAccount(3L, OWNER)).isFalse();
    }
}
