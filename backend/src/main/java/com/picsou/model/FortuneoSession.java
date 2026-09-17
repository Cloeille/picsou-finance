package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.FortuneoErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "fortuneo_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FortuneoSession extends AuditableEntity {
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
    private FortuneoSyncStatus syncStatus = FortuneoSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private FortuneoErrorCode lastSyncError;

    public static FortuneoSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return FortuneoSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(FortuneoSyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Fortuneo sessions cannot be queued");
        }
        if (syncStatus == FortuneoSyncStatus.QUEUED
            || syncStatus == FortuneoSyncStatus.RUNNING) {
            throw new IllegalStateException("Fortuneo synchronization is already in progress");
        }
        syncStatus = FortuneoSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != FortuneoSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Fortuneo session can run");
        }
        syncStatus = FortuneoSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != FortuneoSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Fortuneo session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = FortuneoSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(FortuneoErrorCode errorCode, Instant completedAt) {
        if (syncStatus != FortuneoSyncStatus.QUEUED
            && syncStatus != FortuneoSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight Fortuneo synchronization can fail");
        }
        FortuneoErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = FortuneoSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == FortuneoErrorCode.SESSION_EXPIRED) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == FortuneoSyncStatus.QUEUED
            || syncStatus == FortuneoSyncStatus.RUNNING;
    }
}
