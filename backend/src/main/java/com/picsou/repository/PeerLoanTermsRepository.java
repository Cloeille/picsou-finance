package com.picsou.repository;

import com.picsou.model.PeerLoanTerms;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PeerLoanTermsRepository extends JpaRepository<PeerLoanTerms, Long> {

    Optional<PeerLoanTerms> findByAccountId(Long accountId);

    /** Guards against linking the same provider transaction to two loans. */
    Optional<PeerLoanTerms> findByLendingTransactionId(Long lendingTransactionId);
}
