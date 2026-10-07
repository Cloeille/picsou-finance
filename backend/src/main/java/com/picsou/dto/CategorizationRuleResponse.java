package com.picsou.dto;

import com.picsou.model.CategorizationRule;
import com.picsou.model.RuleMatchType;
import com.picsou.model.RuleSource;

public record CategorizationRuleResponse(
    Long id,
    RuleMatchType matchType,
    String pattern,
    Long categoryId,
    String categoryName,
    int priority,
    RuleSource source
) {
    public static CategorizationRuleResponse from(CategorizationRule r) {
        return new CategorizationRuleResponse(
            r.getId(), r.getMatchType(), r.getPattern(),
            r.getCategory().getId(), r.getCategory().getName(),
            r.getPriority(), r.getSource()
        );
    }
}
