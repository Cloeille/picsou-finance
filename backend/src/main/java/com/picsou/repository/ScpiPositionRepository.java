package com.picsou.repository;

import com.picsou.model.ScpiPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScpiPositionRepository extends JpaRepository<ScpiPosition, Long> {
    /**
     * The member id is the account owner's, not the viewer's. A co-owner reads the
     * owner's row; filtering by the viewer would hide a share they are allowed to see.
     */
    Optional<ScpiPosition> findByAccountIdAndMemberId(Long accountId, Long memberId);

    /**
     * The fund a CORUM sync writes to. A holding with no match is skipped, so a
     * manually entered account that was never linked stays untouched.
     */
    Optional<ScpiPosition> findByMemberIdAndCorumFundCode(Long memberId, String corumFundCode);

    /**
     * The fund a Sofidy sync writes to, matched on Sofidy's own Code_Produit.
     * A holding with no match is skipped, so a manually entered account that was
     * never linked stays untouched.
     */
    Optional<ScpiPosition> findByMemberIdAndSofidyFundCode(Long memberId, String sofidyFundCode);

    /**
     * Every position this member linked to a Sofidy fund.
     *
     * <p>A complete snapshot is authoritative: a fund Sofidy no longer lists was
     * sold, so its position has to go to zero rather than keep a balance the
     * member does not hold any more. Reaching the positions this way is what
     * makes that reconciliation possible.
     */
    List<ScpiPosition> findByAccountMemberIdAndSofidyFundCodeIsNotNull(Long memberId);
}
