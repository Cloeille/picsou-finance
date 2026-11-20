package com.picsou.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AccountDisplayOrderRequest(@NotNull List<Long> accountIds) {}
