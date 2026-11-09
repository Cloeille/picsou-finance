package com.picsou.repository;

import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.model.SidecarSession;
import com.picsou.model.SidecarSyncStatus;
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

public interface SidecarSessionRepository extends JpaRepository<SidecarSession, Long> {

    @Query("select s from SidecarSession s where s.bank = :bank and s.member.id = :memberId")
    Optional<SidecarSession> findByBankAndMemberId(
        @Param("bank") SidecarBank bank,
        @Param("memberId") Long memberId
    );

    boolean existsByBankAndActiveTrue(SidecarBank bank);

    boolean existsByBankAndMemberIdAndActiveTrue(SidecarBank bank, Long memberId);

    long countByBank(SidecarBank bank);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE SidecarSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.bank = :bank AND session.syncStatus IN :interrupted
        """)
    int markInterruptedSyncsFailed(
        @Param("bank") SidecarBank bank,
        @Param("interrupted") Collection<SidecarSyncStatus> interrupted,
        @Param("failed") SidecarSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") SidecarErrorCode errorCode
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE SidecarSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.bank = :bank AND session.syncStatus IN :interrupted
          AND session.member.id = :memberId
        """)
    int markInterruptedSyncsFailed(
        @Param("bank") SidecarBank bank,
        @Param("memberId") Long memberId,
        @Param("interrupted") Collection<SidecarSyncStatus> interrupted,
        @Param("failed") SidecarSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") SidecarErrorCode errorCode
    );

    /**
     * {@link #markInterruptedSyncsFailed(SidecarBank, Collection, SidecarSyncStatus, Instant, SidecarErrorCode)}
     * for the rows of one bank not touched since {@code staleBefore}: a live job keeps updating its row,
     * so only an orphan is failed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE SidecarSession session
        SET session.syncStatus = :failed,
            session.lastSyncCompletedAt = :completedAt,
            session.lastSyncError = :errorCode
        WHERE session.bank = :bank AND session.syncStatus IN :interrupted
          AND session.updatedAt < :staleBefore
        """)
    int markInterruptedSyncsFailedBefore(
        @Param("bank") SidecarBank bank,
        @Param("interrupted") Collection<SidecarSyncStatus> interrupted,
        @Param("failed") SidecarSyncStatus failed,
        @Param("completedAt") Instant completedAt,
        @Param("errorCode") SidecarErrorCode errorCode,
        @Param("staleBefore") Instant staleBefore
    );

    /** Row-locked so two concurrent syncs cannot both pass the "is one already running" check. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select session from SidecarSession session
        where session.id = :id and session.member.id = :memberId
        """)
    Optional<SidecarSession> findByIdAndMemberIdForUpdate(
        @Param("id") Long id,
        @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SidecarSession s where s.bank = :bank and s.member.id = :memberId")
    Optional<SidecarSession> findByBankAndMemberIdForUpdate(
        @Param("bank") SidecarBank bank,
        @Param("memberId") Long memberId
    );
}
