package com.picsou.controller;

import com.picsou.dto.CashflowPeriod;
import com.picsou.dto.SpendingByCategoryResponse;
import com.picsou.dto.SpendingDetailResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CashflowFlowService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Spending breakdown: the ranked expense-by-category list and the per-category drill.
 * Drill routes are keyed by category id (seeded categories also carry a {@code slug}, but
 * user-created ones do not), so the id is the reliable identifier.
 */
@RestController
@RequestMapping("/api/spending")
public class SpendingController {

    private final CashflowFlowService cashflowFlowService;
    private final UserContext userContext;

    public SpendingController(CashflowFlowService cashflowFlowService, UserContext userContext) {
        this.cashflowFlowService = cashflowFlowService;
        this.userContext = userContext;
    }

    @GetMapping("/by-category")
    public SpendingByCategoryResponse byCategory(
        @RequestParam(defaultValue = "CYCLE") CashflowPeriod period,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor
    ) {
        return cashflowFlowService.spendingByCategory(userContext.currentMemberId(), period,
            anchor != null ? anchor : LocalDate.now());
    }

    @GetMapping("/category/{categoryId}")
    public SpendingDetailResponse categoryDetail(
        @PathVariable Long categoryId,
        @RequestParam(defaultValue = "CYCLE") CashflowPeriod period,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor
    ) {
        return cashflowFlowService.categoryDetail(userContext.currentMemberId(), categoryId, period,
            anchor != null ? anchor : LocalDate.now());
    }
}
