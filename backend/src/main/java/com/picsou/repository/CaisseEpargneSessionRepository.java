package com.picsou.repository;

import com.picsou.model.CaisseEpargneSession;
import com.picsou.model.CaisseEpargneSyncStatus;
import com.picsou.port.CaisseEpargneErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface CaisseEpargneSessionRepository extends JpaRepository<CaisseEpargneSession, Long> {
    Optional<CaisseEpargneSession> findByMemberId(Long memberId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CaisseEpargneSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<CaisseEpargneSyncStatus> interrupted,
        @Param("failed") CaisseEpargneSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") CaisseEpargneErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from CaisseEpargneSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<CaisseEpargneSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from CaisseEpargneSession session where session.member.id = :memberId")
    Optional<CaisseEpargneSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
