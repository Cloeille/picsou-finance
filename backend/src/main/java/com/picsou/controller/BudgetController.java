package com.picsou.controller;

import com.picsou.dto.BudgetRequest;
import com.picsou.dto.BudgetResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.BudgetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Budget envelopes — one monthly cap per category, scored against the active payday cycle. */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;
    private final UserContext userContext;

    public BudgetController(BudgetService budgetService, UserContext userContext) {
        this.budgetService = budgetService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<BudgetResponse> findAll() {
        return budgetService.findAll(userContext.currentMemberId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse create(@Valid @RequestBody BudgetRequest req) {
        return budgetService.create(req, userContext.currentMemberId());
    }

    @PutMapping("/{id}")
    public BudgetResponse update(@PathVariable Long id, @Valid @RequestBody BudgetRequest req) {
        return budgetService.update(id, req, userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        budgetService.delete(id, userContext.currentMemberId());
    }
}
