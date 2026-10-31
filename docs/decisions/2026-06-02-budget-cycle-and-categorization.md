# ADR: Budget cycle, categorization engine, and transfer kind

> Date: 2026-06-02
> Status: ✅ Active

## Context

The 1.1.0 Budget module needs to turn raw transactions into envelopes, cashflow, recurring
charges, and savings allocation. Three cross-cutting design questions drove the data model:

1. **What is a budget period?** Salaries rarely land on the 1st, so a calendar month splits a
   pay cycle across two budgets.
2. **How are transactions categorized?** Enable Banking sync delivers transactions with no
   category; manual entry should not force the user to tag everything by hand forever.
3. **How are internal transfers handled?** Moving €500 from checking to a savings account is
   neither income nor an expense, yet it is the core signal for savings allocation.

## Decision

1. **Configurable pay cycle.** `budget_settings.cycle_start_day` (SMALLINT, 1–28, default 1).
   `BudgetCycle.cycleFor(date, cycleStartDay)` returns the `[start, end]` range; all
   aggregations (envelopes, cashflow monthly buckets, allocation flux) use it instead of the
   calendar month.
2. **Rule engine + learning.** `categorization_rule` (`match_type` COUNTERPARTY|KEYWORD,
   `pattern`, `category_id`, `priority`, `source` AUTO|USER). Rules run on every synced
   transaction; categorizing a transaction by hand can `learnRule(...)` a `USER` rule that
   outranks `AUTO` rules. Manual override always wins.
3. **`CategoryKind` = INCOME | EXPENSE | TRANSFER.** Transfers are excluded from cashflow and
   envelopes and instead feed the allocation view.

## Alternatives considered

### Calendar-month budgets

- **Pros**: trivial to compute; matches bank statements.
- **Cons**: a pay cycle straddling the month boundary is split across two budgets, so
  "remaining this month" is wrong for anyone not paid on the 1st.

### Manual-only categorization

- **Pros**: no rule engine, no surprises.
- **Cons**: every synced transaction needs a human; unusable at sync volume.

### ML / external categorization service

- **Pros**: no rules to maintain.
- **Cons**: heavy dependency, opaque, privacy-hostile for a self-hosted app, overkill for a
  single household's spending patterns.

### No transfer kind (flat income/expense)

- **Pros**: simpler enum.
- **Cons**: internal transfers pollute cashflow (a savings deposit looks like an expense) and
  there is no clean source for allocation contributions.

## Reasoning

A configurable start day is one column and one pure function, yet it makes every downstream
aggregation correct for real pay schedules. A deterministic rule engine with learning gives
auto-categorization that the user can inspect and correct — the correction *improves* the
engine rather than being thrown away. The three-way kind is the minimal pivot that keeps
transfers out of spend/income math while making them the explicit input to allocation.

## Trade-offs accepted

- `cycleStartDay` is capped at 28 to avoid undefined days (29–31) on short months; a day past
  28 clamps to the month's last day rather than offering true end-of-month anchoring.
- The rule engine is pattern-based, not semantic: a renamed merchant needs a new rule (or a
  re-learn) to match.
- Envelopes have **no rollover** — an under-spent month does not credit the next.

## Consequences

- New tables: `category`, `categorization_rule`, `budget_settings`, `budget`,
  `recurring_series`; `transaction` gains `category_id`, `counterparty`, `recurring_series_id`
  (migrations V33–V35).
- `BudgetCycle` is a shared, unit-tested utility consumed by the envelope, cashflow, and
  allocation services.
- `SyncService` categorizes during ingestion; manual transactions accept a `categoryId`, so the
  whole module functions with zero synced banks.

## Supersedes

None.
