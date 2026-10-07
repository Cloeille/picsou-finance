package com.picsou.dto;

import com.picsou.model.RuleMatchType;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Assign a managed category to a transaction, optionally learning a rule from it. */
public record CategorizeRequest(
    @NotNull Long categoryId,
    boolean createRule,
    /** Optional explicit pattern for the rule (e.g. from RuleWordPicker). When present, ruleMatchType must also be set. */
    String rulePattern,
    /** Match type for the explicit rule pattern. Defaults to COUNTERPARTY when pattern is absent. */
    RuleMatchType ruleMatchType,
    /** Cherry-pick: if non-empty, retro-apply the rule only to these transaction ids. */
    List<Long> applyToTransactionIds
) {}
