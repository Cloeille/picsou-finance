package com.picsou.repository;

import com.picsou.model.CmesSession;
import com.picsou.model.CmesSyncStatus;
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

public interface CmesSessionRepository extends JpaRepository<CmesSession, Long> {
    Optional<CmesSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CmesSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<CmesSyncStatus> interrupted,
        @Param("failed") CmesSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") SidecarErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from CmesSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<CmesSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from CmesSession session where session.member.id = :memberId")
    Optional<CmesSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
