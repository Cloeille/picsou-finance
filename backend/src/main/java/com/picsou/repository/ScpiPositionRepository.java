package com.picsou.repository;

import com.picsou.model.ScpiPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScpiPositionRepository extends JpaRepository<ScpiPosition, Long> {
    /**
     * The member id is the account owner's, not the viewer's. A co-owner reads the
     * owner's row; filtering by the viewer would hide a share they are allowed to see.
     */
    Optional<ScpiPosition> findByAccountIdAndMemberId(Long accountId, Long memberId);
}
