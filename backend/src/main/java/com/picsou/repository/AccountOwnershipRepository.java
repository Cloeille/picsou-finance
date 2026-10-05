package com.picsou.repository;

import com.picsou.model.AccountOwnership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AccountOwnershipRepository extends JpaRepository<AccountOwnership, Long> {

    List<AccountOwnership> findByAccountId(Long accountId);

    List<AccountOwnership> findByMemberId(Long memberId);

    @Query("SELECT o FROM AccountOwnership o WHERE o.account.id IN :accountIds")
    List<AccountOwnership> findByAccountIdIn(@Param("accountIds") Collection<Long> accountIds);

    @Query("SELECT o.account.id FROM AccountOwnership o WHERE o.member.id = :memberId")
    List<Long> findAccountIdsByMemberId(@Param("memberId") Long memberId);

    /**
     * JPQL rather than a derived {@code deleteByAccountId}: a replace-the-whole-split write
     * deletes then re-inserts, and Hibernate flushes the inserts before a derived delete,
     * tripping {@code uk_account_ownership_account_member}. {@code clearAutomatically} also
     * drops the stale entities the caller may still hold.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM AccountOwnership o WHERE o.account.id = :accountId")
    void deleteAllForAccount(@Param("accountId") Long accountId);
}
