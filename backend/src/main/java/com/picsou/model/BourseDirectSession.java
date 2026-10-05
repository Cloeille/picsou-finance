package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.BourseDirectErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "bourse_direct_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BourseDirectSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Complete Playwright storage state, encrypted via CryptoEncryption. */
    @Column(name = "session_state", nullable = false, columnDefinition = "TEXT")
    private String sessionState;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private BourseDirectSyncStatus syncStatus = BourseDirectSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private BourseDirectErrorCode lastSyncError;

    public static BourseDirectSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return BourseDirectSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(BourseDirectSyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Bourse Direct sessions cannot be queued");
        }
        if (syncStatus == BourseDirectSyncStatus.QUEUED
            || syncStatus == BourseDirectSyncStatus.RUNNING) {
            throw new IllegalStateException("Bourse Direct synchronization is already in progress");
        }
        syncStatus = BourseDirectSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != BourseDirectSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Bourse Direct session can run");
        }
        syncStatus = BourseDirectSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != BourseDirectSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Bourse Direct session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = BourseDirectSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(BourseDirectErrorCode errorCode, Instant completedAt) {
        if (syncStatus != BourseDirectSyncStatus.QUEUED
            && syncStatus != BourseDirectSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight Bourse Direct synchronization can fail");
        }
        BourseDirectErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = BourseDirectSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == BourseDirectErrorCode.SESSION_EXPIRED) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == BourseDirectSyncStatus.QUEUED
            || syncStatus == BourseDirectSyncStatus.RUNNING;
    }
}
