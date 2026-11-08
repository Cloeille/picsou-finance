package com.picsou.service;

import com.picsou.dto.TransactionResponse.PeerLoanLink;
import com.picsou.model.Account;
import com.picsou.model.AccountType;
import com.picsou.model.FamilyMember;
import com.picsou.model.PeerCounterparty;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanRepaymentKind;
import com.picsou.model.PeerLoanTerms;
import com.picsou.model.Transaction;
import com.picsou.repository.AccountRepository;
import com.picsou.repository.FamilyMemberRepository;
import com.picsou.repository.PeerCounterpartyRepository;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import com.picsou.repository.TransactionRepository;
import com.picsou.support.PostgresContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The link lookup runs against real rows: a soft-deleted loan account must be skipped,
 * not blow up the transaction lists with an EntityNotFoundException when its lazy
 * account proxy is resolved.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(PeerLoanTransactionLinks.class)
class PeerLoanTransactionLinksPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @Autowired PeerLoanTransactionLinks links;
    @Autowired FamilyMemberRepository members;
    @Autowired AccountRepository accounts;
    @Autowired PeerCounterpartyRepository counterparties;
    @Autowired PeerLoanTermsRepository terms;
    @Autowired PeerLoanRepaymentRepository repayments;
    @Autowired TransactionRepository transactions;

    @Test
    void skipsSoftDeletedLoansInsteadOfFailing() {
        FamilyMember member = members.saveAndFlush(FamilyMember.builder().displayName("Liens").build());
        Account cash = accounts.saveAndFlush(Account.builder().member(member).name("Courant")
            .type(AccountType.CHECKING).currency("EUR").currentBalance(BigDecimal.ZERO).build());
        Account loan = accounts.saveAndFlush(Account.builder().member(member).name("Jean")
            .type(AccountType.PEER_LOAN).currency("EUR").isManual(true)
            .currentBalance(BigDecimal.ZERO).build());
        PeerCounterparty counterparty = counterparties.saveAndFlush(PeerCounterparty.builder()
            .member(member).name("Jean").build());
        // Real rows: lending_transaction_id is a foreign key to the transaction table.
        Transaction lending = transactions.saveAndFlush(Transaction.builder()
            .account(cash).date(LocalDate.parse("2026-01-01")).description("Prêt — Jean")
            .amount(new BigDecimal("-500")).build());
        Transaction cashIn = transactions.saveAndFlush(Transaction.builder()
            .account(cash).date(LocalDate.parse("2026-02-01")).description("Remboursement — Jean")
            .amount(new BigDecimal("100")).build());
        terms.saveAndFlush(PeerLoanTerms.builder()
            .account(loan).counterparty(counterparty)
            .principal(new BigDecimal("500")).lentAt(LocalDate.parse("2026-01-01"))
            .annualRate(BigDecimal.ZERO).linkedAccount(cash).lendingTransactionId(lending.getId())
            .build());
        repayments.saveAndFlush(PeerLoanRepayment.builder()
            .account(loan).paidOn(LocalDate.parse("2026-02-01")).amount(new BigDecimal("100"))
            .kind(PeerLoanRepaymentKind.PRINCIPAL).cashTransactionId(cashIn.getId()).cashLegOwned(false)
            .build());

        Map<Long, PeerLoanLink> before = links.linksFor(List.of(lending.getId(), cashIn.getId(), 70L));
        assertThat(before.get(lending.getId())).isEqualTo(new PeerLoanLink("LENDING", loan.getId(), "Jean"));
        assertThat(before.get(cashIn.getId())).isEqualTo(new PeerLoanLink("REPAYMENT", loan.getId(), "Jean"));
        assertThat(before).doesNotContainKey(70L);

        // Deleting a loan is a soft-delete: the ledger rows and the cash movements stay.
        loan.setDeletedAt(Instant.now());
        accounts.saveAndFlush(loan);

        assertThat(links.linksFor(List.of(50L, 60L))).isEmpty();
    }
}
