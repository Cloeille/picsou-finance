package com.picsou.mcp.tools;

import com.picsou.dto.AiJobStatus;
import com.picsou.mcp.RequiresScope;
import com.picsou.mcp.Scopes;
import com.picsou.service.UserContext;
import com.picsou.service.budget.AiCategorizationJobService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * MCP tools over the member's AI categorization job, the one behind the inbox "Categorize with AI"
 * button. Both go through {@link AiCategorizationJobService}, so provider, mode, threshold and
 * concurrency stay the instance's configuration. Progress is exposed as counters only: the job's
 * error message can echo prompt or provider response text, so it is never returned.
 */
@Component
public class AiCategorizationTools {

    private final AiCategorizationJobService jobService;
    private final UserContext userContext;

    public AiCategorizationTools(AiCategorizationJobService jobService, UserContext userContext) {
        this.jobService = jobService;
        this.userContext = userContext;
    }

    @Tool(name = "start_ai_categorization",
        description = "Start the same background job as the inbox 'Categorize with AI' button over the "
            + "authenticated member's uncategorized transactions, using the instance's configured "
            + "provider, mode, threshold and concurrency. Rules are applied when transactions are imported; "
            + "only transactions still without a category are sent. Never starts a second job while one "
            + "runs (started=false, returns the running job). Returns immediately; while state is RUNNING, "
            + "poll get_ai_categorization_status until COMPLETED or FAILED. started=false with state "
            + "COMPLETED and total 0 means there was nothing to do (AI categorization disabled for the "
            + "member, no categories, or nothing uncategorized): no job exists, do not poll. "
            + "started is advisory; the running job is the source of truth.")
    @RequiresScope(Scopes.BUDGET_AI_WRITE)
    public AiCategorizationStart startAiCategorization() {
        Long memberId = userContext.currentMemberId();
        // Advisory: the service's compute() guard, not this pre-read, prevents a second job.
        boolean alreadyRunning = jobService.status(memberId).running();
        AiJobStatus status = jobService.start(memberId);
        return new AiCategorizationStart(!alreadyRunning && status.running(), AiCategorizationProgress.from(status));
    }

    @Tool(name = "get_ai_categorization_status",
        description = "Read the progress of the authenticated member's AI categorization job: state "
            + "(IDLE, RUNNING, COMPLETED, FAILED) and aggregate counters only (total, processed, applied, "
            + "suggested, unresolved). No transaction labels, amounts, prompts, model responses or "
            + "provider error text. COMPLETED or FAILED describes the last job until the next one starts.")
    @RequiresScope(Scopes.BUDGET_AI_WRITE)
    public AiCategorizationProgress getAiCategorizationStatus() {
        return AiCategorizationProgress.from(jobService.status(userContext.currentMemberId()));
    }

    public record AiCategorizationStart(boolean started, AiCategorizationProgress job) {}

    /** {@code unresolved}: processed transactions the model returned nothing usable for. */
    public record AiCategorizationProgress(State state, int total, int processed, int applied, int suggested,
                                           int unresolved) {

        public enum State { IDLE, RUNNING, COMPLETED, FAILED }

        static AiCategorizationProgress from(AiJobStatus s) {
            State state = s.running() ? State.RUNNING
                : s.done() && s.error() != null ? State.FAILED
                : s.done() ? State.COMPLETED
                : State.IDLE;
            return new AiCategorizationProgress(state, s.total(), s.processed(), s.applied(), s.suggested(),
                Math.max(0, s.processed() - s.applied() - s.suggested()));
        }
    }
}
