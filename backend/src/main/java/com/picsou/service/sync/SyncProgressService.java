package com.picsou.service.sync;

import com.picsou.dto.DiscoveredRevolutAccount;
import com.picsou.dto.SyncProgress;
import com.picsou.port.RevolutPort.RevolutAccountData;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-member+provider live progress for background bank-sync jobs (Revolut discovery today;
 * Trade Republic progress-only in a later increment), mirroring
 * {@link com.picsou.service.budget.AiCategorizationJobService}'s single-flight
 * {@link ConcurrentHashMap#compute} guard.
 *
 * <p>Revolut's discovery flow additionally holds the harvested-but-unpersisted accounts here
 * in-memory between {@code RevolutSyncService.discover} and {@code RevolutSyncService.confirmSync}
 * ({@link #setDiscovered} / {@link #takePendingDiscovery}) -- non-durable across a backend
 * restart by design; a confirm with nothing pending fails clearly rather than silently no-op-ing.
 *
 * <p>The {@code jobs} map is package-private to allow tests to pre-seed a running state.
 */
@Service
public class SyncProgressService {

    /** Package-private so tests can pre-seed a RUNNING state. */
    final ConcurrentHashMap<Key, State> jobs = new ConcurrentHashMap<>();

    // ─── Key / State ─────────────────────────────────────────────────────────

    record Key(Long memberId, SyncProvider provider) {}

    static final class State {
        volatile boolean running = true;
        volatile boolean done;
        volatile String error;
        volatile String phase;
        volatile Integer remainingSeconds;
        volatile Integer accountsFound;
        /** Last elapsed-seconds reported by the connector itself; falls back to {@link #startedAt} until set. */
        volatile Integer elapsedSecondsOverride;
        final Instant startedAt = Instant.now();
        /** Raw harvested accounts awaiting confirm -- never serialized. */
        volatile List<RevolutAccountData> pendingDiscovery;
        /** Serializable preview of {@link #pendingDiscovery} -- this is what goes into the DTO. */
        volatile List<DiscoveredRevolutAccount> discoveredPreview;

        SyncProgress toDto() {
            int elapsed = elapsedSecondsOverride != null
                ? elapsedSecondsOverride
                : (int) Duration.between(startedAt, Instant.now()).getSeconds();
            List<DiscoveredRevolutAccount> discovered = discoveredPreview != null ? discoveredPreview : List.of();
            return new SyncProgress(running, phase, elapsed, remainingSeconds, accountsFound, done, error, discovered);
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Starts a new job for the member+provider if none is currently running, atomically (mirrors
     * {@code AiCategorizationJobService#start}'s {@code jobs.compute} race guard).
     *
     * @return true if this call won the race and should submit the background job; false if a
     *         job is already running (the caller should just report its current status)
     */
    public boolean startIfIdle(Long memberId, SyncProvider provider) {
        Key key = new Key(memberId, provider);
        State fresh = new State();
        State[] winner = { null };
        jobs.compute(key, (k, cur) -> {
            if (cur != null && cur.running) { winner[0] = cur; return cur; }
            winner[0] = fresh;
            return fresh;
        });
        return winner[0] == fresh;
    }

    /** Idle default (never started) when no job has been submitted for this member+provider. */
    public SyncProgress status(Long memberId, SyncProvider provider) {
        State s = jobs.get(new Key(memberId, provider));
        return s == null
            ? new SyncProgress(false, null, null, null, null, false, null, List.of())
            : s.toDto();
    }

    // ─── Phase / counters ────────────────────────────────────────────────────

    public void phase(Long memberId, SyncProvider provider, String phase) {
        phase(memberId, provider, phase, null, null, null);
    }

    /** Any {@code null} argument leaves that field unchanged. */
    public void phase(Long memberId, SyncProvider provider, String phase,
                       Integer remainingSeconds, Integer elapsedSeconds, Integer accountsFound) {
        State s = jobs.get(new Key(memberId, provider));
        if (s == null) return;
        s.phase = phase;
        if (remainingSeconds != null) s.remainingSeconds = remainingSeconds;
        if (elapsedSeconds != null) s.elapsedSecondsOverride = elapsedSeconds;
        if (accountsFound != null) s.accountsFound = accountsFound;
    }

    public void accountsFound(Long memberId, SyncProvider provider, int count) {
        State s = jobs.get(new Key(memberId, provider));
        if (s != null) s.accountsFound = count;
    }

    // ─── Revolut discovery hand-off ──────────────────────────────────────────

    /** Stores the discovery result: {@code preview} is what the DTO exposes, {@code raw} stays in-memory only. */
    public void setDiscovered(Long memberId, SyncProvider provider,
                               List<DiscoveredRevolutAccount> preview, List<RevolutAccountData> raw) {
        State s = jobs.get(new Key(memberId, provider));
        if (s == null) return;
        s.discoveredPreview = preview;
        s.pendingDiscovery = raw;
    }

    /** Returns and clears the pending raw discovery for Revolut; empty (never null) if absent. */
    public List<RevolutAccountData> takePendingDiscovery(Long memberId) {
        State s = jobs.get(new Key(memberId, SyncProvider.REVOLUT));
        if (s == null) return List.of();
        List<RevolutAccountData> raw = s.pendingDiscovery;
        s.pendingDiscovery = null;
        return raw != null ? raw : List.of();
    }

    // ─── Terminal states ─────────────────────────────────────────────────────

    public void done(Long memberId, SyncProvider provider) {
        State s = jobs.get(new Key(memberId, provider));
        if (s == null) return;
        s.done = true;
        s.running = false;
    }

    public void error(Long memberId, SyncProvider provider, String message) {
        State s = jobs.get(new Key(memberId, provider));
        if (s == null) return;
        s.error = message;
        s.done = true;
        s.running = false;
    }
}
