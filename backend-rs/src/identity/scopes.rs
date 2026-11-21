//! Scopes: the access-key and MCP OAuth scope vocabulary (domain:action).

pub const ALL: &[&str] = &[
    "accounts:read",
    "transactions:read",
    "goals:read",
    "dashboard:read",
    "prices:read",
    "family:read",
    "sync:read",
    "analysis:read",
    "budget:categories-read",
    "budget:rules-read",
    "budget:transactions-read",
    "budget:recurring-read",
    "budget:envelopes-read",
    "budget:dashboard-read",
    "oauth2:discover",
    "oauth2:session-status",
    "accounts:write",
    "transactions:write",
    "goals:write",
    "sync:trigger",
    "budget:categories-write",
    "budget:rules-write",
    "budget:transactions-write",
    "budget:recurring-write",
    "budget:envelopes-write",
    "budget:ai-write",
];

pub fn is_known(scope: &str) -> bool {
    ALL.contains(&scope)
}

/// Every scope, sorted, as the protected-resource metadata lists them.
pub fn sorted() -> Vec<&'static str> {
    let mut all = ALL.to_vec();
    all.sort_unstable();
    all
}

/// The read-only scopes (*:read, *-read) a DCR request without a scope receives. Java iterates a
/// Set.of, whose order changes with every JVM start; declaration order is as good as any.
pub fn default_read() -> Vec<&'static str> {
    ALL.iter()
        .copied()
        .filter(|s| s.ends_with(":read") || s.ends_with("-read"))
        .collect()
}
