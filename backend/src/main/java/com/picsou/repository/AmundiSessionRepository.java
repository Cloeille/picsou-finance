package com.picsou.repository;

import com.picsou.model.AmundiSession;
import com.picsou.model.AmundiSyncStatus;
import com.picsou.port.AmundiErrorCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface AmundiSessionRepository extends JpaRepository<AmundiSession, Long> {
    Optional<AmundiSession> findByMemberId(Long memberId);

    boolean existsByActiveTrue();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE AmundiSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("interrupted") Collection<AmundiSyncStatus> interrupted,
        @Param("failed") AmundiSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") AmundiErrorCode errorCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from AmundiSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<AmundiSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AmundiSession session where session.member.id = :memberId")
    Optional<AmundiSession> findByMemberIdForUpdate(@Param("memberId") Long memberId);
}
