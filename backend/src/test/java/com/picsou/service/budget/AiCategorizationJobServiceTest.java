package com.picsou.service.budget;

import com.picsou.config.AiConfigProvider;
import com.picsou.dto.AiJobStatus;
import com.picsou.model.AiCategorizationMode;
import com.picsou.model.AiCallLog;
import com.picsou.port.TransactionCategorizerPort;
import com.picsou.port.TransactionCategorizerPort.CategorizationInput;
import com.picsou.port.TransactionCategorizerPort.CategorizationResult;
import com.picsou.port.TransactionCategorizerPort.CategoryOption;
import com.picsou.port.TransactionCategorizerPort.CategorySuggestion;
import com.picsou.service.AiCallLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AiCategorizationJobServiceTest {

    @Mock CategorizationService categorizationService;
    @Mock TransactionCategorizerPort categorizer;
    @Mock AiConfigProvider aiConfigProvider;
    @Mock AiCallLogService aiCallLogService;

    /** Both executors are synchronous so that runJob executes inline and status is readable immediately. */
    private static final java.util.concurrent.Executor SYNC = Runnable::run;

    AiCategorizationJobService service;

    @BeforeEach
    void setUp() {
        service = new AiCategorizationJobService(
            categorizationService,
            categorizer,
            aiConfigProvider,
            aiCallLogService,
            SYNC,
            SYNC
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static CategorizationService.AiContext ctx(boolean enabled, List<CategoryOption> options) {
        return new CategorizationService.AiContext(
            options,
            List.of(),
            Map.of("food", 10L),
            AiCategorizationMode.AUTO_HIGH_CONFIDENCE,
            80,
            enabled
        );
    }

    private static CategorizationResult okResult(String slug, double confidence) {
        return new CategorizationResult(
            Optional.of(new CategorySuggestion(slug, confidence)),
            "prompt-text", "resp-text", 10, 5, 15, 200L, "OK", null
        );
    }

    private static CategorizationResult emptyResult() {
        return new CategorizationResult(
            Optional.empty(), "prompt-text", "resp-empty", 5, 2, 7, 100L, "EMPTY", null
        );
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    void start_disabled_returnsDoneNoWork() {
        // loadAiContext returns a disabled context
        org.mockito.Mockito.when(categorizationService.loadAiContext(1L))
            .thenReturn(ctx(false, List.of()));

        AiJobStatus status = service.start(1L);

        assertThat(status.running()).isFalse();
        assertThat(status.done()).isTrue();
        assertThat(status.total()).isEqualTo(0);
        assertThat(status.error()).isNull();

        verify(categorizationService, never()).uncategorizedIds(any());
        verify(categorizer, never()).categorize(any(), any(), any());
        verifyNoInteractions(aiCallLogService);
    }

    @Test
    void start_emptyOptions_noWork() {
        // enabled=true but no categories → short-circuit
        org.mockito.Mockito.when(categorizationService.loadAiContext(1L))
            .thenReturn(ctx(true, List.of()));

        AiJobStatus status = service.start(1L);

        assertThat(status.running()).isFalse();
        assertThat(status.done()).isTrue();
        assertThat(status.total()).isEqualTo(0);

        verify(categorizationService, never()).uncategorizedIds(any());
        verifyNoInteractions(aiCallLogService);
    }

    @Test
    void start_processesAll_recordsAudit() {
        List<CategoryOption> options = List.of(new CategoryOption("food", "Food"));
        CategorizationService.AiContext ctx = ctx(true, options);
        org.mockito.Mockito.when(categorizationService.loadAiContext(1L)).thenReturn(ctx);
        org.mockito.Mockito.when(categorizationService.uncategorizedIds(1L)).thenReturn(List.of(1L, 2L, 3L));
        org.mockito.Mockito.when(aiConfigProvider.maxConcurrency()).thenReturn(10);
        org.mockito.Mockito.when(aiConfigProvider.current()).thenReturn(Optional.empty());

        Map<Long, CategorizationInput> inputs = Map.of(
            1L, new CategorizationInput("Carrefour", "Carrefour", BigDecimal.TEN),
            2L, new CategorizationInput("Netflix", "Netflix", BigDecimal.ONE),
            3L, new CategorizationInput("Amazon", "Amazon", BigDecimal.valueOf(50))
        );
        org.mockito.Mockito.when(categorizationService.inputsFor(anyList(), eq(1L))).thenReturn(inputs);

        org.mockito.Mockito.when(categorizer.categorize(eq(inputs.get(1L)), any(), any())).thenReturn(okResult("food", 0.95));
        org.mockito.Mockito.when(categorizer.categorize(eq(inputs.get(2L)), any(), any())).thenReturn(okResult("food", 0.75));
        org.mockito.Mockito.when(categorizer.categorize(eq(inputs.get(3L)), any(), any())).thenReturn(emptyResult());

        // tx1 auto-applied, tx2 suggested
        org.mockito.Mockito.when(categorizationService.applyAiResults(any(), eq(ctx), eq(1L)))
            .thenReturn(Map.of(1L, true, 2L, false));

        AiJobStatus status = service.start(1L);

        assertThat(status.total()).isEqualTo(3);
        assertThat(status.processed()).isEqualTo(3);
        assertThat(status.applied()).isEqualTo(1);
        assertThat(status.suggested()).isEqualTo(1);
        assertThat(status.done()).isTrue();
        assertThat(status.running()).isFalse();
        assertThat(status.error()).isNull();

        // saveAll must have been called with at least one row
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiCallLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(aiCallLogService).saveAll(captor.capture());
        assertThat(captor.getValue()).isNotEmpty();

        // applied flags must match decisions
        List<AiCallLog> rows = captor.getValue();
        assertThat(rows).anyMatch(r -> r.getTransactionId().equals(1L) && r.isApplied());
        assertThat(rows).anyMatch(r -> r.getTransactionId().equals(2L) && !r.isApplied());

        // EMPTY result for tx3 must also be logged (applied=false since not in decisions map)
        assertThat(rows).anyMatch(r -> r.getTransactionId().equals(3L) && !r.isApplied());

        verify(aiCallLogService).prune();
    }

    @Test
    void start_emptyIds_noWork() {
        List<CategoryOption> options = List.of(new CategoryOption("food", "Food"));
        org.mockito.Mockito.when(categorizationService.loadAiContext(1L)).thenReturn(ctx(true, options));
        org.mockito.Mockito.when(categorizationService.uncategorizedIds(1L)).thenReturn(List.of());

        AiJobStatus status = service.start(1L);

        assertThat(status.running()).isFalse();
        assertThat(status.done()).isTrue();
        assertThat(status.total()).isEqualTo(0);
        assertThat(status.error()).isNull();

        verify(categorizationService, never()).inputsFor(any(), any());
        verify(categorizer, never()).categorize(any(), any(), any());
        verifyNoInteractions(aiCallLogService);
    }

    @Test
    void start_whileRunning_returnsExisting() {
        // Pre-seed a running job state directly via the package-private map
        AiCategorizationJobService.JobState existing = new AiCategorizationJobService.JobState(42);
        // running = true by default
        service.jobs.put(1L, existing);

        AiJobStatus status = service.start(1L);

        assertThat(status.total()).isEqualTo(42);
        assertThat(status.running()).isTrue();
        assertThat(status.done()).isFalse();

        // Must not start a new job
        verify(categorizationService, never()).loadAiContext(any());
        verify(categorizationService, never()).uncategorizedIds(any());
        verifyNoInteractions(aiCallLogService);
    }

    @Test
    void runJob_exception_setsErrorNoThrow() {
        List<CategoryOption> options = List.of(new CategoryOption("food", "Food"));
        CategorizationService.AiContext ctx = ctx(true, options);
        org.mockito.Mockito.when(categorizationService.loadAiContext(1L)).thenReturn(ctx);
        org.mockito.Mockito.when(categorizationService.uncategorizedIds(1L)).thenReturn(List.of(1L));
        org.mockito.Mockito.when(aiConfigProvider.maxConcurrency()).thenReturn(10);
        org.mockito.Mockito.when(aiConfigProvider.current()).thenReturn(Optional.empty());
        org.mockito.Mockito.when(categorizationService.inputsFor(anyList(), eq(1L)))
            .thenReturn(Map.of(1L, new CategorizationInput("Test", "Test", BigDecimal.ONE)));
        org.mockito.Mockito.when(categorizer.categorize(any(), any(), any()))
            .thenReturn(okResult("food", 0.9));
        org.mockito.Mockito.when(categorizationService.applyAiResults(any(), any(), any()))
            .thenThrow(new RuntimeException("simulated DB error"));

        // Must not throw
        AiJobStatus status = service.start(1L);

        assertThat(status.done()).isTrue();
        assertThat(status.running()).isFalse();
        assertThat(status.error()).isNotNull().contains("simulated DB error");

        verify(aiCallLogService, never()).saveAll(any());
        verify(aiCallLogService, never()).prune();
    }

    @Test
    void respectsChunking() {
        List<CategoryOption> options = List.of(new CategoryOption("food", "Food"));
        CategorizationService.AiContext ctx = ctx(true, options);
        org.mockito.Mockito.when(categorizationService.loadAiContext(1L)).thenReturn(ctx);
        org.mockito.Mockito.when(categorizationService.uncategorizedIds(1L)).thenReturn(List.of(1L, 2L, 3L));
        org.mockito.Mockito.when(aiConfigProvider.maxConcurrency()).thenReturn(2); // chunk size 2
        org.mockito.Mockito.when(aiConfigProvider.current()).thenReturn(Optional.empty());

        // First chunk [1,2], second chunk [3]
        org.mockito.Mockito.when(categorizationService.inputsFor(anyList(), eq(1L)))
            .thenReturn(Map.of(1L, new CategorizationInput("A", "A", BigDecimal.ONE),
                               2L, new CategorizationInput("B", "B", BigDecimal.ONE)))
            .thenReturn(Map.of(3L, new CategorizationInput("C", "C", BigDecimal.ONE)));

        org.mockito.Mockito.when(categorizer.categorize(any(), any(), any())).thenReturn(emptyResult());
        org.mockito.Mockito.when(categorizationService.applyAiResults(any(), any(), any()))
            .thenReturn(Map.of());

        service.start(1L);

        // Two chunks → two calls each
        verify(categorizationService, times(2)).applyAiResults(any(), any(), any());
        verify(aiCallLogService, times(2)).saveAll(any());
        // prune called once at the end
        verify(aiCallLogService, times(1)).prune();
    }
}
