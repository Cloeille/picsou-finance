package com.picsou.repository;

import com.picsou.model.Requisition;
import com.picsou.model.RequisitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RequisitionRepository extends JpaRepository<Requisition, Long> {

    // All queries must be member-scoped (see backend/CLAUDE.md) — the single
    // exception is findByOauthState, documented below.
    List<Requisition> findAllByMemberId(Long memberId);
    Optional<Requisition> findByIdAndMemberId(Long id, Long memberId);
    List<Requisition> findByStatusAndMemberIdOrderByCreatedAtDesc(RequisitionStatus status, Long memberId);
    List<Requisition> findByStatusAndMemberIdAndInstitutionIdOrderByCreatedAtDesc(
        RequisitionStatus status, Long memberId, String institutionId);

    /**
     * One statement, so a requisition deleted concurrently simply counts 0. Finding the entity
     * and then removing it fails instead: the lookup misses, or the DELETE hits no row and
     * Hibernate raises a stale-state error.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Requisition r WHERE r.id = :id AND r.member.id = :memberId")
    int deleteByIdAndMemberId(@Param("id") Long id, @Param("memberId") Long memberId);

    /**
     * Deliberately not member-scoped: the random single-use state nonce IS the
     * credential binding the OAuth callback to its requisition (the member is
     * derived from the resolved row).
     */
    Optional<Requisition> findByOauthState(String oauthState);
}
