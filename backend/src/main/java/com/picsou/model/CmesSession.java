package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.CmesErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "cmes_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CmesSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Crédit Mutuel Épargne Salariale session cookies, encrypted via CryptoEncryption. Never exposed. */
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
    private CmesSyncStatus syncStatus = CmesSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private CmesErrorCode lastSyncError;

    public static CmesSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return CmesSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(CmesSyncStatus.IDLE)
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
            throw new IllegalStateException("Inactive CMES sessions cannot be queued");
        }
        if (syncStatus == CmesSyncStatus.QUEUED || syncStatus == CmesSyncStatus.RUNNING) {
            throw new IllegalStateException("CMES synchronization is already in progress");
        }
        syncStatus = CmesSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != CmesSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued CMES session can run");
        }
        syncStatus = CmesSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != CmesSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running CMES session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = CmesSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(CmesErrorCode errorCode, Instant completedAt) {
        if (syncStatus != CmesSyncStatus.QUEUED && syncStatus != CmesSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight CMES synchronization can fail");
        }
        CmesErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = CmesSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // An expired session and a rejected stored password both need the saver
        // to reconnect; a failed fetch leaves a usable session so the daily
        // scheduler can simply retry.
        if (error == CmesErrorCode.SESSION_EXPIRED || error == CmesErrorCode.INVALID_CREDENTIALS) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == CmesSyncStatus.QUEUED || syncStatus == CmesSyncStatus.RUNNING;
    }
}
