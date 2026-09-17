package com.picsou.model;

/**
 * How much volatility the member says they accept.
 *
 * <p>Stated, never inferred. What someone actually holds is already measurable from their
 * accounts; the gap between that and what they say they want is the interesting figure, and it
 * only exists if the two are recorded separately.
 */
public enum RiskProfile {
    PRUDENT,
    BALANCED,
    DYNAMIC,
    AGGRESSIVE
}
