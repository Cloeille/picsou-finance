package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.port.CaisseEpargneErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One Caisse d'Epargne session per member. Holds the encrypted session state only:
 * there is deliberately no credentials field (decision D5, cookies only).
 */
@Entity
@Table(name = "caisse_epargne_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CaisseEpargneSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Session cookies and authorize params, encrypted via CryptoEncryption. Never exposed. */
    @JsonIgnore
    @ToString.Exclude
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
    private CaisseEpargneSyncStatus syncStatus = CaisseEpargneSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private CaisseEpargneErrorCode lastSyncError;

    /** JSON array of {externalId, familyCode}: contracts seen but not imported. */
    @Column(name = "unsupported_contracts", columnDefinition = "TEXT")
    private String unsupportedContracts;

    public static CaisseEpargneSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return CaisseEpargneSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(CaisseEpargneSyncStatus.IDLE)
            .build();
    }

    public void updateSessionState(String encryptedState, Instant validatedAt) {
        this.sessionState = Objects.requireNonNull(encryptedState, "encryptedState");
        this.lastValidatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
        this.active = true;
    }

    public void recordUnsupportedContracts(String json) {
        this.unsupportedContracts = json;
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive Caisse d'Epargne sessions cannot be queued");
        }
        if (isSyncInFlight()) {
            throw new IllegalStateException("Caisse d'Epargne synchronization is already in progress");
        }
        syncStatus = CaisseEpargneSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != CaisseEpargneSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Caisse d'Epargne session can run");
        }
        syncStatus = CaisseEpargneSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != CaisseEpargneSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Caisse d'Epargne session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = CaisseEpargneSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(CaisseEpargneErrorCode errorCode, Instant completedAt) {
        if (!isSyncInFlight()) {
            throw new IllegalStateException("Only an in-flight Caisse d'Epargne synchronization can fail");
        }
        CaisseEpargneErrorCode error = Objects.requireNonNull(errorCode, "errorCode");
        syncStatus = CaisseEpargneSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // The stored cookies cannot be silently renewed (no password is kept), so a session the
        // bank rejected or Picsou cannot read is dead; a transient failure leaves it usable.
        if (error == CaisseEpargneErrorCode.SESSION_EXPIRED
            || error == CaisseEpargneErrorCode.INVALID_SESSION_STATE) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == CaisseEpargneSyncStatus.QUEUED || syncStatus == CaisseEpargneSyncStatus.RUNNING;
    }
}
