package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import com.picsou.port.BanquePopulaireRegion;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One Banque Populaire session per member. Holds the encrypted session state and the regional
 * bank it was opened at: there is deliberately no credentials field (decision D5, cookies only).
 */
@Entity
@Table(name = "banque_populaire_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BanquePopulaireSession extends AuditableEntity implements BpceSession {
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

    /**
     * The regional bank chosen at the login that opened this session. Not a secret: it names the
     * bank, which the sync stamps on the accounts it imports and looks the logo up under.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "region", nullable = false, length = 32)
    private BanquePopulaireRegion region;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private BanquePopulaireSyncStatus syncStatus = BanquePopulaireSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private SidecarErrorCode lastSyncError;

    /** JSON array of {externalId, familyCode}: contracts seen but not imported. */
    @Column(name = "unsupported_contracts", columnDefinition = "TEXT")
    private String unsupportedContracts;

    public static BanquePopulaireSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt,
        BanquePopulaireRegion region
    ) {
        return BanquePopulaireSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .region(Objects.requireNonNull(region, "region"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(BanquePopulaireSyncStatus.IDLE)
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
            throw new IllegalStateException("Inactive Banque Populaire sessions cannot be queued");
        }
        if (isSyncInFlight()) {
            throw new IllegalStateException("Banque Populaire synchronization is already in progress");
        }
        syncStatus = BanquePopulaireSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != BanquePopulaireSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued Banque Populaire session can run");
        }
        syncStatus = BanquePopulaireSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != BanquePopulaireSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running Banque Populaire session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = BanquePopulaireSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(SidecarErrorCode errorCode, Instant completedAt) {
        if (!isSyncInFlight()) {
            throw new IllegalStateException("Only an in-flight Banque Populaire synchronization can fail");
        }
        SidecarErrorCode error = SidecarBank.BANQUE_POPULAIRE.requireDeclared(Objects.requireNonNull(errorCode, "errorCode"));
        syncStatus = BanquePopulaireSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // The stored cookies cannot be silently renewed (no password is kept), so a session the
        // bank rejected or Picsou cannot read is dead; a transient failure leaves it usable.
        if (error == SidecarErrorCode.SESSION_EXPIRED
            || error == SidecarErrorCode.INVALID_SESSION_STATE) {
            active = false;
        }
    }

    @Override
    public boolean isQueued() {
        return syncStatus == BanquePopulaireSyncStatus.QUEUED;
    }

    @Override
    public boolean isRunning() {
        return syncStatus == BanquePopulaireSyncStatus.RUNNING;
    }

    @Override
    public boolean isSyncInFlight() {
        return syncStatus == BanquePopulaireSyncStatus.QUEUED || syncStatus == BanquePopulaireSyncStatus.RUNNING;
    }
}
