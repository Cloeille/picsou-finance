package com.picsou.repository;

import com.picsou.model.BourseDirectSession;
import com.picsou.model.BourseDirectSyncStatus;
import com.picsou.port.BourseDirectErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface BourseDirectSessionRepository extends JpaRepository<BourseDirectSession, Long> {
    Optional<BourseDirectSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE BourseDirectSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<BourseDirectSyncStatus> interrupted,
        @Param("failed") BourseDirectSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") BourseDirectErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from BourseDirectSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<BourseDirectSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from BourseDirectSession session where session.member.id = :memberId")
    Optional<BourseDirectSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
