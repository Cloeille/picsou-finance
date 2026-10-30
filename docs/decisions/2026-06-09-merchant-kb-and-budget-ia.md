# ADR: Offline merchant knowledge base and nested-route Budget IA

> Date: 2026-06-09
> Status: ✅ Active

## Context

The 1.1.0 Budget foundation (ADR [2026-06-02](./2026-06-02-budget-cycle-and-categorization.md))
shipped a deterministic rule engine with learning, a configurable pay cycle, and the
`CategoryKind` pivot. In practice it was **reactive**: a synced transaction stayed uncategorized
until the user tagged it once and a `USER` rule was learned. The product goal for the redesign is
the opposite — **seamless and zero-config, usable by a grandmother without any setup**: every
transaction categorized automatically, by *brand*, from the very first sync.

That same 2026-06-02 ADR **rejected ML / external categorization** as a heavy, opaque,
privacy-hostile dependency for a self-hosted app. So automatic categorization has to come from an
**embedded, offline** dataset — not a model, not a third-party API.

Two design questions drove this ADR:

1. **How does brand-based auto-categorization integrate without weakening the existing
   precedence** (manual override must always win) or exploding the data model?
2. **How should the Budget UI be organized** now that it spans overview, spending flow, recurring,
   envelopes, allocation, review, and settings — far more than one tabbed page can carry cleanly?

## Decision

1. **Embedded, offline merchant knowledge base.** Two **global** (not member-scoped) tables —
   `merchant_brand` (slug, display name, `default_category_slug`, colour, monogram, logo domain)
   and `merchant_alias` (pattern, `WORD`/`PHRASE` match type) — seeded with 137 common FR/EU
   brands. Loaded once at startup into an **immutable in-memory snapshot** (`MerchantKnowledgeBase`,
   published via a `volatile` reference, hot-reloadable on a per-member `kb_version` bump), matched
   with **zero per-transaction I/O**.

2. **The KB is a *direct fallback*, not stored rules.** `CategorizationService.autoCategorize`
   keeps its exact precedence — `USER rule > learned AUTO rule > brand KB > uncategorized` — by
   running the rule engine *first* and consulting the KB **only** when no rule matched. The KB
   match resolves the brand's `default_category_slug` against the member's own categories
   (`categoriesBySlug`). **No per-member `BRAND` rows are written.** The single invariant
   `categoryRef != null` (already in `apply`) is what protects a user's choice; nothing else is
   needed.

3. **Canonical merchant label, always stamped.** A pure, static `MerchantNormalizer` derives a
   clean `merchant_label` (strip processor wrapper, transaction-type noise, card/reference digits,
   dates) and a normalized `matchKey` (lower-case, accent-free) for KB lookups. `enrich(tx)` stamps
   `merchant_label` + `merchant_brand_id` on **every** transaction, independent of whether a
   category is assigned.

4. **Category tree with stable slugs.** `category` gains `parent_id` (self-FK) and `slug`. The slug
   is the join key between the global KB and a member's private categories; seeded defaults are
   backfilled with slugs, user-created categories have none (so drill routes key on `categoryId`).

5. **Nested-route information architecture.** The single 7-tab Budget page becomes a `BudgetLayout`
   with an `<Outlet/>` over nested routes (`/budget`, `/spending`, `/spending/:categoryId`,
   `/subscriptions`, `/envelopes`, `/review`, `/settings`) — the same pattern as `/setup`. **Review
   is contextual, not a permanent destination**: a banner on the overview, shown only when there
   are items to correct.

6. **Logos are opt-in, off by default.** `budget_settings.logo_fetch_enabled` (default `false`).
   The default rendering is an offline `MerchantAvatar` monogram with a deterministic colour; logo
   fetching is a cosmetic toggle that **never feeds categorization**. When enabled, logos are served
   through a **server-side proxy** (`GET /api/merchants/{id}/logo`) behind a `MerchantLogoPort`,
   implemented by `DuckDuckGoLogoProvider` against DuckDuckGo's keyless icon service, with an
   in-memory TTL cache. See [Logo proxy & integration testing (M5)](#logo-proxy--integration-testing-m5-realization)
   for the provider choice, the no-SSRF argument, and the gating.

## Alternatives considered

### ML / external categorization service

- **Pros**: no rules or seed data to maintain; adapts to any merchant.
- **Cons**: rejected in 2026-06-02 — heavy dependency, opaque, privacy-hostile for self-hosting.
  This ADR **reaffirms and extends** that decision: the offline KB delivers the zero-config payoff
  without any of those costs.

### A `RuleSource.BRAND` rule materialized per member

- **Pros**: uniform code path — brand matches become ordinary low-priority rules.
- **Cons**: writes thousands of near-identical rows across members; couples the global KB to
  per-member state; complicates KB version bumps (which rows to refresh?). A direct in-memory
  fallback achieves identical precedence with zero rows.

### Per-transaction DB lookup of the brand tables

- **Pros**: no in-memory cache; always current.
- **Cons**: a DB round-trip per transaction during a sync batch; the KB is tiny and changes only on
  a version bump, so an immutable startup snapshot is strictly better.

### Member-scoped brand tables

- **Pros**: members could customize brands.
- **Cons**: duplicates the same 137 brands per member for no benefit; brand→category mapping is
  universal, and per-member customization already exists via learned `USER` rules.

### Keeping the single tabbed Budget page

- **Pros**: no routing change.
- **Cons**: seven tabs do not scale to overview + flow + drill + recurring + envelopes + review +
  settings; deep links and per-page lazy loading are awkward; Review wants to be a contextual nudge,
  not a tab.

## Reasoning

The existing engine already had the perfect seam: `apply()` never overrides an assigned category.
Slotting the KB in *after* the rule loop means precedence is preserved **for free** — no new enum
value, no priority arithmetic, no migration of rule rows. Keeping the KB global and in-memory makes
it a tiny, thread-safe, zero-I/O lookup that a version bump can hot-swap. Stamping the canonical
label unconditionally decouples the two payoffs (clean names everywhere vs. the gated category
decision), so even uncategorized transactions read cleanly. The nested IA mirrors a pattern already
proven in `/setup` and lets Review become the contextual surface the product vision wants.

## Trade-offs accepted

- **Seed coverage is finite.** 137 brands cover the bulk of real FR/EU spending, but the long tail
  falls through to the learned-rule path. Acceptable: the user's first manual tag still teaches a
  durable `USER` rule.
- **A KB version bump requires a recategorize pass** to benefit existing transactions
  (`recategorizeUncategorized`, which never overrides a user choice).
- **The KB is read-only at match time but recategorize writes** — the write-on-read transaction
  trap (documented in `budget.md`) applies. It is locked down by a Postgres-level test
  (`BudgetSeedWriteOnReadPostgresTest`); H2 hides it. See the M5 realization section below.
- **Brand→category mapping is to parent categories only.** Sub-categories are user-created; the
  seed stays simple.

## Consequences

- New migrations **V38** (`category.parent_id`/`slug`, `transaction.merchant_label`,
  `budget_settings.kb_version`/`logo_fetch_enabled`), **V39** (`merchant_brand`,
  `merchant_alias`, `transaction.merchant_brand_id`, 137-brand seed), and **V40** (recurring v2).
  They start at V38 because V36/V37 (`transaction.name`, access-keys / MCP) were merged in from the
  1.0.x line above the budget foundation's V33–V35.
- New backend: `MerchantNormalizer` (pure), `MerchantKnowledgeBase` (`@Component`),
  `CashflowFlowService`, `SpendingController`, `MerchantBrand`/`MerchantAlias` models + repos, flow
  DTOs; `CategorizationService` gains `enrich`/`autoCategorize`/brand fallback.
- New frontend: `BudgetLayout` + nested pages, `MerchantAvatar`, `CashflowSankey`, `FlowBars`,
  `flow-utils`.
- `CategorizationRule` and `RuleSource` are **unchanged** — the deliberate consequence of the
  direct-fallback choice.
- Recurring v2 (identity on `merchant_label`, auto-confirm) and the sub-category UI built on this
  foundation in later milestones; the opt-in logo proxy and the Postgres integration test landed in
  M5 (next section).

## Logo proxy & integration testing (M5 realization)

Two M5 decisions are recorded here rather than in a separate ADR, because each is a direct
realization of a choice already made above (logos opt-in; the write-on-read trade-off).

### Why a server-side logo proxy, and why DuckDuckGo

The avatar could in principle `<img src>` a third-party logo CDN directly from the browser. We
**proxy server-side** instead so that enabling logos never leaks the member's IP, `Referer`, or —
by URL pattern — *the list of brands they spend at* to a third party on every page render. The
proxy also lets us cache centrally, enforce the opt-in, and rate-limit.

Provider: **DuckDuckGo's keyless icon service** (`icons.duckduckgo.com/ip3/{domain}.ico}`).

- **Pros**: no API key, no account, no per-call quota to manage in a self-hosted app; the
  privacy-aligned brand for a finance tool; trivial to call.
- **Rejected — Clearbit / Brandfetch**: require an API key and have commercial/rate terms that don't
  fit a zero-config self-hosted deploy.
- **Rejected — Google s2 favicons**: works keyless too, but routes every brand the user spends at
  through Google — the exact privacy leak the proxy exists to prevent.

The choice is cheap to revisit: providers sit behind `MerchantLogoPort`, so swapping one is a single
adapter, with no change to the controller, cache, or frontend.

**No SSRF surface.** The fetched `logoDomain` always originates from the **bundled, seeded
`merchant_brand` table** — never from user input — so the proxy cannot be steered at an attacker
host. The adapter is defensive regardless (5 s timeout, 1 MB cap, every failure → empty). The
controller gates in order: **per-IP rate limit** (no open relay) → **per-member opt-in** (404 when
off, identical to a missing logo so the monogram fallback is seamless) → cache/fetch.

### Why Testcontainers (real Postgres) over H2 for the one integration test

The seed-on-read path must run its INSERT in a *writable* transaction even when reached from a
read-only caller (it escapes via `REQUIRES_NEW`). **H2 silently tolerates an INSERT in a read-only
transaction; PostgreSQL rejects it with SQLSTATE `25006`.** A test on H2 would therefore pass while
production 500s — worse than no test. `BudgetSeedWriteOnReadPostgresTest` is the project's first and
only container-backed test: a `@SpringBootTest` over a `postgres:16-alpine` Testcontainer that
`disabledWithoutDocker = true` makes self-skip on CI/dev machines with no Docker daemon.

- **Rejected — H2 / `@DataJpaTest`**: masks exactly the bug under test (and any other Postgres-only
  semantics), so it gives false confidence here.
- **Rejected — a shared/dev Postgres the test connects to**: stateful, needs provisioning, and
  couples the suite to an external service; a throwaway container is hermetic and parallel-safe.

The matching convention guidance lives in [`docs/conventions/testing.md`](../conventions/testing.md):
Mockito unit tests by default, a real-Postgres container only when DB fidelity is the point.

## Supersedes

None. **Extends** [2026-06-02 Budget cycle, categorization engine, and transfer kind](./2026-06-02-budget-cycle-and-categorization.md)
— same anti-ML stance, now realized as an offline knowledge base.
