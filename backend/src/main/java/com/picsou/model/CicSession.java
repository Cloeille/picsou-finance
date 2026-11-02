package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.SidecarErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "cic_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CicSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** CIC session cookies, encrypted via CryptoEncryption. Never exposed. */
    @Column(name = "session_state", nullable = false, columnDefinition = "TEXT")
    private String sessionState;

    @Column(name = "encrypted_credentials", columnDefinition = "TEXT")
    @ToString.Exclude
    private String encryptedCredentials;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private CicSyncStatus syncStatus = CicSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private SidecarErrorCode lastSyncError;

    public static CicSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return CicSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(CicSyncStatus.IDLE)
            .build();
    }

    public void setEncryptedCredentials(String encryptedCredentials) {
        this.encryptedCredentials = encryptedCredentials;
    }

    public void updateSessionState(String encryptedState, Instant validatedAt) {
        this.sessionState = Objects.requireNonNull(encryptedState, "encryptedState");
        this.lastValidatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
        this.active = true;
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive CIC sessions cannot be queued");
        }
        if (syncStatus == CicSyncStatus.QUEUED || syncStatus == CicSyncStatus.RUNNING) {
            throw new IllegalStateException("CIC synchronization is already in progress");
        }
        syncStatus = CicSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != CicSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued CIC session can run");
        }
        syncStatus = CicSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != CicSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running CIC session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = CicSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(SidecarErrorCode errorCode, Instant completedAt) {
        if (syncStatus != CicSyncStatus.QUEUED && syncStatus != CicSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight CIC synchronization can fail");
        }
        SidecarErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = CicSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == SidecarErrorCode.SESSION_EXPIRED || error == SidecarErrorCode.INVALID_CREDENTIALS) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == CicSyncStatus.QUEUED || syncStatus == CicSyncStatus.RUNNING;
    }

    public void rejectCredentials(Instant completedAt) {
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        encryptedCredentials = null;
        active = false;
        syncStatus = CicSyncStatus.FAILED;
        lastSyncCompletedAt = completion;
        lastSyncError = SidecarErrorCode.INVALID_CREDENTIALS;
    }
}
