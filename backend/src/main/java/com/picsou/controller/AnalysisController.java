package com.picsou.controller;

import com.picsou.dto.AllocationTargetsRequest;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.dto.ProjectionResponse;
import com.picsou.dto.SecurityProfileRefreshResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.config.RateLimitConfig;
import com.picsou.config.ClientIp;
import com.picsou.service.AllocationTargetService;
import com.picsou.service.EssentialExpenseEstimator;
import com.picsou.service.PortfolioDiversificationService;
import com.picsou.service.ProjectionService;
import com.picsou.service.SecurityProfileRefreshRunner;
import com.picsou.service.UserContext;
import com.picsou.service.WealthPyramidService;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Wealth analysis: how the portfolio is built, rather than what it is worth.
 *
 * <p>Singular path, following {@code /api/dashboard}: this is one view of the member's wealth,
 * not a collection of analyses.
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final WealthPyramidService pyramidService;
    private final PortfolioDiversificationService diversificationService;
    private final ProjectionService projectionService;
    private final AllocationTargetService allocationTargetService;
    private final EssentialExpenseEstimator expenseEstimator;
    private final SecurityProfileRefreshRunner profileRefreshRunner;
    private final UserContext userContext;
    private final Map<String, Bucket> syncBuckets;

    public AnalysisController(WealthPyramidService pyramidService,
                              PortfolioDiversificationService diversificationService,
                              ProjectionService projectionService,
                              AllocationTargetService allocationTargetService,
                              EssentialExpenseEstimator expenseEstimator,
                              SecurityProfileRefreshRunner profileRefreshRunner,
                              UserContext userContext,
                              @Qualifier("syncBuckets") Map<String, Bucket> syncBuckets) {
        this.pyramidService = pyramidService;
        this.diversificationService = diversificationService;
        this.projectionService = projectionService;
        this.allocationTargetService = allocationTargetService;
        this.expenseEstimator = expenseEstimator;
        this.profileRefreshRunner = profileRefreshRunner;
        this.userContext = userContext;
        this.syncBuckets = syncBuckets;
    }

    /** The five tiers, their weights against the member's targets, and the resulting score. */
    @GetMapping("/pyramid")
    public WealthPyramidResponse pyramid() {
        return pyramidService.pyramid(userContext.currentMemberId());
    }

    /**
     * How the equity sleeve spreads across sectors and regions.
     *
     * <p>Reads persisted profiles only, so it never blocks on a scrape. Whatever has no profile
     * yet is reported as unclassified with its tickers named, rather than renormalised away.
     */
    @GetMapping("/diversification")
    public DiversificationResponse diversification() {
        return diversificationService.diversification(userContext.currentMemberId());
    }

    /**
     * The investable portfolio projected forward under four return assumptions, fed by the
     * member's recurring investment plans.
     *
     * <p>Property, loans and alternative assets are excluded from the base — a house does not
     * compound at 7.5% a year, and including it would inflate every scenario.
     */
    @GetMapping("/projection")
    public ProjectionResponse projection(@RequestParam(defaultValue = "20") int years) {
        return projectionService.project(userContext.currentMemberId(), years);
    }

    /** The member's targets, or the shipped defaults when they have never set any. */
    @GetMapping("/allocation-targets")
    public AllocationTargetsResponse targets() {
        return allocationTargetService.get(userContext.currentMemberId());
    }

    /** Replaces the whole profile. 422 when the four percentages do not sum to 100. */
    @PutMapping("/allocation-targets")
    public AllocationTargetsResponse replaceTargets(@Valid @RequestBody AllocationTargetsRequest request) {
        return allocationTargetService.replace(userContext.currentMemberId(), request);
    }

    /**
     * What the member's own transactions suggest they spend monthly. Offered as a suggestion for
     * the form above — accepting it is a PUT, so the figure is never stored on their behalf.
     */
    @GetMapping("/essential-expenses/estimate")
    public EssentialExpenseEstimateResponse expenseEstimate() {
        return expenseEstimator.estimate(userContext.currentMemberId());
    }

    /**
     * Warms the security profiles the diversification breakdown reads from, now rather than on
     * the weekly schedule.
     *
     * <p>202, never 200: the scraping outlives the request by design. Rate-limited on the sync
     * bucket because it reaches two unofficial sources, and idempotent while a pass is running —
     * a second call reports {@code alreadyRunning} instead of starting a rival pass.
     */
    @PostMapping("/security-profiles/refresh")
    public ResponseEntity<?> refreshSecurityProfiles(HttpServletRequest request) {
        String ip = ClientIp.resolve(request);
        Bucket bucket = syncBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createSyncBucket());
        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many refresh requests. Please wait a moment.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        int queued = profileRefreshRunner.trigger();
        return ResponseEntity.accepted().body(queued < 0
            ? new SecurityProfileRefreshResponse(0, true)
            : new SecurityProfileRefreshResponse(queued, false));
    }
}
