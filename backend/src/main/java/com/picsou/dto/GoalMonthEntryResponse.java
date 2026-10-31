package com.picsou.dto;

import java.math.BigDecimal;

public record GoalMonthEntryResponse(
    String yearMonth,        // "2025-03"
    BigDecimal objective,    // override ?? monthly needed (pre-calculated)
    BigDecimal actual,       // derived from balance snapshots, null if no data
    BigDecimal manualActual, // manual declaration, null if not set
    BigDecimal override,     // manual override of objective, null if not set
    BigDecimal effective     // manualActual ?? actual (what was saved; never the override)
) {}
