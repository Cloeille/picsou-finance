# Feature: Recurring investment plans and the wealth projection

> Last updated: 2026-08-19

## Context

Goals modelled one shape: an amount by a deadline. That cannot express "300 € into the PEA every
month" — a recurrence with no target and no end — which is exactly what a wealth projection is
built from. `Goal` gains a `type`, and the Analysis page gains a curve.

The same migration drops `chk_goal_deadline`, a constraint that had been silently breaking edits
to expired goals since V2. See **The bug this fixes** below.

## How it works

### One entity, two shapes

`GoalType` is `SAVINGS_TARGET` (everything Picsou had) or `RECURRING_INVESTMENT`.

They share one table because they share everything around them: member scoping, the M:N link to
accounts, the contributor breakdown, the GDPR export and four MCP tools. Two tables would have
duplicated all of that to avoid two nullable columns.

| | `SAVINGS_TARGET` | `RECURRING_INVESTMENT` |
|---|---|---|
| `target_amount`, `deadline` | required | null |
| `monthly_amount` | null | required |
| `expected_return`, `start_date`, `end_date` | null | optional |
| accounts | one or more | exactly one |

Per-type integrity is a CHECK, not just nullable columns: dropping the NOT NULLs alone would let
a savings target be created with no target at all.

### Compatibility, deliberately

Every payload written before this field existed — the frontend's and four MCP tools' — omits
`type`. Three things keep them working untouched:

- the column has `DEFAULT 'SAVINGS_TARGET'`;
- the entity field has `@Builder.Default`;
- `GoalRequest`'s **compact constructor** defaults a null `type`.

The four existing MCP tools keep their exact signatures — agents have prompts written against
them. A fifth, `create_recurring_investment`, is added beside them.
(`McpToolCatalogTest` pins the registered set, so adding a tool is a deliberate act.)

### Validation on a record

A record cannot validate a field conditionally on another. Two DTOs behind two endpoints would
fork the controller, `goalsApi`, four MCP tools and the demo handler table; validation groups
cannot select themselves from the payload's own content without Hibernate-specific machinery
that does not work on records. So: one record with `@AssertTrue` methods.

**The 422 body keys those under derived property names** — `savingsTargetComplete`,
`recurringComplete`, `recurringSingleAccount`, `dateRangeOrdered` — not under a field name.
Cross-field rules have no single field to attach to. Renaming a method is a breaking change for
the form that maps messages off those keys.

### Branching

`toProgressResponse` branches at the top, because it runs for every goal on the Goals page **and**
from `DashboardService`, where `target.subtract(currentTotal)` on a null target is a 500.

Every path that assumes a deadline — the monthly calendar, both backfills, both override setters
and both deleters — goes through `requireSavingsTarget` and answers 400. An unguarded one would
be a 500 somewhere deeper.

`DashboardService` filters recurring plans out of `goalSummaries`: that card is built entirely
around a completion percentage.

### The projection

`ProjectionService`, separate from `GoalService` — that class is about progress against targets,
is already 450+ lines, and has no business knowing what the portfolio is worth.

**Base**: accounts whose `WealthTier` is `EQUITY`, `CRYPTO` or `SAFETY_NET`, valued and
share-weighted once. Property, loans and alternatives are out: a house does not compound at 7.5%
a year, and including it would inflate every scenario by whatever it is worth. The base is
exposed so the screen states what it is projecting from.

```
r_m = (1 + r_a)^(1/12) − 1
C_m = Σ monthly_amount of the plans active in month m
V_m = V_{m−1} · (1 + r_m) + C_m          ← contribution at month END
```

Two defences worth keeping:

- **Geometric monthly rate, never `r_a / 12`.** Dividing by twelve compounds to more than the
  rate on the label — 10% split that way reaches 10.47% over a year — so a line labelled "10 %"
  would not be one. `Math.pow` on the *rate* is the one place a `double` is acceptable: it is a
  pure ratio, and every amount stays `BigDecimal`.
- **Contributions at the end of the month.** At the start, the very first payment earns a month
  of growth it never saw, and the error compounds across the whole horizon.

**A rate per tier, and a rate per plan.** Cash compounds at 2 %, equity and crypto at 7.5 %,
property and alternatives at **zero** — a statement rather than an omission, since Picsou does not
forecast house prices. A plan is credited to the tier of the account it funds, at its own
`expected_return` when one was given.

That last point reverses an earlier decision, and the reason is worth keeping. The four scenarios
used to be absolute rates — 2 / 5 / 7.5 / 10 % applied to everything, cash included — and the
per-plan rate was deliberately ignored because folding it into a line labelled "5 %" would have
made the label false. The label was *already* false: a member with 250 €/month going to a Livret A
had it compounded at 7.5 % on the "realistic" curve, having typed 1.7 % into the form themselves.

**The scenarios are now spreads on risky assets**, `-2.5 / -1 / 0 / +2.5` points on equity and
crypto only — a passbook does not have a good year. Each carries the **blended rate it actually
works out to**, which varies per member: the same optimistic curve is 10 % for someone fully
invested and 6 % for someone half in cash. Expressed that way, both the per-plan rate and an
honest label are possible.

**One pot per source, not one total per tier.** The rate above only survives because the money is
kept apart: the projection's state is a pot for each tier's *existing* holdings plus a pot for
each *plan*, every pot compounding at its own rate, and the mix summing the pots of a tier.

The obvious shape — one running total per tier — was the shape it had, and it silently discarded
the very rate it had just read. Once 250 € landed in `SAFETY_NET` it was indistinguishable from
the cushion already there, so it grew at the tier's 2.0 % and never at the member's stated 1.7 %.
`expected_return` reached the label and nothing else; a commit message here once claimed
otherwise, and it was wrong.

**A stated rate on cash is contractual; on equity or crypto it is an expectation.** The scenario
spread lands on risky tiers only, which settles both cases with no extra field — the tier already
says which kind of number it is. A Livret A at 1.7 % is the same on all four curves; a PEA at 8 %
runs 5.5 → 10.5. For the member's own four plans, three of which state no rate at all:

| Plan | Tier | Base rate | Pessimistic | Cautious | Reference | Optimistic |
|---|---|---|---|---|---|---|
| PEA | Equity | 7.5 % (default) | 5.0 | 6.5 | 7.5 | 10.0 |
| CTO | Equity | 7.5 % (default) | 5.0 | 6.5 | 7.5 | 10.0 |
| Crypto | Crypto | 7.5 % (default) | 5.0 | 6.5 | 7.5 | 10.0 |
| Livret A | Safety net | **1.7 % (stated)** | 1.7 | 1.7 | 1.7 | 1.7 |

That is what the profiles are *for*: they carry the uncertainty of the contributions whose return
nobody knows, and leave alone the one that is known.

The reported blended rate is weighted by **capital in** — today's holdings plus everything each
plan pays in over the horizon — not by the closing balances, which would let the fastest-growing
pot be the loudest voice describing its own growth.

The starting split comes from `WealthPyramidService`, not from account types, so the two panels of
one screen cannot disagree about the same euro — and current-account money, which the pyramid
carves off as this month's spending, is no longer compounded for forty years.

Contributions are **share-weighted like the base**: a plan funding a half-owned joint account used
to add its whole amount on top of a base that counted half the account.

The maths is monthly, the points are yearly: 480 points × 4 lines is a large payload for a chart
that cannot render them distinctly anyway.

### Where the mix is heading

The scenarios answer *how much*. `allocation[]` answers *in what* — every tier projected forward
against the member's own targets, under the reference scenario only. One scenario deliberately:
four sets of shares would be four ways to read the same qualitative answer, and what moves that
answer is where the money goes, not the return assumption.

This is the join the two panels never had. The pyramid knew today's gap, the curve knew tomorrow's
total, and neither could say whether the plans close the gap or widen it. A tier no plan funds
stays at zero however long the horizon — which is the observation worth acting on.

### The monthly split

A plan said *when* and *where* — 400 € a month into the PEA — and nothing about *into what*. The
amount is a standing order; the split is a decision, and it is the half a member revisits.
`goal_allocation` holds one row per line: `(goal_id, ticker, monthly_amount)`.

**Keyed on the ticker, not on an `account_holding` id.** The sync paths delete and rebuild holding
rows, so a foreign key there would evaporate on the first transient gap and take the split with
it — the same reasoning as `holding_classification` in V83.

**Only positions the account already holds.** `GoalService.replaceAllocations` reads the funded
account's holdings and answers 400 for anything else, and the form offers that list rather than a
text field. A plan describes money going into a position that exists; a wish list would need a
price, a currency and a name from nowhere.

**A partial split is legal.** The remainder reads as *unallocated*, on the card and in the form.
Requiring the lines to sum exactly would mean nobody details a plan they have not finished
thinking about. Over-allocating is not legal, and the form disables Save before the 422 has to
say it.

The sum rule is **not** a CHECK: it spans several rows and the parent table, which a row-level
constraint cannot see. It is `GoalRequest.isAllocationWithinMonthlyAmount`, beside the existing
cross-field rules — and it returns `true` on a null `monthlyAmount` so a half-filled form reports
*"a recurring investment needs a monthly amount"* rather than complaining about a total nobody
has typed yet.

`Goal.allocations` is mapped with `orphanRemoval`, which makes the list's **identity**
load-bearing: `setAllocations(new ArrayList<>(…))` makes Hibernate throw *"a collection with
cascade=all-delete-orphan was no longer referenced"* instead of deleting the rows that went away.
`replaceAllocations` mutates in place — `clear()` then `add()` — and is the only place that does.

The split is **descriptive**: `ProjectionService` still reasons about `monthlyAmount` alone. Giving
each line its own expected return is the obvious next step and a much larger one; nothing here
depends on it.

### The savings rate

The Goals page divides what the running plans pay in every month by the **net** monthly income
from [the member profile](./member-profile.md), and compares the result to the **17.5 %** French
household average (`FRENCH_HOUSEHOLD_SAVINGS_RATE` in `lib/constants.ts`).

Net, not gross, and this is the part worth reading twice: the "brut" in INSEE's *revenu
disponible brut* means gross of capital consumption, **not** gross of tax — RDB is measured after
compulsory levies. Dividing by a gross salary would understate the rate by roughly a quarter
against a benchmark computed on net. The profile therefore asks for two payslip lines rather than
one salary; see [member-profile.md](./member-profile.md).

Three things the figure is careful about:

- **Only the plans running this month count.** A plan starting next year or ended last one stays
  on the page — it is a record the member keeps — but it is not money going out today.
- **The sum is not ownership-weighted**, unlike `ProjectionService.monthlyInflowEur`. It sits
  directly above the plan cards, which each print their raw `monthlyAmount`; two numbers
  disagreeing on one screen is worse than being approximate about a joint account.
- **No income, no rate.** `monthlyNetIncome` is null until *both* the net-before-tax figure and
  the withholding rate are stated; the card then asks for them and links to Settings rather than
  inventing a denominator — the same principle as the allocation-targets form, which never guesses
  an expense on the member's behalf.

The card states the benchmark **in the tooltip only** and passes no verdict of its own. A
coloured "above average" chip overstated how comparable the two figures are; the reader can place
their own number against the one the tooltip quotes.

The two rates are still not the same quantity — disposable income is household-wide and includes
benefits and property income, and standing orders are not all of saving — so the tooltip quotes
the definition rather than presenting the comparison as arithmetic. It answers "more or less than
people around me", which is the question being asked.

### Reading a point on the curve

Both projection tabs label a hovered point with `useProjectionDateLabel`
(`features/analysis/hooks.ts`), which answers two separate complaints about a date forty years
out:

- **It follows the app's date-format setting.** The tooltips printed the payload's raw
  `yyyy-MM-dd`, so a member who had chosen `DD-MM-YYYY` in Settings read `2042-08-31` here and
  `31-08-2042` everywhere else in Picsou.
- **It carries the age the member will be**, when a birth date is stated. A year is an
  abstraction; "at 58" is the thing actually being decided about.
  `MemberProfileResponse.age` is today's age and no use here — this is the age *on that point's
  date*, so it is computed per point on the client with `ageAt` (`lib/utils.ts`). A point falling
  before that year's birthday reports the lower age, which is why it is not a subtraction of
  years.

The label lives in the feature's hooks rather than beside either chart so the two tabs of one
card cannot drift apart on how a date is written — they had already drifted once, since only one
of them went through a formatter at all.

### Reading the chart

Every legend entry is a toggle for its curve, contributions included: five series is more than a
320 px chart separates, and comparing two of them means removing the other three.

**The Y axis is pinned to the full set of series, not to the visible ones.** Letting it breathe
when a curve is hidden rescales every remaining curve, so hiding the optimistic line appears to
move the pessimistic one — the opposite of what the reader clicked for. The maximum is rounded up
to a 1 / 2 / 2.5 / 5 × 10ⁿ figure so the ticks stay readable.

### Key files

- `backend/src/main/java/com/picsou/model/GoalType.java`, `Goal.java`
- `backend/src/main/java/com/picsou/dto/GoalRequest.java` — the compact constructor and the `@AssertTrue` rules
- `backend/src/main/java/com/picsou/service/GoalService.java` — `toRecurringResponse`, `requireSavingsTarget`
- `backend/src/main/java/com/picsou/service/ProjectionService.java`
- `backend/src/main/java/com/picsou/model/GoalAllocation.java`
- `backend/src/main/resources/db/migration/V85__goal_recurring_investment.sql`,
  `V89__goal_allocation.sql`
- `frontend/src/pages/goals/GoalsPage.tsx` — two sections, two form shapes, `RecurringPlanCard`
- `frontend/src/pages/goals/AllocationPicker.tsx` — the split editor
- `frontend/src/pages/goals/SavingsRateCard.tsx`, `plan-math.ts` — the savings rate and its
  pure helpers
- `frontend/src/pages/analysis/ProjectionSection.tsx`,
  `AllocationTrajectory.tsx` — the two tabs, both labelled by `useProjectionDateLabel`

## The bug this fixes

V2 shipped `CONSTRAINT chk_goal_deadline CHECK (deadline > CURRENT_DATE)`.

`CURRENT_DATE` is **not immutable**, so PostgreSQL re-evaluates that constraint on every UPDATE of
the row. Any `save()` on a goal whose deadline had passed therefore failed at the database —
breaking `update`, `extendHistory`, `extendHistoryByMonth`, `setMonthOverride` and
`setManualContribution` for exactly the goals a user is most likely to revisit: the ones that have
come due.

It was broken on `main` long before this feature. V85 drops it; `@Future` on
`GoalRequest.deadline` keeps the rule where it means what the user meant — at creation — instead
of forbidding every later edit.

`V85GoalTypeMigrationTest` proves both halves: the UPDATE fails before the migration and succeeds
after.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| One entity with a discriminator | Both shapes share member scoping, accounts, contributors, export and MCP | A separate `investment_plan` table duplicating all of it |
| Nullable columns **and** a per-type CHECK | Without the CHECK a savings target could be created with no target | Dropping the NOT NULLs alone |
| Compact constructor defaults the type | Keeps four MCP tools and every existing client working untouched | Requiring `type`, and updating every caller |
| `@AssertTrue` on the record | One endpoint, one DTO; groups cannot self-select from the payload | Two DTOs and two endpoints |
| A new MCP tool, existing four untouched | Agent prompts are written against those signatures | Adding parameters to `create_goal` |
| Geometric monthly rate | `r/12` compounds to 10.47% on a line labelled 10% | Arithmetic division |
| Contribution at month end | Month start gives the first payment growth it never earned | Beginning-of-month |
| Scenarios are spreads on risky assets | Lets a per-plan rate coexist with an honest label | Absolute rates applied to cash as well |
| One pot per plan, not one total per tier | A stated rate cannot survive being merged into a tier total | Reading `expected_return` and losing it on arrival |
| A stated cash rate ignores the spread | A Livret A does not have a good year | A flag on the goal saying "this rate is certain" |
| The blend is weighted by capital in | Closing balances let the fastest pot describe its own growth | Weighting by the horizon's values |
| Investable base only | A house does not compound at an equity rate | Projecting total net worth |
| Yearly points from monthly maths | 480 × 4 points a chart cannot draw distinctly | Monthly points |
| `monthsLeft`/`isOnTrack` stay primitives | Boxing them would ripple a nullable through every savings-goal call site to say what `type` already says | Making them nullable |
| The split is keyed on the ticker | A FK to `account_holding` evaporates on the first sync gap | `account_holding_id` on the row |
| Only positions already held | A wish list has no price, currency or name to show | Free-text tickers |
| A partial split is legal | Nobody details a plan they have not finished thinking about | Requiring the lines to sum exactly |
| `allocations` is always a list | An omitted key is `undefined` on the client, and it gets mapped over | Following `non_null` like the rest of the record |
| The split is descriptive | Per-line returns are a much larger change, and nothing here needs it | Feeding it into `ProjectionService` |
| The savings rate is computed client-side | Both operands are already on the page; a server figure would be a third source of the same number | An endpoint returning the rate |

## Gotchas / Pitfalls

- **The 422 keys are derived property names**, not field names. See above.
- **`monthsLeft` is `0` and `isOnTrack` is `true` for a recurring plan.** They are primitives and
  cannot be dropped from the JSON. They are meaningless for that type — check `type` before
  rendering either.
- **`GoalProgressBar` returns null for a recurring plan.** A progress bar towards no target would
  be inventing a completion percentage.
- **The monthly calendar refuses a recurring plan with a 400.** The frontend never links to it
  from a recurring card, but the guard is the backend's.
- **`Goal.builder()` in a test now needs `.type(...)`** only when it should be recurring; the
  `@Builder.Default` covers everything else, which is what keeps the 20 pre-existing
  `GoalServiceTest` cases untouched.
- **`GoalsExporter`'s CSV header and rows are positional.** They gained the same five columns in
  the same order; a mismatch silently misaligns every consumer's file.
- **Do not re-stub `accessResolver.sharesFor` inside a `GoalServiceTest` case.**
  `when(mock.method(...))` *invokes* the mock, which runs the class-level `Answer` with null
  arguments and NPEs. The `@BeforeEach` stub already answers correctly.
- **A nullable field is omitted from the JSON, not sent as null.** `spring.jackson`'s
  `default-property-inclusion: non_null` means `targetPercent`, `expectedReturn`, `coverage` and
  friends arrive as `undefined`, so `=== null` is false and `.toFixed()` throws. Compare with
  `== null`, and write fixtures without the key rather than with an explicit `null` — see
  [`docs/conventions/api-rest.md`](../conventions/api-rest.md).
- **`allocations` is always an array, deliberately against `non_null`.** Empty for a savings
  target and for an undetailed plan, never omitted — the client maps over it, and an omitted key
  arrives as `undefined`. This is the one field in `GoalProgressResponse` that does not follow the
  rule in [`api-rest.md`](../conventions/api-rest.md), and it is on purpose.
- **Never `setAllocations(new ArrayList<>(…))`.** `orphanRemoval` makes the collection's identity
  load-bearing; replacing it throws instead of deleting. `clear()` + `add()`.
- **Changing the funded account clears the split.** `toggleAccount` does it in the form, because
  the tickers name positions of the old account and the backend would answer 400 for every one.
- **The three new 422 keys are derived property names too**: `allocationOnlyOnRecurring`,
  `allocationWithinMonthlyAmount`, `allocationTickersUnique`.
- **`ALTER TABLE ... ADD CONSTRAINT` revalidates existing rows.** The migration test has to add
  the old constraint back as `NOT VALID` to reproduce production, where the row aged past its
  deadline while the constraint sat there unvalidated.

## Tests

- `GoalServiceTest` — the 20 savings-target cases unchanged, plus: a recurring plan reports its
  plan without NPE, the calendar/backfill/overrides refuse it, `create` persists the new fields
- `GoalRequestTest` — an omitted type means `SAVINGS_TARGET`, each `@AssertTrue`, a past deadline
  still refused at creation
- `ProjectionServiceTest` (22) — investable base only, **10% over twelve months lands on ×1.10,
  not ×1.1047**, contributions at month end, plan windows, shares once, four ordered scenarios,
  horizon clamped, empty portfolio flat. And on the rates: **a stated rate produces a different
  total from no stated rate** (the test the old suite lacked — it only checked that a passbook was
  not compounded at an equity rate, which stayed true while the member's figure was ignored), a
  stated cash rate is identical across all four scenarios, a stated equity rate is not, and the
  stated rate survives into the allocation trajectory as well
- `V85GoalTypeMigrationTest` — the UPDATE fails before the migration and succeeds after; a
  recurring row needs no target; a savings target still cannot be created without one
- `McpToolCatalogTest`, `GoalToolsTest` — the fifth tool registered, the four unchanged
- `GoalRequestTest` also covers the split: a partial one is valid, an exact one is valid, an
  over-allocation and a duplicate ticker are not, a savings target cannot carry one, a blank
  ticker does not also read as a duplicate, and a missing monthly amount reports itself first
- `GoalServiceTest` — the split persists and is named from the account's holdings, an unheld
  ticker is a 400 with nothing saved, an edit replaces the lines **without swapping the list**,
  clearing the split leaves the holdings table untouched
- `AllocationPicker.test.tsx` — the account's own holdings are what is offered, ticking adds and
  removes a line, the remainder and the over-allocation messages, the two empty states
- `SavingsRateCard.test.tsx` — the percentage, the benchmark stated once in the tooltip with no
  verdict on the card, only the plans running
  this month counted, the ask-for-income state instead of a computed-from-nothing rate, and
  nothing rendered when no plan is paying in
- `features/analysis/hooks.test.tsx` — the projected date follows the format setting, carries the
  age on that date, and falls back to the date alone with no birth date or before the profile
  loads; `lib/utils.test.ts` covers `ageAt` itself (completed years, the birthday turning over,
  timezone independence, malformed input, a negative age)
- `ProjectionSection.test.tsx` — rates from the payload, legend in payload order, base stated,
  empty state, the legend toggles hide and restore a series, and **the mix renders when the API
  omits a null `targetPercent`** rather than sending it; `e2e/goals.spec.ts` — the two sections
  and the form switch
- `PyramidSection.test.tsx` — the same omission on `coverage`, `global` and `allocation` reads as
  "not rated" instead of `NaN` and `undefined / 100`

## Links

- Related: [Savings goals](./goals.md) — the shape that already existed
- Related: [Wealth pyramid](./wealth-pyramid.md) — where `WealthTier` comes from
- Related: [Member profile](./member-profile.md) — where the savings rate's income comes from
