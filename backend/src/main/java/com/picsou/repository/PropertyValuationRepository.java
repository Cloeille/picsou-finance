package com.picsou.repository;

import com.picsou.model.PropertyValuation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PropertyValuationRepository extends JpaRepository<PropertyValuation, Long> {

    Optional<PropertyValuation> findByAccountIdAndValuedAt(Long accountId, LocalDate valuedAt);

    /** Newest first — the head is the current estimate. */
    List<PropertyValuation> findByAccountIdOrderByValuedAtDesc(Long accountId);

    Optional<PropertyValuation> findFirstByAccountIdOrderByValuedAtDesc(Long accountId);

    List<PropertyValuation> findByMemberId(Long memberId);
}
