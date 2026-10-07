package com.picsou.dto;

import java.util.List;

public record AiCallLogPage(
    List<AiCallLogResponse> items,
    long total,
    long totalTokens
) {}
