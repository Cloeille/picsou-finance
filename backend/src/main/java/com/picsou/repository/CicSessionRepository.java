package com.picsou.repository;

import com.picsou.model.CicSession;
import com.picsou.model.CicSyncStatus;
import com.picsou.port.CicErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface CicSessionRepository extends JpaRepository<CicSession, Long> {
    Optional<CicSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CicSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<CicSyncStatus> interrupted,
        @Param("failed") CicSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") CicErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from CicSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<CicSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from CicSession session where session.member.id = :memberId")
    Optional<CicSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
