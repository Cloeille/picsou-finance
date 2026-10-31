package com.picsou.repository;

import com.picsou.model.CategorizationRule;
import com.picsou.model.RuleMatchType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, Long> {

    /** Highest priority first so the most specific rule wins. */
    List<CategorizationRule> findAllByMemberIdOrderByPriorityDescIdAsc(Long memberId);

    Optional<CategorizationRule> findByIdAndMemberId(Long id, Long memberId);

    Optional<CategorizationRule> findFirstByMemberIdAndMatchTypeAndPatternIgnoreCase(
        Long memberId, RuleMatchType matchType, String pattern);
}
