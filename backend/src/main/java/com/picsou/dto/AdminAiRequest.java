package com.picsou.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** Admin AI-provider config write. apiKey blank/omitted = keep the existing stored key. provider
 *  may be "none" to disable. maxConcurrency null = keep existing (default 4, clamped 1..16). */
public record AdminAiRequest(@NotBlank String provider, String model, String baseUrl, String apiKey,
                             @Min(1) @Max(16) Integer maxConcurrency) {}
