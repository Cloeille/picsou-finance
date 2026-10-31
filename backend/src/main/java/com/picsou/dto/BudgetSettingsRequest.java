package com.picsou.dto;

import com.picsou.model.AiCategorizationMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record BudgetSettingsRequest(
    @Min(1) @Max(28) int cycleStartDay,
    boolean logoFetchEnabled,
    boolean aiCategorizationEnabled,
    @NotNull AiCategorizationMode aiMode,
    @Min(0) @Max(100) int aiConfidenceThreshold
) {}
