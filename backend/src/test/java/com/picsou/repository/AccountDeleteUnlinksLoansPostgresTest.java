package com.picsou.repository;

import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.model.FamilyMember;
import com.picsou.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting an account is a soft-delete, so the {@code ON DELETE SET NULL} of
 * {@code debt.linked_account_id} never fires. The deleted row is then hidden by
 * {@code @SQLRestriction}, and a loan still linked to it failed to load
 * ({@code EntityNotFoundException} in {@code DebtResponse.from}): the accounts page
 * answered 500 until the link was cleared by hand. The mocked unit test cannot see
 * that, so this runs the real JPQL update and read on PostgreSQL.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountDeleteUnlinksLoansPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired DebtRepository debts;
    @Autowired AccountService service;

    @Test
    void deletingThePropertyKeepsItsMortgageReadable() {
        FamilyMember member = members.save(FamilyMember.builder().displayName("Unlink").build());
        Account property = account(member, "Maison", AccountType.REAL_ESTATE);
        Account mortgage = account(member, "Prêt immobilier", AccountType.LOAN);
        debts.save(Debt.builder().member(member).account(mortgage).linkedAccount(property)
            .borrowedAmount(new BigDecimal("200000")).build());

        service.delete(property.getId(), member.getId());

        var loan = service.findById(mortgage.getId(), member.getId());
        assertThat(loan.debt()).isNotNull();
        assertThat(loan.debt().linkedAccountId()).isNull();
        assertThat(service.findAll(member.getId())).extracting("id").containsExactly(mortgage.getId());
    }

    private Account account(FamilyMember member, String name, AccountType type) {
        return accounts.save(Account.builder().name(name).type(type).currency("EUR")
            .currentBalance(BigDecimal.TEN).member(member).isManual(true).build());
    }
}
