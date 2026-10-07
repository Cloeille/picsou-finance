package com.picsou.service.budget;

import com.picsou.config.AiConfigProvider;
import com.picsou.config.AiProviderConfig;
import com.picsou.dto.AiJobStatus;
import com.picsou.model.AiCallLog;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.port.TransactionCategorizerPort.CategorizationInput;
import com.picsou.port.TransactionCategorizerPort.CategorizationResult;
import com.picsou.port.TransactionCategorizerPort.CategorySuggestion;
import com.picsou.service.AiCallLogService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs AI categorization as a member-scoped background job.
 *
 * <p>At most one job per member is active at a time (atomic guard via {@link ConcurrentHashMap#compute}).
 * Within each job, transactions are processed in chunks whose size is driven by
 * {@link AiConfigProvider#maxConcurrency()}; each chunk fans out to the {@code aiInferenceExecutor}
 * so multiple LLM calls run concurrently. Every call is written to {@code ai_call_log} for
 * audit and debugging, including failures (EMPTY/ERROR status).
 *
 * <p>The {@code jobs} map is package-private to allow tests to pre-seed a running state.
 */
@Service
public class AiCategorizationJobService {

    private final CategorizationService categorizationService;
    private final TransactionCategorizerPort categorizer;
    private final AiConfigProvider aiConfigProvider;
    private final AiCallLogService aiCallLogService;
    private final Executor jobExecutor;
    private final Executor inferenceExecutor;

    /** Package-private so tests can pre-seed a RUNNING state. */
    final ConcurrentHashMap<Long, JobState> jobs = new ConcurrentHashMap<>();

    public AiCategorizationJobService(
            CategorizationService categorizationService,
            TransactionCategorizerPort categorizer,
            AiConfigProvider aiConfigProvider,
            AiCallLogService aiCallLogService,
            @Qualifier("aiJobExecutor") Executor jobExecutor,
            @Qualifier("aiInferenceExecutor") Executor inferenceExecutor) {
        this.categorizationService = categorizationService;
        this.categorizer = categorizer;
        this.aiConfigProvider = aiConfigProvider;
        this.aiCallLogService = aiCallLogService;
        this.jobExecutor = jobExecutor;
        this.inferenceExecutor = inferenceExecutor;
    }

    // ─── Inner state ─────────────────────────────────────────────────────────

    static final class JobState {
        volatile boolean running = true;
        volatile boolean done;
        volatile String error;
        final int total;
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger applied = new AtomicInteger();
        final AtomicInteger suggested = new AtomicInteger();

        JobState(int total) {
            this.total = total;
        }

        AiJobStatus toStatus() {
            return new AiJobStatus(running, total, processed.get(), applied.get(), suggested.get(), done, error);
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Start a new job for the member, or return the current status if one is already running.
     * Short-circuits immediately (returns done=true, total=0) when AI is disabled or no
     * categories are configured — there would be nothing for the model to choose from.
     */
    public AiJobStatus start(Long memberId) {
        JobState existing = jobs.get(memberId);
        if (existing != null && existing.running) return existing.toStatus();   // fast path (non-authoritative)
        CategorizationService.AiContext ctx = categorizationService.loadAiContext(memberId);
        if (!ctx.enabled() || ctx.options().isEmpty()) return new AiJobStatus(false, 0, 0, 0, 0, true, null);
        List<Long> ids = categorizationService.uncategorizedIds(memberId);
        if (ids.isEmpty()) return new AiJobStatus(false, 0, 0, 0, 0, true, null);   // no-op guard
        JobState fresh = new JobState(ids.size());
        JobState[] winner = { null };
        jobs.compute(memberId, (k, cur) -> {
            if (cur != null && cur.running) { winner[0] = cur; return cur; }
            winner[0] = fresh;
            return fresh;
        });
        if (winner[0] != fresh) return winner[0].toStatus();                    // lost the race — another job is running
        jobExecutor.execute(() -> runJob(memberId, ids, ctx, fresh));
        return fresh.toStatus();
    }

    /**
     * Returns the current job status for the member, or an idle status if no job has been
     * submitted.
     */
    public AiJobStatus status(Long memberId) {
        JobState j = jobs.get(memberId);
        return j == null ? new AiJobStatus(false, 0, 0, 0, 0, false, null) : j.toStatus();
    }

    // ─── Job execution ───────────────────────────────────────────────────────

    void runJob(Long memberId, List<Long> ids, CategorizationService.AiContext ctx, JobState job) {
        try {
            int c = aiConfigProvider.maxConcurrency();
            UUID batch = UUID.randomUUID();
            Optional<AiProviderConfig> cfg = aiConfigProvider.current();
            String provider = cfg.map(p -> p.provider().name().toLowerCase()).orElse("none");
            String model = cfg.map(AiProviderConfig::effectiveModel).orElse(null);

            for (int i = 0; i < ids.size(); i += c) {
                List<Long> chunk = ids.subList(i, Math.min(i + c, ids.size()));
                Map<Long, CategorizationInput> inputs = categorizationService.inputsFor(chunk, memberId);

                Map<Long, CategorizationResult> results = new ConcurrentHashMap<>();
                CompletableFuture.allOf(
                    inputs.entrySet().stream()
                        .map(e -> CompletableFuture.runAsync(
                            () -> results.put(e.getKey(),
                                categorizer.categorize(e.getValue(), ctx.options(), ctx.examples())),
                            inferenceExecutor))
                        .toArray(CompletableFuture[]::new)
                ).join();

                Map<Long, CategorySuggestion> sugg = new HashMap<>();
                results.forEach((id, r) -> r.suggestion().ifPresent(s -> sugg.put(id, s)));

                Map<Long, Boolean> decisions = categorizationService.applyAiResults(sugg, ctx, memberId);
                aiCallLogService.saveAll(buildLogRows(memberId, batch, provider, model, chunk, inputs, results, decisions));

                int appliedCount = 0, suggestedCount = 0;
                for (Boolean b : decisions.values()) {
                    if (b) appliedCount++;
                    else suggestedCount++;
                }
                job.applied.addAndGet(appliedCount);
                job.suggested.addAndGet(suggestedCount);
                job.processed.addAndGet(chunk.size());
            }

            aiCallLogService.prune();
            job.done = true;
        } catch (Exception ex) {
            job.error = msg(ex);
            job.done = true;
        } finally {
            job.running = false;
        }
    }

    // ─── Audit log builder ───────────────────────────────────────────────────

    private List<AiCallLog> buildLogRows(
            Long memberId,
            UUID batch,
            String provider,
            String model,
            List<Long> chunk,
            Map<Long, CategorizationInput> inputs,
            Map<Long, CategorizationResult> results,
            Map<Long, Boolean> decisions) {
        List<AiCallLog> rows = new ArrayList<>();
        for (Long txId : chunk) {
            CategorizationResult r = results.get(txId);
            if (r == null) {
                continue; // transaction was absent from inputs (unknown / foreign id)
            }
            CategorizationInput input = inputs.get(txId);
            AiCallLog.AiCallLogBuilder b = AiCallLog.builder()
                .memberId(memberId)
                .transactionId(txId)
                .merchantLabel(input != null ? input.merchantLabel() : null)
                .batchId(batch)
                .provider(provider)
                .model(model)
                .prompt(r.prompt())
                .response(r.response())
                .promptTokens(r.promptTokens())
                .completionTokens(r.completionTokens())
                .totalTokens(r.totalTokens())
                .latencyMs((int) r.latencyMs())
                .status(r.status())
                .error(r.error())
                .applied(decisions.getOrDefault(txId, false));

            r.suggestion().ifPresent(s -> {
                b.chosenSlug(s.categorySlug());
                b.confidence((int) Math.round(clamp01(s.confidence()) * 100));
            });

            rows.add(b.build());
        }
        return rows;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String msg(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
