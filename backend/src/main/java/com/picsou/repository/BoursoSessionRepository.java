package com.picsou.repository;

import com.picsou.model.BoursoSession;
import com.picsou.model.BoursoSyncStatus;
import com.picsou.port.BoursoErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface BoursoSessionRepository extends JpaRepository<BoursoSession, Long> {
    Optional<BoursoSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE BoursoSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<BoursoSyncStatus> interrupted,
        @Param("failed") BoursoSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") BoursoErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from BoursoSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<BoursoSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from BoursoSession session where session.member.id = :memberId")
    Optional<BoursoSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
