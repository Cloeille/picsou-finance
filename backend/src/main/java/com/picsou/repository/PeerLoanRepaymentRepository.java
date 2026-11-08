package com.picsou.repository;

import com.picsou.model.PeerLoanRepayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PeerLoanRepaymentRepository extends JpaRepository<PeerLoanRepayment, Long> {

    List<PeerLoanRepayment> findByAccountIdOrderByPaidOnAscIdAsc(Long accountId);

    Optional<PeerLoanRepayment> findByIdAndAccountId(Long id, Long accountId);

    /** Guards against assigning the same incoming transaction to two repayments. */
    Optional<PeerLoanRepayment> findByCashTransactionId(Long cashTransactionId);

    List<PeerLoanRepayment> findAllByAccountIdIn(Collection<Long> accountIds);

    /** The GDPR export needs the whole journal of one member in date order. */
    List<PeerLoanRepayment> findAllByAccountMemberIdOrderByPaidOnAscIdAsc(Long memberId);
}
