package com.picsou.repository;

import com.picsou.model.AmexSession;
import com.picsou.model.AmexSyncStatus;
import com.picsou.port.AmexErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface AmexSessionRepository extends JpaRepository<AmexSession, Long> {
    Optional<AmexSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AmexSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<AmexSyncStatus> interrupted,
        @Param("failed") AmexSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") AmexErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from AmexSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<AmexSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AmexSession session where session.member.id = :memberId")
    Optional<AmexSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
