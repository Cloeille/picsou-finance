package com.picsou.model;

import com.picsou.port.SidecarErrorCode;

import java.time.Instant;

/**
 * What the shared BPCE sync engine needs from a bank's session row: the {@link SidecarSession}
 * rows of bank {@code CAISSE_EPARGNE} and {@code BANQUE_POPULAIRE}. The state machine is the
 * same: IDLE, QUEUED, RUNNING, then SUCCESS or FAILED.
 */
public interface BpceSession {
    Long getId();

    boolean isActive();

    /** The bank's own sync-status constant; only ever logged by the engine. */
    Enum<?> getSyncStatus();

    boolean isQueued();

    boolean isRunning();

    boolean isSyncInFlight();

    /** The encrypted session state. */
    String getSessionState();

    String getUnsupportedContracts();

    void recordUnsupportedContracts(String json);

    void markQueued();

    void markRunning(Instant startedAt);

    void markSuccessful(Instant completedAt);

    void markFailed(SidecarErrorCode errorCode, Instant completedAt);
}
