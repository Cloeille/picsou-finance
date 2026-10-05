# Feature: Accounts Overview (PnL chart + summary card + asset type filters)

> Last updated: 2026-08-19

## Context

The Accounts page (`/accounts`) shows a grid of account cards. Users need a visual overview of PnL evolution over time, with the ability to filter by asset type (stocks, savings, crypto, etc.). A summary card at top shows the total balance and PnL for the current filter, similar to the Dashboard hero card.

## How it works

### Account card anatomy

`AccountCard` renders every account to the same five-line shape, so the grid stays on one
rhythm whatever the asset:

| Line | Bank / broker / crypto account | Property (`REAL_ESTATE`) |
|---|---|---|
| Mark | wallet logo key, connector logo, bundled brand asset, or color circle — see [bank-logos.md](./bank-logos.md) | the property-kind glyph on a tint of the account color |
| Name | `name` + type badge + co-ownership share | same |
| Subtitle | `provider` | property kind and city, joined by ` · ` |
| Balance | `currentBalanceEur` (negated and red for a `LOAN`) | same |
| Freshness | `lastSyncedAt`, graded green → yellow → orange → red by age | `realEstate.lastValuedAt`, graded on the monthly scale |

A property is a manual account: it has no `provider` and nothing ever writes its
`lastSyncedAt`, so before this shape it rendered two lines shorter than its neighbours *and*
carried an unrealized-gain line no other type showed. The gain now lives only on the account
detail page and in `RealEstateSummaryCard`; the kind, the city and the valuation date fill
the two slots that were empty.

Every line is still conditional — an account with no provider and no sync date (a manual
`OTHER` account, or a property described but never valued) renders short, as it always has.
Only the property case, where the data existed but was not surfaced, was fixed.

The freshness line is colour-graded by age, so how old a figure is reads at a glance instead
of only crossing one 48h threshold. `freshnessLevel(date, bounds, now)`
(`frontend/src/lib/utils.ts`) buckets the age against a bounds object; `FRESHNESS_TEXT_CLASS`
maps the bucket to a Tailwind pair that stays legible in both themes.

**Two scales, because the two dates are produced on entirely different cadences** — a scale
that cries wolf is one users learn to ignore:

| Level | `lastSyncedAt` (daily jobs) | `lastValuedAt` (monthly job) | Colour |
|---|---|---|---|
| `fresh` | < 24 h | < 35 d | green |
| `recent` | < 48 h | < 60 d | yellow |
| `stale` | < 7 d | < 90 d | orange |
| `old` | beyond | beyond | red |

Both live in `frontend/src/lib/constants.ts` as `SYNC_FRESHNESS_BOUNDS_MS` and
`VALUATION_FRESHNESS_BOUNDS_MS`. The valuation scale is month-shaped because
`SchedulerService.monthlyPropertyValuation` runs on the 1st of each month against sources that
themselves refresh twice a year: a 40-day-old estimate is the system working as designed, and
would be permanently red on the sync scale.

The `TriangleAlert` glyph and its tooltip are **narrower than the colour**: they appear only on
the `stale`/`old` levels *and* only for non-manual accounts. Colour alone carries no meaning for
a colour-blind reader, so the worst levels need a second signal — but the tooltip names a dead
provider session and tells the user to reconnect, which is meaningless for a figure they type in
themselves. A manual account still gets the colour, since age is age.

A missing date is `unknown`, not `old`: never synced is not the same as very stale, and must not
read as an alarm. In this card the line is simply not rendered in that case.

The card re-renders on a 5-minute interval so a tab left open crosses a boundary without a
remount, and `now` is a parameter of `freshnessLevel` rather than a `Date.now()` call inside it,
which keeps the helper pure and its tests free of fake timers.

The glyphs come from `frontend/src/lib/property-icons.ts` (`PROPERTY_KIND_ICONS`), which
`AddPropertyModal`'s kind picker shares so a property looks the same wherever it is shown.
They are keyed on `RealEstateMetadata.propertyKind` — the backend's `PropertyKind.parse` of
the free-text `property_type` column — never on the raw string.

### Sorting the position tables

Every column of the three tables on the account detail page — `HoldingsTable`,
`PositionsByProduct` and `RealizedPnlSection` — is a sort key. `useTableSort`
(`frontend/src/hooks/use-table-sort.ts`) holds the state locally, per the
[UI-filter ADR](../decisions/2026-04-05-component-local-state-for-ui-filters.md), and
`SortableTableHead` wraps the shadcn `TableHead` rather than editing it.

Three rules the comparator carries, each of which was a way to get this wrong:

- **Nulls sink to the bottom in both directions.** A dash means "we could not price this line",
  not zero — the same distinction `PositionsByProduct` already makes when it refuses to publish a
  subtotal over an unpriced row. Ordering null as the smallest value would float the lines we know
  least about to the top of an ascending sort.
- **The sort is stable and never in place.** Rows that tie keep the order the API sent them in, so
  sorting on a column that is empty for every visible row is a no-op rather than a shuffle.
- **Text compares through an `Intl.Collator`.** "Élan" starts at U+00C9, above `Z` in code-point
  order; a raw `<` would file every accented fund name after "Zeta".

The default is **value, descending** — the biggest line first is what the reader is nearly always
after. `PositionsByProduct` keeps **one** sort for its three product sections and applies it inside
each: the SPOT/STAKING/LENDING sequence is editorial, not data, and survives any column picked.

`AccountDetailPage` passes `key={accountId}` to all three, which is what resets the sort when the
reader moves to another account — React Router keeps the same component across a change of `:id`,
so without the key a sort chosen on one portfolio would silently carry over to the next.

### Opening date

`account.opened_at` (V91) records when a wrapper was opened, as the member states it. The account
form offers it for the types whose taxation turns on the plan's age — `PEA` and `ASSURANCE_VIE`,
listed in `OPENING_DATE_TYPES` — and the xlsx export prints it under the account's type.

**It is not `created_at`.** That column dates the Picsou row; a PEA opened in 2014 and typed in
last month has ten years between the two, and the ten years are the point. Nothing backfills it
for the same reason: no existing row can know its own opening date, and deriving one from
`created_at` would put a fabricated fiscal anniversary in an export.

**A null in `AccountRequest.openedAt` means "leave it alone", not "clear it"** — the same rule
`logoKey` carries, and for the same reason: the MCP `update_account` tool has no such parameter
and sends null on every call, so treating it as a clear would wipe the date the first time an
agent renamed the account. The cost is that the form can change the date but not blank it.

The column is deliberately general rather than `pea_opened_at`: a PER, a PEA-PME and an
assurance-vie all have the same shape of rule at different thresholds. Extending the field to
another type is one entry in `OPENING_DATE_TYPES`.

### Summary card

A `Card` at the top of the page shows the total balance for the filtered accounts. When the filter
holds anything with a cost basis distinct from its value, it also displays the aggregate PnL with a
green/red trend icon and percentage, in the same style as the Dashboard net worth card.

That test is `hasPnl`, and it covers two different sources of a basis:

- **an investment account** — `HOLDING_ACCOUNT_TYPES` (PEA, COMPTE_TITRES, CRYPTO,
  EMPLOYEE_SAVINGS, ASSURANCE_VIE), whose basis is the sum of its holdings' cost
- **a property** — `hasMeasurableGain(account)`, i.e. a `REAL_ESTATE` account whose
  `realEstate.costBasis` is positive

Cash-only filters (SAVINGS, CHECKING) are still excluded: their PnL is 0 by construction.

#### Where a property's gain comes from

A property holds nothing, so `AccountService.valuation()` falls back to
`invested = currentBalance` and the snapshot's `pnl` is structurally 0 — the Immobilier filter
showed a total and no gain line for exactly that reason. Its real basis is the purchase price plus
every acquisition fee, already on the wire as `RealEstateMetadataResponse.costBasis`, and already
what `RealEstateSummaryCard` and `PropertyValuationChart` measure against.

`frontend/src/features/accounts/pnl.ts` substitutes it. Two pure helpers, used by the summary card
*and* both branches of the chart so the three cannot drift apart:

| | `accountInvestedAt` | `accountPnlAt` |
|---|---|---|
| not `REAL_ESTATE` | `point.invested` | `point.pnl` |
| property, basis > 0 | `costBasis` | `point.total - costBasis` |
| property, no basis | `point.total` | `0` |

The third row is the one that matters. A property described but never given a purchase price
contributes its own balance to the denominator, so it reports no gain. Leaving it out of the
denominator while its balance stayed in the numerator is the partial-basis mismatch
`AccountService.valuation()`'s javadoc warns about — it would report a gain the size of the whole
property.

This is a **frontend-only** substitution, on purpose. Making `calculateInvestedAmount` return the
cost basis for a property would be the tidier fix, but existing `balance_snapshots` rows carry
`invested = balance` for properties, so the curve would step at the deployment date — and it would
silently move the dashboard's net-worth PnL too.

### PnL line chart

The `AccountsStackedChart` renders a Recharts `LineChart` (not stacked — PnL can be negative). Each line represents one category or account's PnL (`balance - invested`) over time.

Data preparation depends on the active filter:

- **ALL filter**: PnL is aggregated by asset type group (one line per group: STOCKS, CRYPTO, etc.).
- **Specific filter** (e.g. STOCKS): PnL is computed per individual account.

The chart is rendered on the same `hasPnl` test as the summary line, so the Immobilier filter now
draws a curve too: historical balance minus a constant cost basis, over real data — the daily
snapshot job already records the balance `PropertyValuationService` writes each month. For cash-only
filters (SAVINGS, CHECKING) the chart stays hidden, since PnL is always 0.

Both branches of `chartPnlData` group by **account object**, not by id string, because a property's
PnL is only computable from the account itself — that is where its cost basis lives.

### Asset type filters

Six asset categories defined in `AccountsPage.tsx`:

| Filter key | Account types | Chart color |
|-----------|--------------|-------------|
| STOCKS | PEA, COMPTE_TITRES, EMPLOYEE_SAVINGS, ASSURANCE_VIE | `#6366f1` |
| METALS | OTHER | `#eab308` |
| SAVINGS | LEP, LIVRET_A, LDDS, LIVRET_JEUNE, PEL, CEL, SAVINGS | `#22c55e` |
| CHECKING | CHECKING | `#0ea5e9` |
| CRYPTO | CRYPTO | `#f97316` |
| REAL_ESTATE | REAL_ESTATE, SCPI | `#a855f7` |

The filter affects the summary card, chart, and account card grid simultaneously.

### History fetching

`useAllAccountsHistory` fetches `/accounts/{id}/history` for every account in parallel, merges all snapshots into a unified time series, and forward-fills missing values. It returns `{ balances, invested }` — two parallel arrays of `{ date, [accountId]: value }` points. Both are forward-filled independently.

It also injects each account's current balance at today's date if no snapshot exists for today, and carries the latest known `investedAmount` forward.

### Key files

- `frontend/src/components/shared/AccountForm.tsx` — `OPENING_DATE_TYPES` and the date field
- `backend/src/main/resources/db/migration/V91__account_opened_at.sql`
- `frontend/src/pages/accounts/AccountsPage.tsx` — page with summary card, PnL chart, and grid
- `frontend/src/components/shared/AccountCard.tsx` — one card; `AccountAvatar` / `PropertyAvatar`
- `frontend/src/lib/property-icons.ts` — `PROPERTY_KIND_ICONS`, shared with `AddPropertyModal`
- `frontend/src/components/shared/AccountsStackedChart.tsx` — PnL line chart component
- `frontend/src/features/accounts/hooks.ts` — `useAllAccountsHistory` hook (returns `AccountsHistoryData`)
- `frontend/src/demo/index.ts` — mock history data (12 months per account)

### Flow

```
AccountsPage
  ├─ useAccounts()                    → list of all accounts
  ├─ useAllAccountsHistory()          → { balances, invested } merged time series
  │
  ├─ filteredAccounts (useMemo)       → accounts matching current filter
  ├─ hasHoldings                      → true if any filtered account is investment type
  │
  ├─ Summary card (totalBalance + PnL)
  │
  ├─ chartPnlData (useMemo)
  │   ├─ ALL  → aggregate (balance - invested) per type group per date
  │   └─ else → (balance - invested) per individual account per date
  │
  ├─ <AccountsStackedChart> (only if hasHoldings)
  │
  └─ Account card grid
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Virtual `Account` objects for type groups in ALL mode | `AccountsStackedChart` expects `Account[]` — avoids a second component or prop variant | Separate `GroupedChart` component |
| Client-side PnL computation in `useMemo` | PnL = balance - invested; both already available from the hook | Backend PnL endpoint |
| `LineChart` instead of stacked `AreaChart` | PnL can be negative — stacking breaks with negative values | Stacked areas with clamping |
| Separate `balances` / `invested` arrays from hook | Clean separation — chart consumes computed PnL, not raw data | Single array with `inv_` prefixed keys |
| Forward-fill missing balance and invested values | Accounts may not have snapshots for every date; chart needs continuous data | Interpolation (would invent non-real values) |
| Hide chart for cash-only filters | SAVINGS/CHECKING have PnL = 0 — a flat line chart adds no value | Show empty chart with flat zero lines |

## Gotchas / Pitfalls

- **A new `PropertyKind` needs an entry in `PROPERTY_KIND_ICONS`.** The map is typed
  `Record<PropertyKind, LucideIcon>`, so a missing key fails the build rather than silently
  dropping the glyph — but the enum lives in the backend, and adding a value there without
  adding it to `PropertyKind.parse` leaves `propertyKind` null and the card back on the color
  circle.
- **The property glyph tints itself with relative color syntax.** `PropertyAvatar` sets
  `--account-color` inline and derives both the disc and the glyph from it, darkening the
  glyph in light mode and brightening it in dark (see the
  [CSS relative color ADR](../decisions/2026-04-08-css-relative-color-syntax.md)). The
  account palette runs from indigo to yellow; a raw yellow-500 glyph on its own pale tint is
  barely visible in light mode.
- **Never derive an account type's label key from its name.** `accountTypeLabelKey()` in `lib/constants.ts` is the only mapping; a local `type.toLowerCase()` renders the raw key (`accountTypes.livret_a`) for any type whose key isn't simply its lowercased value. See [add-account-modal.md](./add-account-modal.md#account-type-labels).
- **`TYPE_TO_GROUP` must cover every `AccountType`** — if a new type is added to the enum but not to this map, those accounts silently disappear from the ALL chart. It is typed `Record<AccountType, string>`, so the compiler catches the omission; `ASSET_FILTER_MAP` is **not** exhaustive by type and needs the same edit by hand.
- **This grouping is not the investment pyramid's.** `WealthTier.of()` answers a different question (the role an asset plays, not how a user browses it) and lives on the backend — see [wealth-pyramid.md](./wealth-pyramid.md). Do not collapse the two without deciding which answer to lose.
- **`HOLDING_ACCOUNT_TYPES` lives in `lib/constants.ts`**, not in this page. It used to be duplicated here, in `AccountDetailPage` and in `features/accounts/hooks.ts`, so a new position-bearing type meant three chances to forget one.
- **`currentBalanceEur` is the account's full value, not the viewer's share.** Co-owned accounts carry `sharePercent` alongside it (see [account-ownership-shares.md](account-ownership-shares.md)); anything summing balances on this page must apply it, because the server only weights its own aggregates.
- **`Account.id` cast to `number`** — virtual group accounts use string keys (`'STOCKS'`, `'CRYPTO'`) cast as `number` via `as unknown as number`. This works because Recharts uses `dataKey` as a string lookup, but it's fragile.
- **`totalInvested` relies on the last invested point** — if an account has no snapshots at all, its invested amount is 0 and PnL equals its full balance. This is correct for newly created accounts where balance = invested.
- **Cash accounts have `investedAmount = balance`** — set by `AccountService.calculateInvestedAmount()` which returns `currentBalance` for accounts without holdings. This means their PnL = 0.
- **`useAllAccountsHistory` returns `AccountsHistoryData`** — not a flat array. Consumers must destructure `{ balances, invested }`.
- **Demo mock history** — `generateHistory()` in `frontend/src/demo/index.ts` creates 12 monthly points. The last point should match the account's `currentBalance` to stay consistent.

## Tests

- `frontend/src/components/shared/AccountCard.test.tsx` — a property renders its kind glyph
  instead of the color circle, each kind gets its own glyph, an unparseable `property_type`
  falls back to the circle, kind and city stand in for the provider line (and either half
  may be missing), the valuation date replaces the sync date, and the unrealized gain is
  gone. Glyph assertions match lucide's own `lucide-<icon>` class. A second block covers the
  stale badge: flagged past 48h, the plain last-sync line below it, never on a manual account.
- `frontend/src/hooks/use-table-sort.test.ts` — direction flips, a new column starting at its
  natural direction, nulls last both ways, collator ordering, stability, and that the caller's
  array is never mutated.
- `HoldingsTable.test.tsx` — opens on the largest position, a column flips, ticker sorts
  alphabetically while P&L sorts numerically, an unpriced line stays at the bottom either way, and
  `aria-sort` follows the active column. `PositionsByProduct.test.tsx` adds the one-sort-three-
  sections case.
- `AccountsWorkbookServiceTest` — the opening date is written on the account sheet, and the line
  is absent rather than blank when none is stated.
- Nothing covers the PnL chart, the summary card or the filters yet.

## Links

- i18n keys: `accounts.pnl`, `accounts.total`, `accounts.filters.*`, `accounts.lastSync`,
  `accounts.lastValuation`, `property.kind.*`, `dashboard.netWorthChange`
- Related: [dashboard-time-range-isolation.md](./dashboard-time-range-isolation.md) — Dashboard PnL chart
- Related: [live-prices-holdings.md](./live-prices-holdings.md) — per-holding PnL calculation
- Related: [bank-logos.md](./bank-logos.md) — account card avatar (bank logo, falls back to `color`)
- Related: [real-estate-valuation.md](./real-estate-valuation.md) — where `propertyKind` and `lastValuedAt` come from
