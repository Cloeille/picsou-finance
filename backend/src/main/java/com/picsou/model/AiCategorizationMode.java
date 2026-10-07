package com.picsou.model;

/**
 * How an AI category suggestion is applied to an uncategorized transaction. Stored on
 * {@link BudgetSettings} per member; the default is {@link #AUTO_HIGH_CONFIDENCE}.
 */
public enum AiCategorizationMode {
    /** Never auto-apply — only record the suggestion for the inbox to surface. */
    SUGGEST,
    /** Auto-apply when confidence ≥ the member's threshold; otherwise record a suggestion. */
    AUTO_HIGH_CONFIDENCE,
    /** Always auto-apply the model's best answer, regardless of confidence. */
    AUTO_ALL
}
