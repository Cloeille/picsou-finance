package com.picsou.repository;

import com.picsou.model.PeerLoanTerms;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PeerLoanTermsRepository extends JpaRepository<PeerLoanTerms, Long> {

    Optional<PeerLoanTerms> findByAccountId(Long accountId);

    /**
     * The same row, locked until the transaction ends. Every method that writes a loan's ledger
     * starts here, so two concurrent writers serialize per loan and the second one's cap check
     * sees the first one's row instead of both passing on the same snapshot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PeerLoanTerms t where t.account.id = :accountId")
    Optional<PeerLoanTerms> findForUpdateByAccountId(@Param("accountId") Long accountId);

    /** Guards against linking the same provider transaction to two loans. */
    Optional<PeerLoanTerms> findByLendingTransactionId(Long lendingTransactionId);

    /**
     * Batch version for annotating transaction lists with their loan link. The fetch join
     * loads the account in the same statement: without it, resolving the borrower name
     * would issue one select per loan, and a soft-deleted loan account would fail the
     * read with EntityNotFoundException instead of being skipped.
     */
    @Query("select t from PeerLoanTerms t join fetch t.account where t.lendingTransactionId in :ids")
    List<PeerLoanTerms> findByLendingTransactionIdIn(@Param("ids") Collection<Long> lendingTransactionIds);

    /** The GDPR export needs every loan of one member, not one account at a time. */
    List<PeerLoanTerms> findAllByAccountMemberIdOrderByLentAtAscIdAsc(Long memberId);
}
