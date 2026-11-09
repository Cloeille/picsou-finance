package com.picsou.repository;

import com.picsou.model.BanquePopulaireSession;
import com.picsou.model.BanquePopulaireSyncStatus;
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

public interface BanquePopulaireSessionRepository extends JpaRepository<BanquePopulaireSession, Long> {
    Optional<BanquePopulaireSession> findByMemberId(Long memberId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE BanquePopulaireSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<BanquePopulaireSyncStatus> interrupted,
        @Param("failed") BanquePopulaireSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") SidecarErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from BanquePopulaireSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<BanquePopulaireSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from BanquePopulaireSession session where session.member.id = :memberId")
    Optional<BanquePopulaireSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
