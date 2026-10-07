package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_call_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "merchant_label", length = 512)
    private String merchantLabel;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "response", columnDefinition = "TEXT")
    private String response;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "chosen_slug", length = 64)
    private String chosenSlug;

    @Column(name = "confidence")
    private Integer confidence;

    @Column(name = "applied", nullable = false)
    @Builder.Default
    private boolean applied = false;
}
