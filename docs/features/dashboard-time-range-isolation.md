# Feature: Dashboard Time Range Isolation

> Last updated: 2026-04-13

## Context

The Dashboard displays net worth history, account distribution, and goals. The time range selector (1D, 7D, 1M, etc.) controls both the chart data and the net worth trend displayed in the hero card. The `range` state lives in `DashboardPage` and is passed to `NetWorthChart` as a prop.

## How it works

The dashboard fetches all data once via `useDashboard()` (no range parameter). The backend always returns 12 months of net worth history. `NetWorthChart` filters this data client-side based on the selected range.

### Key files

- `frontend/src/pages/dashboard/DashboardPage.tsx` — Page layout, owns `range` state, calculates trend over selected period, passes `range`/`onRangeChange` to chart
- `frontend/src/components/shared/NetWorthChart.tsx` — Chart with `range`/`onRangeChange` props, `filterByRange()` client-side filter
- `frontend/src/components/shared/TimeRangeSelector.tsx` — Time range button controls (1D, 7D, 1M, 3M, YTD, 1Y, ALL)
- `backend/src/main/java/com/picsou/service/DashboardService.java` — `buildNetWorthHistory()` always fetches last 12 months

### Flow

```
DashboardPage mounts
  ↓
useDashboard() fetches /api/dashboard (no range param)
  ↓
Backend returns 12 months of history + distribution + goals
  ↓
DashboardPage owns range state (default: '1Y')
  ↓
historyForRange = filterByRange(netWorthHistory, range)  (useMemo)
  ↓
Trend = last point of filtered data − first point of filtered data
  ↓
Hero card shows trend for the selected period
  ↓
NetWorthChart receives range + onRangeChange as props
  ↓
User clicks "3M" → DashboardPage.setRange('3M')
  ↓
Both hero trend AND chart update to reflect 3M period
  ↓
Distribution and goals: unaffected by range (no range dependency).
In financial wealth mode the distribution donut additionally mirrors the
hero filter (checking, loans, real estate, physical precious metals and
OTHER excluded), so the donut never shows a category the figure excludes.
```

## Trend calculation

The dashboard hero shows net worth change over the selected time range. The frontend filters `netWorthHistory` by the selected range, then computes:

```typescript
const startValue = historyForRange[0].total
const endValue = historyForRange[historyForRange.length - 1].total
const trend = endValue - startValue
const trendPct = (trend / startValue) * 100
```

This replaces the previous approach that compared against an arbitrary second-to-last point. The trend now always reflects the chart's selected period.

## Optional invested data

`DashboardData.netWorthHistory` items have an optional `invested` field. When present, the chart renders:
- A dashed `invested` line alongside the `total` area
- A gain/loss section in the tooltip (`total - invested`)
- A custom legend with solid (total) and dashed (invested) indicators

When `invested` is absent or undefined, the chart and tooltip show only the `total` line. The `NetWorthTooltip` component uses a `hasInvested` flag to conditionally render invested-related sections.

## Dashboard chart row layout

The dashboard chart row uses fixed-height cards (`420px`) for the PnL chart and
the distribution/allocation card. `DistributionPie` keeps both tab panels inside
the same bounded content area: the pie legend scrolls if needed, and the
allocation treemap fills the available height. This prevents the whole grid row
from growing when the user switches between "Distribution" and "Allocation".

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Client-side filtering in `NetWorthChart` | Single API call, instant range switching, no extra backend work | Separate API call per range — backend ignores the `range` param anyway, would duplicate requests |
| `range` state in `DashboardPage` (lifted from `NetWorthChart`) | Both hero trend and chart must react to range changes — single source of truth | State inside `NetWorthChart` only — trend was disconnected from range selection |
| Default range `'1Y'` | Matches the 12 months of data the backend returns. Consistent with user expectations. | `'ALL'` — identical to 1Y given backend data window |
| Responsive `TimeRangeSelector` buttons | Smaller padding/font on mobile (`px-1.5 text-[11px]`), larger on `sm:` breakpoint. `flex-wrap` for overflow. | Fixed size — overflows on small screens |
| Derive trend from filtered history array | No backend field needed; trend always matches the visible chart period | Add backend field — redundant since history already has the data |
| Fixed-height chart cards | Tab content has different natural heights; a fixed row prevents card resizing on tab switches | Let the grid auto-size each tab panel — causes the PnL card to jump too |

## Gotchas / Pitfalls

- **Backend always returns 12 months**: `DashboardService.buildNetWorthHistory()` hardcodes `LocalDate.now().minusMonths(12)`. Ranges like `'ALL'` will only show 12 months unless the backend is updated.

- **`filterByRange()` uses `new Date()` at filter time**: The cutoff date is computed on each range change relative to "now". If the page stays open across midnight, the filtered window shifts accordingly.

- **`NetWorthChart` is used elsewhere**: It's a shared component in `components/shared/`. The `TimeRangeSelector` is now always rendered inside it. If another page uses `NetWorthChart`, it will also show the range selector.

- **Trend needs at least 2 history points**: If `historyForRange` has fewer than 2 entries, `startValue` defaults to `0`, and the trend shows the full net worth as "gain." This is acceptable — a single data point means the account was just created.
- **`useMemo` must be before conditional return**: In `DashboardPage`, the `historyForRange` memo is computed before the `if (isLoading) return` guard. React requires hooks to be called in the same order on every render. Placing hooks after an early return causes error #310 (too many re-renders).

- **`invested` is optional in history items**: Some data sources (e.g., bank sync) don't provide invested amounts. The chart and tooltip handle the absence gracefully. Don't assume `invested` exists without checking.

## Tests

No dedicated test files. Manual verification:

1. Open Dashboard
2. Click range buttons in the chart → only chart data changes, hero/distribution/goals stay static
3. Toggle Distribution / Allocation → chart row card heights stay fixed
4. Verify range buttons are usable on mobile viewport (no overflow)
5. Refresh page → chart defaults to 1Y

## Links

- Related ADR: [Component-local state for UI filters](../decisions/2026-04-05-component-local-state-for-ui-filters.md)
