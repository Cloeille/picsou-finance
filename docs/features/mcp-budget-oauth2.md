# Feature: Budget + OAuth2 tools in MCP

> Last updated: 2026-10-04

## Context

The MCP server initially exposed Account/Transaction/Goal/Dashboard/Price and Sync tools — read and write operations on the core financial data model. This feature adds two new tool families:

1. **Budget tools** (36 tools, 11 scopes) — full read/write surface over categorization rules (with a dry-run preview), transactions (budgeted view), recurring series (triage, manual declaration, detection, activity feed and calendar), spending envelopes, period-selectable spending and cashflow reads, and a composed dashboard (cashflow + top categories + upcoming subscriptions).
2. **OAuth2 tools** (2 tools, 2 scopes) — discovery of the authorization server's configuration and inspection of the access-key's own session metadata.

Together they bring the MCP surface to feature parity with the Budget page's read-only access and enable external AI apps (e.g. claude.ai via an MCP client) to analyze spending and manage rules.

## OAuth2 Tools

| Scope | Tool | What it does |
|-------|------|-------------|
| `oauth2:discover` | `get_oauth2_configuration` | Returns issuer, authorize/token/JWKS endpoints, the public client ID (`picsou-ios`), and PKCE-required flag. Static response built from `AuthorizationServerSettings` + `OAuthClientProperties` beans. |
| `oauth2:session-status` | `get_oauth2_session_status` | Returns the calling access-key's own metadata: name, scopes, created/last-used/expiry times, plus the owning member's MFA status. Never returns another key's row (scoped via `AccessKeyAuthentication.getKeyId()`). |

### Design notes

- `get_oauth2_configuration` is a **discovery tool** — no auth required, no data at risk. It tells the MCP client where to point the browser for an interactive PKCE handshake.
- `get_oauth2_session_status` is **read-only introspection** — lets an external app check the access-key's own remaining lifetime and what scopes it holds, useful for rotating keys or handling expiry gracefully.
- **Not built: `request_oauth2_token`** — the authorization server is Spring's stock `OAuth2AuthorizationServerConfigurer` with one public client for the native iOS app (PKCE-only, no secret). The token endpoint expects a code from a browser-completed PKCE flow; an MCP caller (authenticated by access-key) has no code to provide and no legitimate path to mint a token. If a future use case needs it (e.g. external OAuth2 client minting tokens via Picsou), that's a separate client registration + grant-type design, not an MCP wrapper.

## Budget Tools

36 tools across 11 scopes covering categories, rules, categorized transactions, recurring series, envelopes, spending/cashflow, and dashboard aggregates.

### Read surface (6 scopes, 17 tools)

| Scope | Tools | What they do |
|-------|-------|------------|
| `budget:categories-read` | `list_budget_categories`, `get_budget_category` | All member categories (seeded defaults + user-created). Returned with id, name, slug, color, parent_id. |
| `budget:rules-read` | `list_budget_rules`, `get_budget_rule` | All member rules (USER and learned AUTO kinds). Never BRAND rows — the merchant KB is read-only knowledge. |
| `budget:transactions-read` | `list_budget_transactions`, `get_spending_category_detail`, `preview_budget_rule` | Transactions filtered by optional category_id, date range, or uncategorized-only flag. Includes merchant_label, merchant_brand_id, category, and review status. `get_spending_category_detail` mirrors `GET /api/spending/category/{categoryId}`: total, per-sub-category split and the transaction rows of the category subtree, for a `CYCLE` or `YTD` period. `preview_budget_rule` mirrors `POST /api/categorization-rules/preview`: a dry run of a rule before `create_budget_rule`, returning the match count and up to 200 matching transactions, nothing written. |
| `budget:recurring-read` | `list_recurring_series`, `get_recurring_series`, `get_recurring_activity`, `get_recurring_calendar` | Recurring series (detected or declared): amount, cadence, last seen, confidence, runtime status. `get_recurring_activity` is the 60-day "what changed" feed (silent auto-confirms and price steps); `get_recurring_calendar` projects confirmed series over `horizonDays` (default 60). It adds credit-card payments due only when the key also holds `accounts:read`. |
| `budget:envelopes-read` | `list_budget_envelopes`, `get_budget_envelope` | Spending envelopes + their allocations. A single envelope can be queried with its full subtree rollup (children's balances). |
| `budget:dashboard-read` | `get_budget_dashboard`, `get_spending_by_category`, `get_cashflow`, `get_cashflow_flow` | Composed dashboard (same data as the Budget Overview page): hero "left to spend", mini Sankey flow, top-5 categories by spend, and 30-day upcoming subscriptions. The three others mirror `GET /api/spending/by-category` and `/api/cashflow{,/flow}` and return aggregates only: `period` is `CYCLE` (default) or `YTD`, `anchor` defaults to today. |

### Write surface (5 scopes, 19 tools)

| Scope | Tools | What they do |
|-------|-------|------------|
| `budget:categories-write` | `create_budget_category`, `update_budget_category`, `delete_budget_category` | Create custom categories, edit name/parent/color, delete. |
| `budget:rules-write` | `create_budget_rule`, `update_budget_rule`, `delete_budget_rule`, `apply_rule_to_transactions` | Manage USER rules (pattern + priority + target category). `apply_rule_to_transactions` re-runs the entire categorization pipeline over all uncategorized transactions (respects USER > AUTO > brand KB precedence). |
| `budget:transactions-write` | `update_budget_transaction` | Manually categorize a transaction or mark it as reviewed. |
| `budget:recurring-write` | `confirm_recurring_series`, `ignore_recurring_series`, `undo_recurring_series_change`, `create_recurring_series`, `update_recurring_series`, `delete_recurring_series`, `detect_recurring_series` | Triage what the detector found, declare a series it missed, correct or delete one, and re-run detection. Each tool returns the updated series (delete and detect return a summary). |
| `budget:envelopes-write` | `create_budget_envelope`, `update_budget_envelope`, `delete_budget_envelope`, `set_envelope_allocation` | Create/edit/delete spending envelopes; set money limit. Envelopes form a tree; operations on a node affect only that node, not its subtree. |

### Design notes

- **Member isolation** — all tools read/write only the calling access-key's member-scoped data (via `UserContext.currentMemberId()`), same as Account/Transaction/Goal tools.
- **No merchant KB mutation** — the offline brand knowledge base is read-only. The categorization pipeline consults it as a fallback after user rules + learned rules; external apps cannot write to it.
- **`apply_rule_to_transactions` re-runs the pipeline** — it does not selectively apply one rule to one date range. The service method `CategorizationService.recategorizeUncategorized(member)` re-categorizes everything still uncategorized, respecting the full precedence (USER > AUTO > brand KB). This is the same re-run logic used internally when a new rule is created.
- **Envelopes are trees** — a parent envelope's balance is a subtree rollup; deleting a parent reparents its children. Queries return full parent + child hierarchy on demand.
- **Recurring triage keeps the REST semantics** — every tool delegates to the service behind `/api/recurring`:
  - `confirm` and `ignore` are idempotent status sets. `ignore` keeps the row as `IGNORED`, so detection never resurrects it; `delete` removes it, so detection may suggest it again.
  - `undo_recurring_series_change` is context-aware, like `POST /{id}/undo`. A price step is acknowledged (new amount kept, alert cleared). A silent auto-confirm is rejected (series goes to `IGNORED`). A series with neither is returned unchanged.
  - `create_recurring_series` declares a series that starts `CONFIRMED`. `update_recurring_series` replaces every editable field (omitted optional fields are cleared) and leaves the status alone.
  - A series or category id belonging to another member is not found (`findByIdAndMemberId`) and nothing is written.
- **Bean validation is applied in the tool** — tool arguments bypass the controllers' `@Valid`, so `create_recurring_series`, `update_recurring_series` and `preview_budget_rule` validate the same request records (`RecurringSeriesRequest`, `RulePreviewRequest`) with the application `Validator` and fail with the violated fields before any service call.
- **Detection is bounded, not throttled** — `detect_recurring_series` scans only the last 400 days (`RecurringDetectionService.LOOKBACK_DAYS`) and is idempotent: re-running refreshes amounts and dates, links transactions, may auto-confirm a high-confidence `SUGGESTED` series, and never flips a `CONFIRMED` or `IGNORED` decision. There is no cooldown or rate limit on it, neither over REST nor over MCP; detection also runs after each sync.
- **A read scope returns nothing its description does not promise** — transaction rows need `budget:transactions-read`, card-account data needs `accounts:read`. `ScopeEnforcementAspect` checks exactly one scope per tool. `get_spending_category_detail` is mostly rows, so `budget:transactions-read` gates the whole tool. `preview_budget_rule` is gated the same way. Even its match count leaks content, because a `KEYWORD` pattern is a case-insensitive substring match over counterparty, description and merchant label: probing `a`, `ca`, `cas`… would rebuild transaction text one character at a time. When a tool is still useful without the extra data, it checks the second scope with `ScopeEnforcementAspect.isGranted` and leaves that data out. `get_recurring_calendar` drops the credit-card entries without `accounts:read`. Pre-existing and unchanged: `get_budget_dashboard` still lists card payments in its 30-day upcoming list under `budget:dashboard-read`.
- **Rule preview is a pure dry run** — `CategorizationService.previewRule` runs in a read-only transaction, builds an unsaved synthetic rule, and only reads `TransactionRepository.findChangeable`. No rule is persisted and no transaction changes.
- **Not built: AI categorization** — `POST /api/transactions/categorize-ai` spends the instance's AI provider budget and is already asynchronous with its own status endpoint; exposing it needs its own decision.
- **Calendar horizon mirrors REST** — `horizonDays` has no bound on `GET /api/recurring/calendar` either; the projection is capped at 400 occurrences per series.
- **Dashboard composition** — `get_budget_dashboard` assembles data from `CashflowService.compute()` (mini Sankey), `CashflowFlowService.spendingByCategory()` (top-5 categories), and `RecurringSeriesService.upcoming(30)` (subscriptions). No single backend endpoint backs it; both the tool and the UI compose the same pieces.

## Scopes (13 total)

OAuth2:
- `oauth2:discover` — read configuration
- `oauth2:session-status` — read this access-key's metadata

Budget:
- `budget:categories-read`, `budget:categories-write`
- `budget:rules-read`, `budget:rules-write`
- `budget:transactions-read`, `budget:transactions-write`
- `budget:recurring-read`, `budget:recurring-write`
- `budget:envelopes-read`, `budget:envelopes-write`
- `budget:dashboard-read`

No scope exists for: connecting new bank/broker/exchange/wallet accounts, changing MFA settings, member management, GDPR data export, or modifying admin settings. Those flows stay behind the cookie-authenticated HTTP API.

## Tests

Backend (`mvn test`):
- `OAuth2ToolsTest` (4) — configuration discovery, session status (own key only), denial cases.
- `BudgetToolsTest` (30) — categories (CRUD + not-found), rules (CRUD + application), transactions (list + categorize), recurring (list/get), envelopes (tree + CRUD), dashboard composition, spending/cashflow period and anchor defaults.
- `BudgetToolsRecurringTriageTest` (40) — the new tools driven through the real recurring, categorization and cashflow services over mocked repositories: undo (price step vs. auto-confirm), ignore not resurrected by detection, other-member series/category not found and never modified, bean-validation errors, preview writes nothing, and each new tool refused through the real `ScopeEnforcementAspect` without its exact scope. A `budget:dashboard-read`-only key cannot read the spending detail. A `budget:rules-read`-only key cannot preview a rule; with `budget:transactions-read` the preview returns the count and the rows. The calendar lists card payments only with `accounts:read`.
- `OAuth2AndBudgetScopeDenialTest` (13 parameterized) — scope enforcement denies each tool when its required scope is absent.
- `McpToolCatalogTest` — wiring verification (exact tool count), no leaked auth/credential/admin tools.

No regression: all existing Account/Transaction/Goal/Insight/Sync tools pass their tests unchanged.

## Implementation notes

- **OAuth2Tools** (`@Component("oauth2Tools")`) and **BudgetTools** (`@Component("budgetTools")`) are separate components, wired into `McpToolConfig`'s single `ToolCallbackProvider` bean.
- **Member isolation is implicit** — all tool methods receive `AccessKeyAuthentication` as the current principal, so `UserContext.currentMemberId()` returns the key owner's member; no additional checks needed.
- **Scope enforcement is aspect-based** — `@RequiresScope(...)` annotations on tool methods are intercepted by `ScopeEnforcementAspect`; missing scopes raise `MissingScopeException` (clean error, never returns null or partial data).
- **Service layer is unchanged** — all tools delegate to existing `BudgetService`, `CategoryService`, `RuleService`, `CashflowService`, `RecurringSeriesService` etc. No refactoring of the services themselves.

## Use cases

**External AI apps via MCP:**
- "Analyze my spending for the last 30 days" → `get_budget_dashboard` + `list_budget_categories`.
- "What subscriptions are coming up?" → `get_budget_dashboard` (30-day upcoming) or `get_recurring_calendar`.
- "Review the subscriptions you found" → `list_recurring_series(status=SUGGESTED)` + `confirm_recurring_series` / `ignore_recurring_series`.
- "Add a rule for my supermarket" → `preview_budget_rule` first, then `create_budget_rule`.
- "Auto-categorize my uncategorized transactions" → `list_budget_transactions(uncategorizedOnly=true)` + `apply_rule_to_transactions`.
- "Create a new spending envelope for groceries and set a $400 limit" → `create_budget_envelope` + `set_envelope_allocation`.
- "Check if my access-key is about to expire" → `get_oauth2_session_status`.

**claude.ai integration:**
After adding an MCP client connection in claude.ai Settings pointing to `https://<picsou-host>/mcp` with an access-key (scopes: `budget:*-read`, `oauth2:discover`), claude.ai can read-only analyze the user's spending without requiring additional authentication steps.

## Links

- Brief: `docs/briefs/oauth2-budget-mcp.md`
- Related: [MCP server + scoped access-keys](./mcp-server.md), [Budget & Cashflow](./budget.md), [Budget categorization rules](./budget-rules.md)
- ADR: [Access-key auth + embedded MCP server](../decisions/2026-06-05-access-key-auth-and-embedded-mcp.md)
