package com.picsou.service.sync;

/**
 * Phases reported by the revolut-auth sidecar's {@code GET /progress/{member_id}} endpoint
 * (see {@code services/revolut-auth/main.py}) and forwarded verbatim -- as plain strings, not
 * this enum -- into {@link SyncProgressService#phase}. The constants exist as a single source of
 * truth for the phase names both sides agree on; {@code done}/{@code error} stay orthogonal
 * {@code SyncProgress} fields, mirroring {@code AiJobStatus}.
 */
public enum RevolutSyncPhase {
    CHECKING_SESSION,
    LOGGING_IN,
    AWAITING_APPROVAL,
    HARVESTING
}
