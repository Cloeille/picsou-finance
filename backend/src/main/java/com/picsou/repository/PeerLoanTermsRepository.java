package com.picsou.repository;

import com.picsou.model.PeerLoanTerms;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PeerLoanTermsRepository extends JpaRepository<PeerLoanTerms, Long> {

    Optional<PeerLoanTerms> findByAccountId(Long accountId);

    /**
     * Every loan of a member, for the borrower history. Scoped through the account's
     * member because the terms row itself carries no member column — the account is the
     * ownership boundary, as it is for transactions and goal contributions.
     */
    List<PeerLoanTerms> findAllByAccountMemberId(Long memberId);
}
