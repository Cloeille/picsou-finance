package com.picsou.dto;

import com.picsou.model.AiCallLog;

import java.time.Instant;

public record AiCallLogResponse(
    Long id,
    Instant createdAt,
    Long memberId,
    Long transactionId,
    String merchantLabel,
    String batchId,
    String provider,
    String model,
    String prompt,
    String response,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    Integer latencyMs,
    String status,
    String error,
    String chosenSlug,
    Integer confidence,
    boolean applied
) {
    public static AiCallLogResponse from(AiCallLog log) {
        return new AiCallLogResponse(
            log.getId(),
            log.getCreatedAt(),
            log.getMemberId(),
            log.getTransactionId(),
            log.getMerchantLabel(),
            log.getBatchId() != null ? log.getBatchId().toString() : null,
            log.getProvider(),
            log.getModel(),
            log.getPrompt(),
            log.getResponse(),
            log.getPromptTokens(),
            log.getCompletionTokens(),
            log.getTotalTokens(),
            log.getLatencyMs(),
            log.getStatus(),
            log.getError(),
            log.getChosenSlug(),
            log.getConfidence(),
            log.isApplied()
        );
    }
}
