package com.picsou.controller;

import com.picsou.dto.BudgetSettingsRequest;
import com.picsou.dto.BudgetSettingsResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Per-member budget configuration — currently the payday {@code cycleStartDay}. */
@RestController
@RequestMapping("/api/budget/settings")
public class BudgetSettingsController {

    private final BudgetSettingsService budgetSettingsService;
    private final UserContext userContext;

    public BudgetSettingsController(BudgetSettingsService budgetSettingsService, UserContext userContext) {
        this.budgetSettingsService = budgetSettingsService;
        this.userContext = userContext;
    }

    @GetMapping
    public BudgetSettingsResponse get() {
        return budgetSettingsService.get(userContext.currentMemberId());
    }

    @PutMapping
    public BudgetSettingsResponse update(@Valid @RequestBody BudgetSettingsRequest req) {
        return budgetSettingsService.update(req, userContext.currentMemberId());
    }
}
