package com.picsou.repository;

import com.picsou.model.PersistentSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersistentSessionRepository extends JpaRepository<PersistentSession, Long> {

    Optional<PersistentSession> findBySeriesId(UUID seriesId);

    /**
     * Same lookup as {@link #findBySeriesId}, but takes a row-level write lock so
     * that concurrent "Remember Me" restores of the same series (multiple tabs
     * firing {@code /auth/refresh} at once) are serialized. Without it, two
     * requests read the same pre-rotation state, both rotate, and the second
     * clobbers the first — orphaning a token that then trips theft detection and
     * revokes the whole series. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PersistentSession s WHERE s.seriesId = :seriesId")
    Optional<PersistentSession> findBySeriesIdForUpdate(@Param("seriesId") UUID seriesId);

    List<PersistentSession> findByUserIdAndRevokedAtIsNullOrderByLastUsedAtDesc(Long userId);

    @Modifying
    @Transactional
    @Query("UPDATE PersistentSession s SET s.revokedAt = :now WHERE s.user.id = :userId AND s.revokedAt IS NULL")
    int revokeAllByUserId(Long userId, Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE PersistentSession s SET s.revokedAt = :now WHERE s.user.id = :userId AND s.id <> :exceptId AND s.revokedAt IS NULL")
    int revokeAllByUserIdExcept(Long userId, Long exceptId, Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE PersistentSession s SET s.revokedAt = :now WHERE s.seriesId = :seriesId AND s.revokedAt IS NULL")
    int revokeBySeriesId(UUID seriesId, Instant now);
}
