package com.picsou.service;

import com.picsou.dto.TransactionResponse.PeerLoanLink;
import com.picsou.dto.TransactionResponse.PeerLoanLinkKind;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.PeerCounterparty;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanTerms;
import com.picsou.model.Transaction;
import com.picsou.support.PostgresContainers;
import com.picsou.support.RequiresPostgres;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PeerLoanTransactionLinks} against a real persistence context.
 *
 * <p><b>Why this exists.</b> {@code PeerLoanTransactionLinksTest} mocks both repositories, so
 * every loan account it hands back is a plain object. In production the loans come out of a query
 * with a lazy {@code account} proxy, and {@code Account} carries a soft-delete restriction: the
 * proxy of a deleted loan cannot load and throws {@code EntityNotFoundException}, which turned
 * every transaction list of the cash account into a 500. Only a real ORM against the real schema
 * shows that, and the statement count shows the batch is not an N+1.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@RequiresPostgres
@Import(PeerLoanTransactionLinks.class)
class PeerLoanTransactionLinksPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.registerValidatedDatasource(registry, POSTGRES);
    }

    @Autowired PeerLoanTransactionLinks links;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired TestEntityManager em;

    private FamilyMember member;
    private Account cash;
    private int sequence;

    /** A loan with its lending row and one repayment row in the cash account; both ids returned. */
    private record Seeded(Long lendingTransactionId, Long repaymentTransactionId, Long loanAccountId) {}

    private void seedMemberAndCash() {
        member = em.persist(FamilyMember.builder().displayName("Alice").build());
        cash = em.persist(Account.builder()
            .member(member).name("Compte courant").type(AccountType.CHECKING)
            .currency("EUR").currentBalance(new BigDecimal("2000")).build());
    }

    private Transaction cashTransaction(String description, String amount) {
        return em.persist(Transaction.builder()
            .account(cash).date(LocalDate.parse("2026-02-01")).description(description)
            .amount(new BigDecimal(amount)).build());
    }

    private Seeded loan(String borrower, boolean softDeleted) {
        sequence++;
        Account account = em.persist(Account.builder()
            .member(member).name(borrower).type(AccountType.PEER_LOAN)
            .currency("EUR").currentBalance(new BigDecimal("1000"))
            .deletedAt(softDeleted ? Instant.parse("2026-03-01T10:00:00Z") : null).build());
        PeerCounterparty counterparty = em.persist(
            PeerCounterparty.builder().member(member).name(borrower).build());
        Transaction lending = cashTransaction("Prêt " + sequence, "-1000");
        Transaction repayment = cashTransaction("Remboursement " + sequence, "500");
        em.persist(PeerLoanTerms.builder()
            .account(account).counterparty(counterparty)
            .principal(new BigDecimal("1000")).lentAt(LocalDate.parse("2026-01-01"))
            .linkedAccount(cash).lendingTransactionId(lending.getId()).build());
        em.persist(PeerLoanRepayment.builder()
            .account(account).paidOn(LocalDate.parse("2026-02-01"))
            .amount(new BigDecimal("500")).cashTransactionId(repayment.getId()).build());
        return new Seeded(lending.getId(), repayment.getId(), account.getId());
    }

    private void commitToTheDatabase() {
        em.flush();
        em.clear();
    }

    @Test
    void aSoftDeletedLoanDropsOutOfTheLinksInsteadOfFailingTheList() {
        seedMemberAndCash();
        Seeded live = loan("Jean", false);
        Seeded deleted = loan("Marie", true);
        commitToTheDatabase();

        Map<Long, PeerLoanLink> out = links.linksFor(List.of(
            live.lendingTransactionId(), live.repaymentTransactionId(),
            deleted.lendingTransactionId(), deleted.repaymentTransactionId()));

        assertThat(out).containsOnlyKeys(live.lendingTransactionId(), live.repaymentTransactionId());
        assertThat(out.get(live.lendingTransactionId()))
            .isEqualTo(new PeerLoanLink(PeerLoanLinkKind.LENDING, live.loanAccountId(), "Jean"));
        assertThat(out.get(live.repaymentTransactionId()))
            .isEqualTo(new PeerLoanLink(PeerLoanLinkKind.REPAYMENT, live.loanAccountId(), "Jean"));
    }

    @Test
    void theBatchIsTwoStatementsHoweverManyLoansItCovers() {
        seedMemberAndCash();
        List<Seeded> loans = List.of(loan("Jean", false), loan("Marie", false), loan("Paul", false));
        commitToTheDatabase();
        List<Long> ids = loans.stream()
            .flatMap(seeded -> Stream.of(
                seeded.lendingTransactionId(), seeded.repaymentTransactionId()))
            .toList();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Map<Long, PeerLoanLink> out = links.linksFor(ids);

        assertThat(out).hasSize(6);
        // One query per table, the account fetched by the same statement: a lazy account
        // would add one statement per loan the moment its name is read.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }
}
