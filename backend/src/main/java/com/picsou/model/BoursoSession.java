package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.BoursoErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "bourso_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BoursoSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** BoursoBank session cookies, encrypted via CryptoEncryption. Never exposed. */
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
    private BoursoSyncStatus syncStatus = BoursoSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private BoursoErrorCode lastSyncError;

    public static BoursoSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return BoursoSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(BoursoSyncStatus.IDLE)
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
            throw new IllegalStateException("Inactive BoursoBank sessions cannot be queued");
        }
        if (syncStatus == BoursoSyncStatus.QUEUED || syncStatus == BoursoSyncStatus.RUNNING) {
            throw new IllegalStateException("BoursoBank synchronization is already in progress");
        }
        syncStatus = BoursoSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != BoursoSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued BoursoBank session can run");
        }
        syncStatus = BoursoSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != BoursoSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running BoursoBank session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = BoursoSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(BoursoErrorCode errorCode, Instant completedAt) {
        if (syncStatus != BoursoSyncStatus.QUEUED && syncStatus != BoursoSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight BoursoBank synchronization can fail");
        }
        BoursoErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = BoursoSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // Only an expired session needs re-authentication; a failed fetch leaves
        // a usable session so the daily scheduler can simply retry.
        if (error == BoursoErrorCode.SESSION_EXPIRED) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == BoursoSyncStatus.QUEUED || syncStatus == BoursoSyncStatus.RUNNING;
    }
}
