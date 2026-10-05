package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.AmundiErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "amundi_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AmundiSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /**
     * The sidecar's opaque session blob -- Playwright storage state plus the
     * harvested bearer -- encrypted via CryptoEncryption. Never exposed.
     */
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
    private AmundiSyncStatus syncStatus = AmundiSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private AmundiErrorCode lastSyncError;

    public static AmundiSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return AmundiSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(AmundiSyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Amundi sessions cannot be queued");
        }
        if (syncStatus == AmundiSyncStatus.QUEUED
            || syncStatus == AmundiSyncStatus.RUNNING) {
            throw new IllegalStateException("Amundi synchronization is already in progress");
        }
        syncStatus = AmundiSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != AmundiSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Amundi session can run");
        }
        syncStatus = AmundiSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != AmundiSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Amundi session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = AmundiSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(AmundiErrorCode errorCode, Instant completedAt) {
        if (syncStatus != AmundiSyncStatus.QUEUED
            && syncStatus != AmundiSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight Amundi synchronization can fail");
        }
        AmundiErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = AmundiSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == AmundiErrorCode.SESSION_EXPIRED) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == AmundiSyncStatus.QUEUED
            || syncStatus == AmundiSyncStatus.RUNNING;
    }
}
