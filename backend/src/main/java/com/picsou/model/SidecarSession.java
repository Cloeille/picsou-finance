package com.picsou.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.picsou.adapter.sidecar.SidecarBank;
import com.picsou.port.BanquePopulaireRegion;
import com.picsou.port.SidecarErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One sidecar bank session per member and bank. Holds the encrypted session state, the
 * optional encrypted stored login (BoursoBank, CIC, CMES, CORUM; Caisse d'Epargne: the identifier only), the
 * Banque Populaire regional bank and the state of the last sync. It is the {@link BpceSession} of both BPCE banks.
 */
@Entity
@Table(name = "sidecar_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SidecarSession extends AuditableEntity implements BpceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank", nullable = false, length = 20, updatable = false)
    private SidecarBank bank;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    /** Session cookies, encrypted via CryptoEncryption. Never exposed. */
    @JsonIgnore
    @ToString.Exclude
    @Column(name = "session_state", nullable = false, columnDefinition = "TEXT")
    private String sessionState;

    @JsonIgnore
    @ToString.Exclude
    @Column(name = "encrypted_credentials", columnDefinition = "TEXT")
    private String encryptedCredentials;

    /** BPCE banks only: JSON array of {externalId, familyCode, subFamilyCode} seen but not imported. */
    @Column(name = "unsupported_contracts", columnDefinition = "TEXT")
    private String unsupportedContracts;

    /**
     * Banque Populaire only, and always set there: the regional bank chosen at the login that opened
     * this session. Not a secret: the sync stamps its name on the accounts and looks the logo up under it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "region", length = 32, updatable = false)
    private BanquePopulaireRegion region;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    @Builder.Default
    private SidecarSyncStatus syncStatus = SidecarSyncStatus.IDLE;

    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_sync_completed_at")
    private Instant lastSyncCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_error", length = 40)
    private SidecarErrorCode lastSyncError;

    /** CORUM only: CORUM Life contracts the last successful sync could not write, see {@code CorumLifeWriter}. */
    @Column(name = "last_sync_life_ambiguous", nullable = false)
    @Builder.Default
    private int lastSyncLifeAmbiguous = 0;

    public static SidecarSession create(
        SidecarBank bank,
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt
    ) {
        Objects.requireNonNull(bank, "bank");
        if (bank == SidecarBank.BANQUE_POPULAIRE) {
            throw new IllegalArgumentException(bank + " sessions need a region: use createBanquePopulaire");
        }
        return newSession(bank, member, encryptedSessionState, validatedAt, null);
    }

    /** A Banque Populaire session, opened at {@code region}. */
    public static SidecarSession createBanquePopulaire(
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt,
        BanquePopulaireRegion region
    ) {
        return newSession(SidecarBank.BANQUE_POPULAIRE, member, encryptedSessionState, validatedAt,
            Objects.requireNonNull(region, "region"));
    }

    private static SidecarSession newSession(
        SidecarBank bank,
        FamilyMember member,
        String encryptedSessionState,
        Instant validatedAt,
        BanquePopulaireRegion region
    ) {
        return SidecarSession.builder()
            .bank(bank)
            .member(Objects.requireNonNull(member, "member"))
            .sessionState(Objects.requireNonNull(encryptedSessionState, "encryptedSessionState"))
            .region(region)
            .lastValidatedAt(Objects.requireNonNull(validatedAt, "validatedAt"))
            .active(true)
            .syncStatus(SidecarSyncStatus.IDLE)
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

    public void recordUnsupportedContracts(String json) {
        this.unsupportedContracts = json;
    }

    /** CORUM: how many life contracts the sync being committed left unwritten. */
    public void recordLifeAmbiguous(int count) {
        if (bank != SidecarBank.CORUM) {
            throw new IllegalStateException("Only a CORUM session counts life contracts");
        }
        if (count < 0) {
            throw new IllegalArgumentException("lifeAmbiguous must not be negative");
        }
        this.lastSyncLifeAmbiguous = count;
    }

    /**
     * Queues a sync. Refused for an inactive session or one already queued or running; the previous
     * run's timestamps, error and CORUM Life ambiguous count are cleared.
     */
    public void markQueued() {
        if (!active) {
            throw new IllegalStateException("Inactive " + bank.label() + " sessions cannot be queued");
        }
        if (isSyncInFlight()) {
            throw new IllegalStateException(bank.label() + " synchronization is already in progress");
        }
        syncStatus = SidecarSyncStatus.QUEUED;
        lastSyncStartedAt = null;
        lastSyncCompletedAt = null;
        lastSyncError = null;
        lastSyncLifeAmbiguous = 0;
    }

    public void markRunning(Instant startedAt) {
        if (!active || syncStatus != SidecarSyncStatus.QUEUED) {
            throw new IllegalStateException("Only an active queued " + bank.label() + " session can run");
        }
        syncStatus = SidecarSyncStatus.RUNNING;
        lastSyncStartedAt = Objects.requireNonNull(startedAt, "startedAt");
        lastSyncCompletedAt = null;
        lastSyncError = null;
    }

    public void markSuccessful(Instant completedAt) {
        if (!active || syncStatus != SidecarSyncStatus.RUNNING) {
            throw new IllegalStateException("Only an active running " + bank.label() + " session can succeed");
        }
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        syncStatus = SidecarSyncStatus.SUCCESS;
        lastValidatedAt = completion;
        lastSyncCompletedAt = completion;
        lastSyncError = null;
    }

    public void markFailed(SidecarErrorCode errorCode, Instant completedAt) {
        if (!isSyncInFlight()) {
            throw new IllegalStateException("Only an in-flight " + bank.label() + " synchronization can fail");
        }
        SidecarErrorCode error = bank.requireDeclared(Objects.requireNonNull(errorCode, "errorCode"));
        syncStatus = SidecarSyncStatus.FAILED;
        lastSyncCompletedAt = Objects.requireNonNull(completedAt, "completedAt");
        lastSyncError = error;
        if (endsSession(error)) {
            active = false;
        }
    }

    /** CIC: a rejected stored password must not be replayed, the site locks the access. */
    public void rejectCredentials(Instant completedAt) {
        Instant completion = Objects.requireNonNull(completedAt, "completedAt");
        encryptedCredentials = null;
        active = false;
        syncStatus = SidecarSyncStatus.FAILED;
        lastSyncCompletedAt = completion;
        lastSyncError = SidecarErrorCode.INVALID_CREDENTIALS;
    }

    @Override
    public boolean isQueued() {
        return syncStatus == SidecarSyncStatus.QUEUED;
    }

    @Override
    public boolean isRunning() {
        return syncStatus == SidecarSyncStatus.RUNNING;
    }

    @Override
    public boolean isSyncInFlight() {
        return syncStatus == SidecarSyncStatus.QUEUED || syncStatus == SidecarSyncStatus.RUNNING;
    }

    /**
     * Whether a failure leaves the session unusable, so only a fresh login can replace it. A
     * transient failure keeps the session so the daily scheduler can simply retry.
     */
    private boolean endsSession(SidecarErrorCode error) {
        return switch (bank) {
            // No password is kept: unreadable cookies cannot be renewed either.
            // Banque Populaire, BNP Paribas and La Banque Postale: create() refuses them here; same
            // rule as lbp_session.
            case CAISSE_EPARGNE, BANQUE_POPULAIRE, BNP, LBP -> error == SidecarErrorCode.SESSION_EXPIRED
                || error == SidecarErrorCode.INVALID_SESSION_STATE;
            // The stored password was rejected: replaying it would lock the access.
            // Natixis keeps no password either: a refused refresh token needs a new login with a new code.
            case NATIXIS, CIC, CMES, CORUM, AMEX -> error == SidecarErrorCode.SESSION_EXPIRED
                || error == SidecarErrorCode.INVALID_CREDENTIALS;
            // The stored refresh cookie is the whole session: unreadable or rejected, only a new login helps.
            case TRADE_REPUBLIC -> error == SidecarErrorCode.SESSION_EXPIRED
                || error == SidecarErrorCode.INVALID_SESSION_STATE;
            case AMUNDI, BOURSE_DIRECT, BOURSO, FORTUNEO, SOFIDY ->
                error == SidecarErrorCode.SESSION_EXPIRED;
        };
    }
}
