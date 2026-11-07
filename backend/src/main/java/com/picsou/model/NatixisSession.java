package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "natixis_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NatixisSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Natixis refresh token, device and employee ids, encrypted via CryptoEncryption. Never exposed. */
    @Column(name = "session_state", nullable = false, columnDefinition = "TEXT")
    @ToString.Exclude
    private String sessionState;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private NatixisSyncStatus syncStatus = NatixisSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private SidecarErrorCode lastSyncError;

    public static NatixisSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return NatixisSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(NatixisSyncStatus.IDLE)
            .build();
    }

    /** The refresh token may rotate on every read; the newest one replaces the stored one. */
    public void updateSessionState(String encryptedState) {
        this.sessionState = Objects.requireNonNull(encryptedState, "encryptedState");
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Natixis sessions cannot be queued");
        }
        if (syncStatus == NatixisSyncStatus.QUEUED || syncStatus == NatixisSyncStatus.RUNNING) {
            throw new IllegalStateException("Natixis synchronization is already in progress");
        }
        syncStatus = NatixisSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != NatixisSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Natixis session can run");
        }
        syncStatus = NatixisSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != NatixisSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Natixis session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = NatixisSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(SidecarErrorCode errorCode, Instant completedAt) {
        if (syncStatus != NatixisSyncStatus.QUEUED && syncStatus != NatixisSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight Natixis synchronization can fail");
        }
        SidecarErrorCode error = SidecarBank.NATIXIS.requireDeclared(Objects.requireNonNull(errorCode, "errorCode"));
        syncStatus = NatixisSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // A refused refresh token needs the saver to log in again with a new
        // code; a failed fetch leaves a usable session so the scheduler retries.
        if (error == SidecarErrorCode.SESSION_EXPIRED || error == SidecarErrorCode.INVALID_CREDENTIALS) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == NatixisSyncStatus.QUEUED || syncStatus == NatixisSyncStatus.RUNNING;
    }
}
