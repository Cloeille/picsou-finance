package com.picsou.repository;

import com.picsou.model.Debt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DebtRepository extends JpaRepository<Debt, Long> {
    Optional<Debt> findByAccountId(Long accountId);
    List<Debt> findByLinkedAccountId(Long linkedAccountId);
    List<Debt> findAllByMemberId(Long memberId);
    List<Debt> findByAccountIdIn(Collection<Long> accountIds);

    /** Unlinks every loan from an account about to be soft-deleted (see AccountService#delete). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Debt d SET d.linkedAccount = null WHERE d.linkedAccount.id = :accountId")
    int unlinkAccount(@Param("accountId") Long accountId);
}
