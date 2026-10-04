package com.picsou.mcp;

import java.util.Set;

/**
 * The complete vocabulary of access-key scopes ({@code domain:action}).
 *
 * <p>An access-key carries a subset of {@link #ALL}; each MCP {@code @Tool} method is gated by
 * exactly one of these via {@code @RequiresScope}. {@link #ALL} is the security allowlist —
 * {@code AccessKeyController} rejects any requested scope outside it, so a key can never be
 * granted a permission that no tool honours.
 *
 * <p>Read scopes end in {@code :read}; everything else mutates ({@code :write} / {@code :trigger}).
 * There is intentionally NO scope for credentials, MFA, admin settings, member management, or
 * data export — those operations have no tool, so no scope can reach them.
 */
public final class Scopes {

    private Scopes() {}

    // ── Read ────────────────────────────────────────────────────────────────
    public static final String ACCOUNTS_READ = "accounts:read";
    public static final String TRANSACTIONS_READ = "transactions:read";
    public static final String GOALS_READ = "goals:read";
    public static final String DASHBOARD_READ = "dashboard:read";
    public static final String PRICES_READ = "prices:read";
    public static final String FAMILY_READ = "family:read";
    public static final String SYNC_READ = "sync:read";

    public static final String BUDGET_CATEGORIES_READ = "budget:categories-read";
    public static final String BUDGET_RULES_READ = "budget:rules-read";
    public static final String BUDGET_TRANSACTIONS_READ = "budget:transactions-read";
    public static final String BUDGET_RECURRING_READ = "budget:recurring-read";
    public static final String BUDGET_ENVELOPES_READ = "budget:envelopes-read";
    public static final String BUDGET_DASHBOARD_READ = "budget:dashboard-read";
    public static final String OAUTH2_DISCOVER = "oauth2:discover";
    public static final String OAUTH2_SESSION_STATUS = "oauth2:session-status";

    // ── Write / trigger ───────────────────────────────────────────────────────
    public static final String ACCOUNTS_WRITE = "accounts:write";
    public static final String TRANSACTIONS_WRITE = "transactions:write";
    public static final String GOALS_WRITE = "goals:write";
    public static final String SYNC_TRIGGER = "sync:trigger";

    public static final String BUDGET_CATEGORIES_WRITE = "budget:categories-write";
    public static final String BUDGET_RULES_WRITE = "budget:rules-write";
    public static final String BUDGET_TRANSACTIONS_WRITE = "budget:transactions-write";
    public static final String BUDGET_ENVELOPES_WRITE = "budget:envelopes-write";

    /** Immutable allowlist of every valid scope. Used to validate key-creation requests. */
    public static final Set<String> ALL = Set.of(
        ACCOUNTS_READ, TRANSACTIONS_READ, GOALS_READ, DASHBOARD_READ, PRICES_READ, FAMILY_READ,
        SYNC_READ,
        BUDGET_CATEGORIES_READ, BUDGET_RULES_READ, BUDGET_TRANSACTIONS_READ, BUDGET_RECURRING_READ,
        BUDGET_ENVELOPES_READ, BUDGET_DASHBOARD_READ, OAUTH2_DISCOVER, OAUTH2_SESSION_STATUS,
        ACCOUNTS_WRITE, TRANSACTIONS_WRITE, GOALS_WRITE, SYNC_TRIGGER,
        BUDGET_CATEGORIES_WRITE, BUDGET_RULES_WRITE, BUDGET_TRANSACTIONS_WRITE, BUDGET_ENVELOPES_WRITE
    );
}
