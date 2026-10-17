package com.picsou.dto;

import jakarta.validation.constraints.NotNull;

public record AdminTelemetryRequest(@NotNull Boolean enabled) {}
