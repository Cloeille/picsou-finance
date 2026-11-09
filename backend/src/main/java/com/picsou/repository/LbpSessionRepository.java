package com.picsou.repository;

import com.picsou.model.LbpSession;
import com.picsou.model.LbpSyncStatus;
import com.picsou.port.SidecarErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface LbpSessionRepository extends JpaRepository<LbpSession, Long> {
    Optional<LbpSession> findByMemberId(Long memberId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE LbpSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<LbpSyncStatus> interrupted,
        @Param("failed") LbpSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") SidecarErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from LbpSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<LbpSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from LbpSession session where session.member.id = :memberId")
    Optional<LbpSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
