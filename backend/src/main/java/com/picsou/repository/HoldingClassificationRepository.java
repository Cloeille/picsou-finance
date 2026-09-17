package com.picsou.repository;

import com.picsou.model.HoldingClassification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingClassificationRepository extends JpaRepository<HoldingClassification, Long> {

    /** The member's whole override set — one query, then an in-memory lookup per holding. */
    List<HoldingClassification> findByMemberId(Long memberId);

    Optional<HoldingClassification> findByMemberIdAndTicker(Long memberId, String ticker);
}
