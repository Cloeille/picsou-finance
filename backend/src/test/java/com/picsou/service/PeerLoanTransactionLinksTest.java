package com.picsou.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picsou.dto.TransactionResponse;
import com.picsou.dto.TransactionResponse.PeerLoanLink;
import com.picsou.dto.TransactionResponse.PeerLoanLinkKind;
import com.picsou.model.Account;
import com.picsou.model.PeerLoanRepayment;
import com.picsou.model.PeerLoanTerms;
import com.picsou.model.Transaction;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PeerLoanTransactionLinksTest {

    @Mock PeerLoanTermsRepository termsRepository;
    @Mock PeerLoanRepaymentRepository repaymentRepository;

    @InjectMocks PeerLoanTransactionLinks links;

    private static Account loanAccount() {
        return Account.builder().id(20L).name("Jean").build();
    }

    @Test
    void annotatesLendingAndRepaymentRows() {
        when(termsRepository.findByLendingTransactionIdIn(List.of(50L, 60L, 70L))).thenReturn(List.of(
            PeerLoanTerms.builder().account(loanAccount()).lendingTransactionId(50L).build()));
        when(repaymentRepository.findByCashTransactionIdIn(List.of(50L, 60L, 70L))).thenReturn(List.of(
            PeerLoanRepayment.builder().account(loanAccount()).cashTransactionId(60L).build()));

        Map<Long, PeerLoanLink> out = links.linksFor(List.of(50L, 60L, 70L));

        assertThat(out.get(50L)).isEqualTo(new PeerLoanLink(PeerLoanLinkKind.LENDING, 20L, "Jean"));
        assertThat(out.get(60L)).isEqualTo(new PeerLoanLink(PeerLoanLinkKind.REPAYMENT, 20L, "Jean"));
        assertThat(out).doesNotContainKey(70L);
    }

    @Test
    void annotateCarriesEachRowItsLink() {
        Account cash = Account.builder().id(4L).name("Compte courant").build();
        Transaction plain = Transaction.builder()
            .id(70L).account(cash).date(LocalDate.parse("2026-02-01")).amount(new BigDecimal("10")).build();
        Transaction leg = Transaction.builder()
            .id(60L).account(cash).date(LocalDate.parse("2026-02-01")).amount(new BigDecimal("100")).build();
        when(termsRepository.findByLendingTransactionIdIn(List.of(70L, 60L))).thenReturn(List.of());
        when(repaymentRepository.findByCashTransactionIdIn(List.of(70L, 60L))).thenReturn(List.of(
            PeerLoanRepayment.builder().account(loanAccount()).cashTransactionId(60L).build()));

        List<TransactionResponse> out = links.annotate(List.of(plain, leg));

        assertThat(out.get(0).peerLoanLink()).isNull();
        assertThat(out.get(1).peerLoanLink()).isEqualTo(new PeerLoanLink(PeerLoanLinkKind.REPAYMENT, 20L, "Jean"));
    }

    @Test
    void theLinkKindSerializesAsItsName() throws Exception {
        String json = new ObjectMapper()
            .writeValueAsString(new PeerLoanLink(PeerLoanLinkKind.REPAYMENT, 20L, "Jean"));

        assertThat(json).isEqualTo("{\"kind\":\"REPAYMENT\",\"accountId\":20,\"accountName\":\"Jean\"}");
    }

    @Test
    void emptyInputSkipsRepositories() {
        assertThat(links.linksFor(List.of())).isEmpty();
        verifyNoInteractions(termsRepository, repaymentRepository);
    }
}
