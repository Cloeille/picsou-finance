package com.picsou.service;

import com.picsou.dto.TransactionResponse;
import com.picsou.dto.TransactionResponse.PeerLoanLink;
import com.picsou.dto.TransactionResponse.PeerLoanLinkKind;
import com.picsou.model.Transaction;
import com.picsou.repository.PeerLoanRepaymentRepository;
import com.picsou.repository.PeerLoanTermsRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Annotates transactions with the peer loan they belong to, if any: the lending outflow
 * that funded a loan, or a repayment linked from an existing row. The link is a fact
 * read from the loan tables, never guessed from labels — the transaction sheet uses it
 * to hide the link actions (they could only fail) and to point at the loan instead.
 *
 * <p>A standalone component rather than a method on a service: both the account
 * transaction list and the cross-account search need the same batch lookup, and neither
 * owning service may depend on {@link PeerLoanService} without creating a cycle
 * (it already depends on {@code AccountService}).
 */
@Component
public class PeerLoanTransactionLinks {

    private final PeerLoanTermsRepository termsRepository;
    private final PeerLoanRepaymentRepository repaymentRepository;

    public PeerLoanTransactionLinks(
        PeerLoanTermsRepository termsRepository,
        PeerLoanRepaymentRepository repaymentRepository
    ) {
        this.termsRepository = termsRepository;
        this.repaymentRepository = repaymentRepository;
    }

    /** The links for a batch of transactions, keyed by transaction id. */
    @Transactional(readOnly = true)
    public Map<Long, PeerLoanLink> linksFor(Collection<Long> transactionIds) {
        List<Long> ids = transactionIds.stream()
            .filter(id -> id != null)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, PeerLoanLink> links = new HashMap<>();
        for (var terms : termsRepository.findByLendingTransactionIdIn(ids)) {
            if (terms.getLendingTransactionId() != null && terms.getAccount() != null) {
                links.putIfAbsent(terms.getLendingTransactionId(), new PeerLoanLink(
                    PeerLoanLinkKind.LENDING, terms.getAccount().getId(), terms.getAccount().getName()));
            }
        }
        for (var repayment : repaymentRepository.findByCashTransactionIdIn(ids)) {
            if (repayment.getCashTransactionId() != null && repayment.getAccount() != null) {
                links.putIfAbsent(repayment.getCashTransactionId(), new PeerLoanLink(
                    PeerLoanLinkKind.REPAYMENT, repayment.getAccount().getId(), repayment.getAccount().getName()));
            }
        }
        return links;
    }

    /** Maps transactions to responses, carrying each row's loan link when it has one. */
    @Transactional(readOnly = true)
    public List<TransactionResponse> annotate(List<Transaction> transactions) {
        Map<Long, PeerLoanLink> links = linksFor(
            transactions.stream().map(Transaction::getId).toList());
        return transactions.stream()
            .map(transaction -> TransactionResponse.from(
                transaction, links.get(transaction.getId())))
            .toList();
    }
}
