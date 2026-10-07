package com.picsou.dto;

/**
 * The kind of change surfaced in the recurring "what changed" activity feed — the safety net that
 * keeps silent auto-confirmation explainable (see ADR 2026-06-09). Each entry is undoable.
 */
public enum RecurringActivityType {
    /** The detector silently promoted a high-confidence series to CONFIRMED on the member's behalf. */
    AUTO_CONFIRMED,
    /** A confirmed series stepped to a new price level (previous → current). */
    PRICE_CHANGE
}
