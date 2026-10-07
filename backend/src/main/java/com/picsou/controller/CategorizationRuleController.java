package com.picsou.controller;

import com.picsou.dto.CategorizationRuleRequest;
import com.picsou.dto.CategorizationRuleResponse;
import com.picsou.dto.RulePreviewRequest;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CategorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CRUD over auto-categorization rules, plus a bulk re-run endpoint that applies the
 * current rule set to everything still uncategorized.
 */
@RestController
@RequestMapping("/api/categorization-rules")
public class CategorizationRuleController {

    private final CategorizationService categorizationService;
    private final UserContext userContext;

    public CategorizationRuleController(CategorizationService categorizationService, UserContext userContext) {
        this.categorizationService = categorizationService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<CategorizationRuleResponse> findAll() {
        return categorizationService.findAllRules(userContext.currentMemberId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategorizationRuleResponse create(@Valid @RequestBody CategorizationRuleRequest req) {
        return categorizationService.createRule(req, userContext.currentMemberId());
    }

    @PutMapping("/{id}")
    public CategorizationRuleResponse update(@PathVariable Long id, @Valid @RequestBody CategorizationRuleRequest req) {
        return categorizationService.updateRule(id, req, userContext.currentMemberId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        categorizationService.deleteRule(id, userContext.currentMemberId());
    }

    /** Re-apply rules to uncategorized transactions; returns how many were assigned. */
    @PostMapping("/recategorize")
    public Map<String, Integer> recategorize() {
        int count = categorizationService.recategorizeUncategorized(userContext.currentMemberId());
        return Map.of("categorized", count);
    }

    /** Preview matching uncategorized transactions for a rule before creating it. */
    @PostMapping("/preview")
    public CategorizationService.RulePreviewResult preview(
            @Valid @RequestBody RulePreviewRequest req) {
        return categorizationService.previewRule(req.matchType(), req.pattern(),
            userContext.currentMemberId());
    }
}
