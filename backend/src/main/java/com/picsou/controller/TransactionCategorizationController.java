package com.picsou.controller;

import com.picsou.dto.AiJobStatus;
import com.picsou.dto.CategorizeRequest;
import com.picsou.dto.TransactionResponse;
import com.picsou.repository.TransactionRepository;
import com.picsou.service.UserContext;
import com.picsou.service.budget.AiCategorizationJobService;
import com.picsou.service.budget.CategorizationService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

import java.util.List;

/**
 * The "to categorize" inbox: lists member transactions with no managed category and lets
 * the user assign one (optionally learning a rule from it). Routes live under
 * {@code /api/transactions} since they operate on transactions across all accounts.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionCategorizationController {

    private final CategorizationService categorizationService;
    private final AiCategorizationJobService jobService;
    private final UserContext userContext;
    private final TransactionRepository transactionRepository;

    public TransactionCategorizationController(
            CategorizationService categorizationService,
            AiCategorizationJobService jobService,
            UserContext userContext,
            TransactionRepository transactionRepository) {
        this.categorizationService = categorizationService;
        this.jobService = jobService;
        this.userContext = userContext;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping
    public List<TransactionResponse> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Long categoryId) {
        return transactionRepository
                .searchByMember(userContext.currentMemberId(), from, to, accountId, categoryId)
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }

    @GetMapping("/uncategorized")
    public List<TransactionResponse> uncategorized() {
        return categorizationService.findUncategorized(userContext.currentMemberId());
    }

    @PutMapping("/{id}/category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void categorize(@PathVariable Long id, @Valid @RequestBody CategorizeRequest req) {
        categorizationService.categorize(id, req.categoryId(), req.createRule(),
            req.rulePattern(), req.ruleMatchType(), req.applyToTransactionIds(),
            userContext.currentMemberId());
    }

    /**
     * Start an async AI categorization job for the member's uncategorized transactions.
     * Returns 202 Accepted with the initial job status. If a job is already running for
     * this member, returns the current status without starting a new one.
     * No-op (returns done=true, total=0) when AI is disabled or no categories are configured.
     */
    @PostMapping("/categorize-ai")
    public ResponseEntity<AiJobStatus> categorizeWithAi() {
        return ResponseEntity.accepted().body(jobService.start(userContext.currentMemberId()));
    }

    /**
     * Poll the current AI categorization job status for the member.
     * Returns an idle status when no job has been submitted yet.
     */
    @GetMapping("/categorize-ai/status")
    public AiJobStatus categorizeAiStatus() {
        return jobService.status(userContext.currentMemberId());
    }
}
