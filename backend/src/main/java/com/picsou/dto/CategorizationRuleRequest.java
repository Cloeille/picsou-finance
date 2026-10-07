package com.picsou.dto;

import com.picsou.model.RuleMatchType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategorizationRuleRequest(
    @NotNull RuleMatchType matchType,
    @NotBlank String pattern,
    @NotNull Long categoryId,
    Integer priority
) {}
