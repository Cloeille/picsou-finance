package com.picsou.service;

import com.picsou.config.CryptoEncryption;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.CmesSession;
import com.picsou.model.FamilyMember;
import com.picsou.port.CmesPort;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.CmesSessionRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Upgrades from the earlier plan ids against real PostgreSQL: the account an
 * old build wrote, then a fetch where plans opened or closed since.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CmesSyncService.class, CmesPlanMigrationPostgresTest.Transactions.class})
class CmesPlanMigrationPostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    private static final String PEE_ID = "cmes_12345_PEE";
    private static final String PEE_LABEL = "Plan d'épargne entreprise";
    private static final String PEE_NAME = PEE_LABEL + " — ACME";
    private static final String PEE_NEW_ID = "cmes_12345_plan-d-pargne-entreprise-1111111111111111";
    private static final String PEI_LABEL = "Plan d'épargne interentreprises";
    private static final String PEI_ID = "cmes_12345_plan-d-pargne-interentreprises-2222222222222222";

    @TestConfiguration
    static class Transactions {
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
        @Bean("cmesSyncExecutor") Executor cmesExecutor() { return Runnable::run; }
    }

    @Autowired CmesSyncService service;
    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired CmesSessionRepository sessions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CmesPort port;
    @MockitoBean CryptoEncryption encryption;
    @MockitoBean AccountService accountService;
    FamilyMember member;

    @BeforeEach
    void seed() {
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        member = members.saveAndFlush(FamilyMember.builder().displayName("CMES upgrade").build());
        sessions.saveAndFlush(CmesSession.create(member, "state", Instant.now()));
    }

    @Test
    void aPlanClosedSinceTheLastOldSyncKeepsItsHistoryFromThePlanThatNowNamesItsId() {
        Account pee = importedByAnOldBuild(PEE_ID, PEE_NAME);
        when(port.fetchPlans("state")).thenReturn(List.of(plan(PEI_ID, PEI_LABEL, "plan-d-pargne-interentreprises")));

        service.queueSync(member.getId());

        Account closed = accounts.findById(pee.getId()).orElseThrow();
        assertThat(closed.getExternalAccountId()).isEqualTo(PEE_ID);
        assertThat(closed.getName()).isEqualTo(PEE_NAME);
        assertThat(closed.getCurrentBalance()).isEqualByComparingTo("0");
        assertThat(balancesByExternalId()).containsOnlyKeys(PEE_ID, PEI_ID);
    }

    @Test
    void aPlanListedBesideOneOpenedSinceMovesToItsNewIdWithItsHistory() {
        Account pee = importedByAnOldBuild(PEE_ID, PEE_NAME);
        when(port.fetchPlans("state")).thenReturn(List.of(
            plan(PEE_NEW_ID, PEE_LABEL, "plan-d-pargne-entreprise"),
            plan(PEI_ID, PEI_LABEL, "plan-d-pargne-interentreprises")));

        service.queueSync(member.getId());

        Account moved = accounts.findById(pee.getId()).orElseThrow();
        assertThat(moved.getExternalAccountId()).isEqualTo(PEE_NEW_ID);
        assertThat(moved.getCurrentBalance()).isEqualByComparingTo("100.00");
        assertThat(balancesByExternalId()).containsOnlyKeys(PEE_NEW_ID, PEI_ID);
    }

    @Test
    void aDeletedPlanStaysDeletedWithoutHidingTheOtherPlanThatNamesItsId() {
        Account pee = importedByAnOldBuild(PEE_ID, PEE_NAME);
        jdbc.update("UPDATE account SET deleted_at = now() WHERE id = ?", pee.getId());
        when(port.fetchPlans("state")).thenReturn(List.of(
            plan(PEE_NEW_ID, PEE_LABEL, "plan-d-pargne-entreprise"),
            plan(PEI_ID, PEI_LABEL, "plan-d-pargne-interentreprises")));

        service.queueSync(member.getId());

        assertThat(balancesByExternalId()).containsOnlyKeys(PEI_ID);
    }

    private Account importedByAnOldBuild(String externalId, String name) {
        return accounts.saveAndFlush(Account.builder()
            .member(member)
            .name(name)
            .type(AccountType.EMPLOYEE_SAVINGS)
            .provider(CmesSyncService.PROVIDER)
            .externalAccountId(externalId)
            .currency("EUR")
            .currentBalance(new BigDecimal("800.00"))
            .build());
    }

    private Map<String, BigDecimal> balancesByExternalId() {
        return accounts.findAllByMemberIdAndProvider(member.getId(), CmesSyncService.PROVIDER).stream()
            .collect(Collectors.toMap(Account::getExternalAccountId, Account::getCurrentBalance));
    }

    private CmesPort.PlanData plan(String externalId, String label, String slug) {
        return new CmesPort.PlanData(
            externalId,
            List.of(PEE_ID, PEE_ID + "-" + slug),
            label,
            "PEE",
            "ACME",
            new BigDecimal("100.00"),
            List.of(new CmesPort.Position(null, "160", "FONDS", new BigDecimal("5"),
                new BigDecimal("20"), new BigDecimal("100.00"), null)),
            true
        );
    }
}
