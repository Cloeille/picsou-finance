package com.picsou.repository;

import com.picsou.model.SofidySession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SofidySessionRepository extends JpaRepository<SofidySession, Long> {

    Optional<SofidySession> findByMemberId(Long memberId);

    /**
     * Row-locked so two concurrent syncs cannot both pass the "is one already
     * running" check and then race to write the same account.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SofidySession s where s.member.id = :memberId")
    Optional<SofidySession> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SofidySession s where s.id = :id and s.member.id = :memberId")
    Optional<SofidySession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );
}
