package com.picsou.model;

/**
 * How often a SCPI pays, as the member declared it.
 *
 * <p>Only the two cadences a French SCPI actually uses. Semi-annual and annual belong to listed
 * shares, which we infer from history rather than ask the member to type.
 */
public enum DistributionFrequency {
    MONTHLY,
    QUARTERLY
}
