package com.picsou.repository;

import com.picsou.model.FortuneoSession;
import com.picsou.model.FortuneoSyncStatus;
import com.picsou.port.FortuneoErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface FortuneoSessionRepository extends JpaRepository<FortuneoSession, Long> {
    Optional<FortuneoSession> findByMemberId(Long memberId);

    boolean existsByMemberIdAndActiveTrue(Long memberId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE FortuneoSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
          AND session.member.id = :memberId
        """)
    int markInterruptedSyncsFailed(
        @Param("memberId") Long memberId,
        @Param("interrupted") Collection<FortuneoSyncStatus> interrupted,
        @Param("failed") FortuneoSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") FortuneoErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from FortuneoSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<FortuneoSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from FortuneoSession session where session.member.id = :memberId")
    Optional<FortuneoSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
