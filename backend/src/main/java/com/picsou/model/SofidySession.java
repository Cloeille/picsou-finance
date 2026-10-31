package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.SofidyErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * A Sofidy client-space session, stored encrypted.
 *
 * <p>The password and the verification code are never persisted: only the
 * sidecar's opaque session blob, exactly as the Amundi and CORUM sidecars do.
 * One row per member, replaced on reconnect.
 *
 * <p>An inactive row can also reserve a pending login. Its encrypted session
 * state then holds an attempt marker, not cookies. This binds the sidecar's
 * process to the member and lets disconnect/reconnect invalidate in-flight
 * authentication without storing a password or verification code.
 */
@Entity
@Table(name = "sofidy_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SofidySession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /**
     * The sidecar's opaque session blob -- the portal's PHPSESSID and its
     * companions -- encrypted via CryptoEncryption. Never exposed.
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
    private SofidySyncStatus syncStatus = SofidySyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private SofidyErrorCode lastSyncError;

    public static SofidySession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return SofidySession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(SofidySyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Sofidy sessions cannot be queued");
        }
        if (syncStatus == SofidySyncStatus.QUEUED || syncStatus == SofidySyncStatus.RUNNING) {
            throw new IllegalStateException("Sofidy synchronization is already in progress");
        }
        syncStatus = SofidySyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != SofidySyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Sofidy session can run");
        }
        syncStatus = SofidySyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != SofidySyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Sofidy session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = SofidySyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(SofidyErrorCode errorCode, Instant completedAt) {
        if (syncStatus != SofidySyncStatus.QUEUED && syncStatus != SofidySyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight Sofidy synchronization can fail");
        }
        SofidyErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = SofidySyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == SofidyErrorCode.SESSION_EXPIRED) {
            // The cookie died, so the stored session can only be replaced by a
            // fresh login. Leaving it active would make every later sync fail
            // the same way with no path forward.
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == SofidySyncStatus.QUEUED || syncStatus == SofidySyncStatus.RUNNING;
    }
}
