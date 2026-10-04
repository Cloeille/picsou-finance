package com.picsou.repository;

import com.picsou.model.CorumSession;
import com.picsou.model.FamilyMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CorumSessionRepository extends JpaRepository<CorumSession, Long> {

    Optional<CorumSession> findByMemberId(Long memberId);

    /** Serializes creation and deletion even when no session row exists yet. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from FamilyMember m where m.id = :memberId")
    Optional<FamilyMember> findMemberByIdForUpdate(@Param("memberId") Long memberId);

    /**
     * Row-locked so two concurrent syncs cannot both pass the "is one already
     * running" check and then race to write the same account.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CorumSession s where s.member.id = :memberId")
    Optional<CorumSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CorumSession s where s.id = :id and s.member.id = :memberId")
    Optional<CorumSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );
}
