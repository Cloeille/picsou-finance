package com.picsou.repository;

import com.picsou.model.PeerLoanRepayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PeerLoanRepaymentRepository extends JpaRepository<PeerLoanRepayment, Long> {

    List<PeerLoanRepayment> findByAccountIdOrderByPaidOnAscIdAsc(Long accountId);

    Optional<PeerLoanRepayment> findByIdAndAccountId(Long id, Long accountId);

    List<PeerLoanRepayment> findAllByAccountIdIn(Collection<Long> accountIds);
}
