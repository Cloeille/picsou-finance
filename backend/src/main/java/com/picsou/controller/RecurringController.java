package com.picsou.controller;

import com.picsou.dto.RecurringActivityResponse;
import com.picsou.dto.RecurringOccurrenceResponse;
import com.picsou.dto.RecurringSeriesRequest;
import com.picsou.dto.RecurringSeriesResponse;
import com.picsou.model.RecurringStatus;
import com.picsou.service.UserContext;
import com.picsou.service.budget.RecurringDetectionService;
import com.picsou.service.budget.RecurringSeriesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Recurring subscriptions / direct debits / salaries: list and triage detected series,
 * declare them manually, and read the projected upcoming-payments calendar. Detection runs
 * automatically after each sync; {@code POST /detect} triggers it on demand.
 */
@RestController
@RequestMapping("/api/recurring")
public class RecurringController {

    private final RecurringSeriesService seriesService;
    private final RecurringDetectionService detectionService;
    private final UserContext userContext;

    public RecurringController(
        RecurringSeriesService seriesService,
        RecurringDetectionService detectionService,
        UserContext userContext
    ) {
        this.seriesService = seriesService;
        this.detectionService = detectionService;
        this.userContext = userContext;
    }

    /** List series, optionally filtered by status (SUGGESTED / CONFIRMED / IGNORED). */
    @GetMapping
    public List<RecurringSeriesResponse> findAll(@RequestParam(required = false) RecurringStatus status) {
        return seriesService.findAll(userContext.currentMemberId(), status, LocalDate.now());
    }

    /**
     * The "what changed" activity feed — series the detector auto-confirmed or re-priced recently,
     * newest first. The safety net for silent auto-confirmation; each entry is reversible via undo.
     */
    @GetMapping("/activity")
    public List<RecurringActivityResponse> activity() {
        return seriesService.activity(userContext.currentMemberId(), LocalDate.now());
    }

    /** Projected charges from today through the next {@code horizonDays} days (default 60). */
    @GetMapping("/calendar")
    public List<RecurringOccurrenceResponse> calendar(
        @RequestParam(defaultValue = "60") int horizonDays
    ) {
        return seriesService.upcoming(userContext.currentMemberId(), LocalDate.now(), horizonDays);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RecurringSeriesResponse create(@Valid @RequestBody RecurringSeriesRequest req) {
        return seriesService.create(req, userContext.currentMemberId());
    }

    @PutMapping("/{id}")
    public RecurringSeriesResponse update(@PathVariable Long id, @Valid @RequestBody RecurringSeriesRequest req) {
        return seriesService.update(id, req, userContext.currentMemberId());
    }

    @PostMapping("/{id}/confirm")
    public RecurringSeriesResponse confirm(@PathVariable Long id) {
        return seriesService.confirm(id, userContext.currentMemberId());
    }

    @PostMapping("/{id}/ignore")
    public RecurringSeriesResponse ignore(@PathVariable Long id) {
        return seriesService.ignore(id, userContext.currentMemberId());
    }

    /**
     * Reverse a recent activity-feed change: acknowledge a price step (keep the new amount, clear the
     * alert) or reject a silent auto-confirm (send the series to IGNORED).
     */
    @PostMapping("/{id}/undo")
    public RecurringSeriesResponse undo(@PathVariable Long id) {
        return seriesService.undo(id, userContext.currentMemberId(), LocalDate.now());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        seriesService.delete(id, userContext.currentMemberId());
    }

    /** Re-run detection over recent transactions now; returns how many series were upserted. */
    @PostMapping("/detect")
    public Map<String, Integer> detect() {
        int count = detectionService.detect(userContext.currentMemberId(), LocalDate.now());
        return Map.of("detected", count);
    }
}
