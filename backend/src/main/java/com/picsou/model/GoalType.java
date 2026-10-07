package com.picsou.model;

/**
 * What kind of goal this is.
 *
 * <p>The two shapes answer different questions and share almost no fields, but they live on one
 * entity because they share everything that surrounds them: the member scoping, the M:N link to
 * accounts, the contributor breakdown, the GDPR export and four MCP tools. Two tables would have
 * duplicated all of that to avoid two nullable columns.
 */
public enum GoalType {

    /** An amount by a date — "20 000 € for a trip in 2028". Everything Picsou had until now. */
    SAVINGS_TARGET,

    /** An amount every month, with no target — "300 € into the PEA". Feeds the projection. */
    RECURRING_INVESTMENT
}
