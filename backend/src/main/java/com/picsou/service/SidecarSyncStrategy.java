package com.picsou.service;

import com.picsou.model.SidecarSession;
import com.picsou.port.SidecarErrorCode;

import java.time.Instant;

/**
 * What differs between two sidecar banks once a sync job runs: how to fetch and prepare the
 * provider's data, and how to write it. {@link SidecarSyncTemplate} owns everything else.
 *
 * @param <T> the prepared data, built outside any transaction
 */
public interface SidecarSyncStrategy<T> {

    /** Calls the sidecar and prepares its answer. Runs outside any transaction; may be slow. */
    T fetch(SidecarSyncJob job);

    /**
     * Writes the prepared data. Runs in the job's transaction, after the template checked the
     * session is still the active running one, and before it marks the session successful.
     */
    void commit(SidecarSyncJob job, T prepared, Instant syncedAt);

    /** Work after the commit that must not fail the import (enrichment, classification). */
    default void afterCommit(SidecarSyncJob job, T prepared) {}

    /** A short count for the completion log line, such as {@code "accounts=3"}. */
    default String describe(T prepared) {
        return "";
    }

    /** Runs before a failure is recorded on the session, inside the same transaction. */
    default void onFailure(SidecarSession session, SidecarErrorCode code) {}

    /** Whether an unexpected failure is logged by type only, because its message may carry payload. */
    default boolean redactsUnexpectedFailures() {
        return false;
    }
}
