package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.SidecarErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One La Banque Postale session per member. Holds the encrypted session state only:
 * there is deliberately no credentials field - the password and the keypad positions are
 * never stored.
 */
@Entity
@Table(name = "lbp_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class LbpSession extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private FamilyMember member;

    /** Session cookies, encrypted via CryptoEncryption. Never exposed. */
    @JsonIgnore
    @ToString.Exclude
    @Column(name = "session_state", nullable = false, columnDefinition = "TEXT")
    private String sessionState;

    /**
     * Optional remembered customer identifier, encrypted via CryptoEncryption. It is not a
     * password: the password and the keypad positions are still never stored.
     */
    @JsonIgnore
    @ToString.Exclude
    @Column(name = "encrypted_identifier", columnDefinition = "TEXT")
    private String encryptedIdentifier;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private LbpSyncStatus syncStatus = LbpSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private SidecarErrorCode lastSyncError;

    /** Reserved for future provisional kinds: JSON array of {externalId, familyCode}, always [] today. */
    @Column(name = "unsupported_products", columnDefinition = "TEXT")
    private String unsupportedProducts;

    public static LbpSession create(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        return LbpSession.builder()
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(LbpSyncStatus.IDLE)
            .build();
    }

    /** Stores or clears the optionally remembered customer identifier. */
    public void updateEncryptedIdentifier(String encryptedIdentifier) {
        this.encryptedIdentifier = encryptedIdentifier;
    }

    public void recordUnsupportedProducts(String json) {
        this.unsupportedProducts = json;
    }

    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive La Banque Postale sessions cannot be queued");
        }
        if (isSyncInFlight()) {
            throw new IllegalStateException("La Banque Postale synchronization is already in progress");
        }
        syncStatus = LbpSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != LbpSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued La Banque Postale session can run");
        }
        syncStatus = LbpSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != LbpSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running La Banque Postale session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = LbpSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(SidecarErrorCode errorCode, Instant completedAt) {
        if (!isSyncInFlight()) {
            throw new IllegalStateException("Only an in-flight La Banque Postale synchronization can fail");
        }
        SidecarErrorCode error = SidecarBank.LBP.requireDeclared(
            Objects.requireNonNull(errorCode, "errorCode"));
        syncStatus = LbpSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        // The stored cookies cannot be silently renewed (no password is kept), so a session
        // the bank rejected or Picsou cannot read is dead; a transient failure leaves it usable.
        if (error == SidecarErrorCode.SESSION_EXPIRED || error == SidecarErrorCode.INVALID_SESSION_STATE) {
            active = false;
        }
    }

    public boolean isSyncInFlight() {
        return syncStatus == LbpSyncStatus.QUEUED || syncStatus == LbpSyncStatus.RUNNING;
    }
}
