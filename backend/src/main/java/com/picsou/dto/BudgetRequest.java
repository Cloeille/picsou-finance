package com.picsou.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record BudgetRequest(
    @NotNull Long categoryId,
    @NotNull @PositiveOrZero BigDecimal monthlyLimit
) {}
