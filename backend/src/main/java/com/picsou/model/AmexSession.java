package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.AmexErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "amex_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AmexSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Amex session cookies, encrypted via CryptoEncryption. Never exposed. */
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
    private AmexSyncStatus syncStatus = AmexSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private AmexErrorCode lastSyncError;

    public static AmexSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return AmexSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(AmexSyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Amex sessions cannot be queued");
        }
        if (syncStatus == AmexSyncStatus.QUEUED || syncStatus == AmexSyncStatus.RUNNING) {
            throw new IllegalStateException("Amex synchronization is already in progress");
        }
        syncStatus = AmexSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != AmexSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Amex session can run");
        }
        syncStatus = AmexSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != AmexSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Amex session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = AmexSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(AmexErrorCode errorCode, Instant completedAt) {
        if (syncStatus != AmexSyncStatus.QUEUED && syncStatus != AmexSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight Amex synchronization can fail");
        }
        AmexErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = AmexSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // Only an expired session needs re-authentication; a failed fetch leaves
        // a usable session so the daily scheduler can simply retry.
        if (error == AmexErrorCode.SESSION_EXPIRED) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == AmexSyncStatus.QUEUED || syncStatus == AmexSyncStatus.RUNNING;
    }
}
