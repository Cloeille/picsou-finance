package com.picsou.dto;

import com.picsou.model.AiCategorizationMode;
import com.picsou.model.BudgetSettings;

import java.time.LocalDate;

public record BudgetSettingsResponse(
    int cycleStartDay,
    boolean logoFetchEnabled,
    boolean aiCategorizationEnabled,
    AiCategorizationMode aiMode,
    int aiConfidenceThreshold,
    LocalDate currentCycleStart,
    LocalDate currentCycleEnd
) {
    public static BudgetSettingsResponse of(BudgetSettings s, LocalDate cycleStart, LocalDate cycleEnd) {
        return new BudgetSettingsResponse(
            s.getCycleStartDay(),
            s.isLogoFetchEnabled(),
            s.isAiCategorizationEnabled(),
            s.getAiMode(),
            s.getAiConfidenceThreshold(),
            cycleStart,
            cycleEnd
        );
    }
}
