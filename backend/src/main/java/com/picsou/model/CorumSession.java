package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.CorumErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * A CORUM client-space session, stored encrypted.
 *
 * <p>CORUM asks for no second factor, so there is no pending state to fence
 * between two calls: a successful login yields a session or it does not. The
 * job state below still exists, because the snapshot read is slow and must not
 * be started twice concurrently.
 */
@Entity
@Table(name = "corum_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CorumSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /**
     * The sidecar's opaque session blob -- Playwright storage state plus the
     * harvested session cookie -- encrypted via CryptoEncryption. Never exposed.
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
    private CorumSyncStatus syncStatus = CorumSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private CorumErrorCode lastSyncError;

    public static CorumSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return CorumSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(CorumSyncStatus.IDLE)
            .build();
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive CORUM sessions cannot be queued");
        }
        if (syncStatus == CorumSyncStatus.QUEUED || syncStatus == CorumSyncStatus.RUNNING) {
            throw new IllegalStateException("CORUM synchronization is already in progress");
        }
        syncStatus = CorumSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != CorumSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued CORUM session can run");
        }
        syncStatus = CorumSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != CorumSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running CORUM session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = CorumSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(CorumErrorCode errorCode, Instant completedAt) {
        if (syncStatus != CorumSyncStatus.QUEUED && syncStatus != CorumSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an in-flight CORUM synchronization can fail");
        }
        CorumErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = CorumSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (error == CorumErrorCode.SESSION_EXPIRED) {
            // The cookie died, so the stored session can only be replaced by a
            // fresh login. Leaving it active would make every later sync fail
            // the same way with no path forward.
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == CorumSyncStatus.QUEUED || syncStatus == CorumSyncStatus.RUNNING;
    }
}
