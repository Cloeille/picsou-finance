package com.picsou.repository;

import com.picsou.model.Debt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DebtRepository extends JpaRepository<Debt, Long> {
    Optional<Debt> findByAccountId(Long accountId);
    List<Debt> findByLinkedAccountId(Long linkedAccountId);
    List<Debt> findAllByMemberId(Long memberId);
    List<Debt> findByAccountIdIn(Collection<Long> accountIds);
}
