package com.picsou.dto;

import java.util.List;

/**
 * Snapshot of a bank-sync background job for one member + provider (Revolut discovery today;
 * Trade Republic progress-only in a later increment), mirroring {@link AiJobStatus}'s shape.
 *
 * @param running          true while the background job is still in progress
 * @param phase            current phase reported by the connector (e.g. {@code "AWAITING_APPROVAL"}),
 *                         null before the first update
 * @param elapsedSeconds   seconds since the job started, null when idle
 * @param remainingSeconds seconds left in the current phase's countdown (e.g. mobile-approval
 *                         wait), null when not applicable
 * @param accountsFound    running count of accounts harvested so far, null until first reported
 * @param done             true once the job finished (success or error)
 * @param error            set when the job terminated with an unhandled exception
 * @param errorCode        stable code for {@code error} when the UI translates it (e.g.
 *                         {@code "HUMAN_VERIFICATION_REQUIRED"}), otherwise null
 * @param discovered       Revolut only: accounts harvested but not yet persisted, awaiting
 *                         {@code POST /api/revolut/sync/confirm}; empty once confirmed
 */
public record SyncProgress(
    boolean running,
    String phase,
    Integer elapsedSeconds,
    Integer remainingSeconds,
    Integer accountsFound,
    boolean done,
    String error,
    String errorCode,
    List<DiscoveredRevolutAccount> discovered
) {}
