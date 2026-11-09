package com.picsou.repository;

import com.picsou.model.PeerLoanRepayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PeerLoanRepaymentRepository extends JpaRepository<PeerLoanRepayment, Long> {

    List<PeerLoanRepayment> findByAccountIdOrderByPaidOnAscIdAsc(Long accountId);

    Optional<PeerLoanRepayment> findByIdAndAccountId(Long id, Long accountId);

    /** Guards against assigning the same incoming transaction to two repayments. */
    Optional<PeerLoanRepayment> findByCashTransactionId(Long cashTransactionId);

    /** Batch version for annotating transaction lists with their loan link. */
    @Query("select r from PeerLoanRepayment r join fetch r.account where r.cashTransactionId in :ids")
    List<PeerLoanRepayment> findByCashTransactionIdIn(@Param("ids") Collection<Long> cashTransactionIds);

    List<PeerLoanRepayment> findAllByAccountIdIn(Collection<Long> accountIds);

    /** The GDPR export needs the whole journal of one member in date order. */
    List<PeerLoanRepayment> findAllByAccountMemberIdOrderByPaidOnAscIdAsc(Long memberId);
}
