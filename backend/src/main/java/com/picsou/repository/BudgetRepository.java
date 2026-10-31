package com.picsou.repository;

import com.picsou.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findAllByMemberIdOrderByIdAsc(Long memberId);

    Optional<Budget> findByIdAndMemberId(Long id, Long memberId);

    Optional<Budget> findByMemberIdAndCategoryId(Long memberId, Long categoryId);

    boolean existsByMemberIdAndCategoryId(Long memberId, Long categoryId);
}
