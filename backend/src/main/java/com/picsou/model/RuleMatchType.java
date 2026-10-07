package com.picsou.model;

/** How a {@link CategorizationRule} matches a transaction. */
public enum RuleMatchType {
    /** Exact (case-insensitive) match on the transaction counterparty. */
    COUNTERPARTY,
    /** Substring (case-insensitive) match on counterparty, description, or merchantLabel. */
    KEYWORD,
    /** All space-split tokens must be case-insensitive substrings of any source field (AND). */
    KEYWORDS_ALL,
    /** At least one space-split token must be a case-insensitive substring of any source field (OR). */
    KEYWORDS_ANY
}
