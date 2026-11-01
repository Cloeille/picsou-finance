# Feature: Goal Calendar — Grid View (donuts)

> Last updated: 2026-10-31 (wrapping grid, whole-euro labels, legend, title — #282)

## Context

The "Grid" view of `GoalCalendarPage` displays past months as interactive donut cards. Each donut shows the effective amount and the percentage of the monthly savings objective achieved. The original 52px SVG rings (6-column grid, percentage only) were replaced with 80px cards showing amount + percentage, overflow support (>100%), and compact formatting for large amounts.

## How it works

### Key files

- `frontend/src/pages/goals/GoalCalendarPage.tsx` — entire component: `ProgressRing`, `getProgressColor`, `YearGridView`, skeleton, `MonthDetailPanel`
- `frontend/src/pages/goals/GoalCalendarPage.test.tsx` — title, whole-euro labels, current-month scroll, legend

### Page title

`PageHeader` gets `surtitle={t('goals.calendar')}` and `title={goal.name}`, as on `AccountDetailPage`. A sentence template ("Calendrier de {{name}}") cannot elide in French ("de Apport") and pushed the H1 past the mobile width, where `PageHeader` truncates it.

### Components

**`ProgressRing`** — 80px SVG, stroke 9px, rotated -90° so the arc starts at the top:
- Background track: `var(--muted)`, stroke 9px
- Base arc: `min(pct, 1) × circumference`, `stroke-linecap="butt"` on overflow, `"round"` otherwise
- Bonus arc (overflow > 100%): color `#818cf8` (`COLORS.overshoot`, light indigo), same stroke width as the track so it never spills over the ring, `min(pct−1, 1) × circumference`, `stroke-linecap="round"` except when full circle → `"butt"` (avoids bump at junction)

**`getProgressColor`** — returns `color` (arc), `textColor` (inner text), `pct` (raw ratio):

| Condition | Arc color |
|-----------|-----------|
| No data | `--muted` |
| ratio < 60% | `oklch(from var(--destructive) calc(l + 0.15) c h)` |
| 60% ≤ ratio < 100% | `#f59e0b` (amber) |
| ratio ≥ 100% | `oklch(from var(--primary) calc(l + 0.2) c h)` |

**Amounts** — every ring amount and objective goes through `money.amount(value, 'EUR', { maximumFractionDigits: 0 })`: whole euros (`985 €`, `1 373 €`), masked in privacy mode. One unit for the whole grid, so a row never mixes `984,72 €` with `1 k €`, and the objective matches the header's `1 373,33 €` to the euro. Measured in Geist at 12px bold, `123 456 €` is 62px wide, exactly the clear inside of the ring; a seven-figure month (`1 234 567 €`, 72px) runs 5px into the 9px stroke on each side. Accepted: a single month of a million euros is outside what this calendar is for, and neither fix is worth it (a compact `M` unit would mix units within a row, and dropping to the 11px floor only buys 8px, still 2px too wide).

**`YearGridView`** — one `<Card>` per year, a wrapping `grid grid-cols-[repeat(auto-fill,minmax(6rem,1fr))] gap-3`: six months per row in the desktop column (≈ 680px), three on a 390px phone. No horizontal scroll. Each month is a `<button>` with:
1. Month label (11px uppercase)
2. `ProgressRing` + absolute text overlay: amount 12px bold in `text-foreground` (Ledger Ink), percent 11px in the status color
3. `obj. XXX` (11px muted)

The current month carries `aria-current="date"` and is scrolled into view once on mount (`scrollIntoView({ block: 'nearest' })`). Its `scroll-mb-28` keeps the year card's bottom edge and the legend above the mobile bottom nav.

A legend below the cards shows two 16px `ProgressRing`s: `goals.legendProgress` (share of the month's objective reached, `94%`) and `goals.legendOvershoot` (above the objective, `+8%`, lavender arc).

### Inner text — cases

| Situation | Line 1 | Line 2 |
|-----------|--------|--------|
| No data | `–` (13px muted, centered) | — |
| 0–100% | whole-euro `effective` | `XX%` |
| > 100% | whole-euro `effective` | `+XX%` |

### Indicator dots

- Violet dot (`bg-violet-600`) at `top-[3px] right-[3px]`: monthly objective manually overridden (`entry.override != null`)
- Blue dot (`bg-blue-500`): manually declared contribution (`entry.manualActual != null`). Takes priority over violet when both are set.

### Selection state

- Selected: `border-primary bg-accent`
- Unselected: `border-border hover:bg-accent/50`

### Page layout — edit panel placement (responsive)

The selected month's `MonthDetailPanel` is shown *beside* the calendar, not stacked below it:

- **Desktop (`≥ lg`)**: a 2-column grid `grid lg:grid-cols-[1fr_360px] items-start` — active view on the left (`min-w-0`), a `lg:sticky lg:top-4` right column on the right. The right column always renders (panel when a month is selected, a hint card otherwise) so the grid never collapses → no layout shift.
- **Mobile/tablet (`< lg`)**: the panel renders in a `Sheet side="bottom"` (shadcn). A local `matchMedia('(min-width: 1024px)')` flag (`isDesktop`) gates `open={!!selectedEntry && !isDesktop}` so the sheet never opens on desktop (where the overlay would otherwise darken the screen behind the visible side panel). An `sr-only` `SheetTitle`/`SheetDescription` satisfies Radix Dialog a11y.
- `MonthDetailPanel` takes an optional `className` (the sheet passes `border-0 bg-transparent shadow-none` to avoid a nested-card look) and uses a single-column inner grid — Tailwind media queries are viewport-based, so `md:grid-cols-2` would have split inside the narrow 360px panel.

### History backfill card

A slim `<Card>` above the 2-column layout offers "+ Add {year}" (`goals.addYear`), where `year = earliestRenderedYear - 1`. It calls `useExtendGoalHistory()` → `POST /goals/{id}/history/extend`, extending the calendar one year earlier. See [goals.md](./goals.md) → *History backfill*.

Additionally, a dashed **"+ previous month"** card (`goals.addPreviousMonth`) is prepended to the *earliest* year's month row inside `YearGridView` (gated on `year === years[0].year`). It calls `useExtendGoalHistoryByMonth()` → `POST /goals/{id}/history/extend/month`, extending the calendar one month earlier per click — for fine-grained backfill of the months before goal creation (e.g. the earlier months of the current year). Crossing January makes a previous-year card appear. Same grid cell as the month buttons; shows a `Plus` icon (or `Loader2` while pending).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Custom SVG (two `<circle>`) | Full control over the bonus arc, no dependency | `recharts` (no access to `stroke-dasharray` for overflow) |
| `oklch(from var(--primary) calc(l + 0.2) c h)` | `var(--primary)` in dark mode is too dark (lightness 0.42); CSS relative color syntax adds 0.2 lightness while staying theme-consistent | Hardcoded `#6366f1` (doesn't follow the theme) |
| Wrapping `auto-fill` grid, 6rem minimum cell | Every month visible at any width; the 80px ring keeps its 12px amount readable | `overflow-x-auto` single row (hid the recent months behind a scroller with no affordance, #282); four columns on a phone (rings too small for ≥ 11px labels) |
| Whole euros via `money.amount` | One unit per row, objective matches the header | `money.compact` (precision follows each value's magnitude, so `984,72 €` sat beside `1 k €`) |
| Bonus arc capped at 100% bonus | Beyond 200% total, the visual doesn't change; the real % stays readable in the text | Multi-turn arc (complex and unreadable) |

## Gotchas / Pitfalls

- **Tailwind v4 + CSS vars**: `hsl(var(--primary))` is invalid in Tailwind v4 (vars are in `oklch`). Use `var(--primary)` directly in SVG `stroke` attributes or inline styles. `color-mix(in oklch, ...)` works; wrapping oklch vars in `hsl()` renders as transparent.
- **`stroke-linecap="butt"` at 100% bonus**: if the bonus arc reaches exactly the circumference with `"round"`, the rounded endpoints overlap and create a visible bump at the top. Detect `bonusFilled >= circ - 0.1` to switch to `"butt"`.
- **`var(--border)` in dark mode** = `oklch(1 0 0 / 10%)` (white at 10% opacity) — nearly invisible as a background track. Use `var(--muted)` instead.
- **`textColor` === `color`**: the percent line uses the arc color. The amount above it is `text-foreground`, since amber text fails contrast on a light background.
- **`TimelineView` / `CalendarGridView` are independent** — they do not share `ProgressRing`; edit the donut grid without affecting them.
- **`MonthDetailPanel` is now rendered in two places** (sticky desktop column *and* mobile bottom sheet) from the same `selectedEntry`. Its inner grid must stay single-column (viewport-based media queries break inside the narrow panel) and all amount inputs use the shared `NumericInput` (comma/point tolerant).

## Tests

`GoalCalendarPage.test.tsx` renders the page with mocked goal hooks and a fixed date. It checks that the goal name is the H1, ring amounts and objectives are whole euros with no `k`, the current month is `aria-current="date"` and scrolled into view, and the legend renders. Layout (wrapping, label sizes) is checked by screenshots of the demo build at 1440×900 and 390×844. In `e2e/goals.spec.ts`, Playwright opens demo goal 2's calendar at 390×844 with a pinned clock and checks that all 12 past/current month buttons sit inside the viewport.

## Links

- Feature backend: [goals.md](./goals.md)
- ADR: [2026-04-08-css-relative-color-syntax.md](../decisions/2026-04-08-css-relative-color-syntax.md)
