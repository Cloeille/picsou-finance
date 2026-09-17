# Feature: Privacy mode (hiding amounts)

> Last updated: 2026-08-16

## Context

Showing Picsou to someone meant showing what its owner is worth. Demo mode (`VITE_DEMO_MODE`,
`src/demo/`) does not answer that: it swaps every response for a mock at the Axios layer, it is a
build-time flag meant for the public deployment, and it therefore shows an installation that is
not yours.

Privacy mode is the opposite — the real app, the real structure, no figures. An eye button beside
the sidebar logo replaces every amount with a fixed-length mask, chart scales included.

## How it works

`hideAmounts` lives in `app-store` (Zustand, persisted under `picsou-app`). Every displayed amount
goes through one formatter, obtained from a hook so that the component consuming it re-renders
when the flag flips.

```
useAppStore(s => s.hideAmounts) ─┐
                                 ├─→ useMoney() ─→ MoneyFormatter ─→ amount / compact / quantity / quote / tick
i18n locale ─────────────────────┘
```

`CurrencyDisplay` calls the hook, which covers 29 files without touching them. The rest — chart
axes, hand-rolled tooltips, an amount interpolated into a translated sentence — call `useMoney()`
themselves.

### Key files

- `frontend/src/lib/money.ts` — the masks, `makeMoneyFormatter`, and the private currency format
- `frontend/src/hooks/use-money.ts` — `useMoney()`, the only public way to format an amount
- `frontend/src/components/shared/CurrencyDisplay.tsx` — the default path, plus the `publicQuote` opt-out
- `frontend/src/components/shared/MoneyChartTooltip.tsx` — for charts that relied on the shadcn default
- `frontend/src/stores/app-store.ts` — `hideAmounts`, `toggleHideAmounts`, `setHideAmounts`
- `frontend/src/components/layout/AppSidebar.tsx` — the eye button
- `frontend/src/pages/settings/SettingsPage.tsx` — the second entry point, reachable on mobile

## What is masked, and what is not

| Masked | Why |
|---|---|
| Every euro amount | The point of the mode |
| Chart Y-axis scales | A curve plus its scale gives the magnitude away as surely as the number does |
| Holding quantities | A quote is public, so quantity × price reconstitutes the line |
| `principal` / `interest` in positions | They are decompositions of the quantity, not separate money |

| Visible | Why |
|---|---|
| Percentages, /100 scores, coverage ratios | No amount can be derived, and they are what a demo is about |
| Unit prices — current quote and average buy-in (`publicQuote`) | Published information; with the quantity masked they say nothing about the position |
| Counts, dates, tickers, account names | Not amounts |
| **Input values** (`NumericInput` in the goal, property and target forms) | Masking what someone is typing makes the form unusable. An open edit form is an explicit intent to read the figure |
| **The live total in `AddTransactionModal`** | It is quantity × price as they are being typed — both operands are already on screen, so masking the product protects nothing. The only authorised caller of `formatCurrencyUnmasked` |

Placeholders in the goal calendar *are* masked: they are display-only, unlike a value being edited.

## Technical choices

| Choice | Why | Rejected alternative |
|---|---|---|
| A fixed-length mask, never per digit | `*********,** €` is visibly wider than `**,** €` — the width **is** the order of magnitude, so a per-digit mask leaks exactly what the mode hides | Masking digit by digit |
| Deleting `formatCurrency` from `lib/utils.ts` | One amount left legible defeats the feature, so forgetting one had to stop being possible. Making the raw formatter private turned all 12 call sites into compile errors | Leaving it exported and relying on discipline |
| A hook, not a store read inside the formatter | `useAppStore.getState()` returns the right string but does not re-render the component holding the closure, so chart axes would keep stale ticks | `formatDate`'s non-reactive `getState()` pattern |
| Keeping the currency symbol and the sign in the mask | `***** €` reads as "an amount, hidden"; a bare `*****` reads as a rendering fault. The sign states a direction, not a magnitude, and the visible percentages give that away anyway | Masking the whole string |
| Masked axis ticks rather than a hidden axis | Every `<YAxis>` already carries an explicit `width`, so masked ticks shift nothing, the grid keeps its lines, and the mask is the only in-frame evidence of redaction on a cropped screen-share | `<YAxis hide>` |
| A second toggle in Settings | The sidebar is `hidden md:flex`, so the eye button does not exist on mobile — where shoulder-surfing is likeliest | Sidebar only; a sixth item in the mobile bottom bar |
| `size-7` on the button | `size="icon"` is `size-10` (40 px) against a 28 px logo, and the classic sidebar aligns its nav on a hard-coded `mt-[47px]` | The default icon size |

## Gotchas / Pitfalls

- **`partialize` in `app-store` is an allowlist.** A field left out is silently not persisted — no
  type error, no runtime error, the setting just does not survive a reload. `AppSidebar.test.tsx`
  asserts the stored value for exactly this reason.
- **A masked amount still contains `€`.** Asserting `not.toContain('€')` in a test is wrong; assert
  the absence of the digits instead.
- **A formatter passed to `ChartTooltipContent` replaces the whole row**, colour indicator and
  series name included (`ui/chart.tsx:215`). That is why `MoneyChartTooltip` reproduces the row
  rather than passing a bare formatter, and why it takes the `ChartConfig` as a prop — `useChart()`
  is not exported and `components/ui/` is vendored shadcn, marked do-not-edit.
- **The hook goes above the early return** in the hand-rolled tooltips (`NetWorthTooltip`,
  `PnlTooltip`, `AmortizationTooltip`), which all start with `if (!active) return null`.
- **Tooltips subscribe for themselves** rather than receiving a formatter from their chart, so one
  that happens to be open when the toggle flips redraws with it.
- **`money.compact()` replaced two hand-rolled compact formatters** (`formatCompactEur` in the
  projection, `formatCompact` in the goal calendar). Both were invisible leaks, and both existed
  because someone needed a short axis label and reached for `Intl` — which is what guard rule B
  now makes noisy.

## Tests

- `money.test.ts` — the load-bearing case is `masked.amount(12) === masked.amount(12_345_678)`;
  plus sign and symbol survival, zero masked, the invalid-currency path still masked, and the
  unmasked output byte-identical to the old `formatCurrency` (issue #9 fallback included)
- `money-leak.test.tsx` — renders a component with **distinctive sentinel amounts**, flips the flag
  on the already-mounted tree, and asserts the digits are gone. It asserts absence rather than the
  presence of a mask, so it catches a path nobody migrated; and it toggles rather than rendering
  pre-masked, so it would fail if the toggle did not propagate
- `money-axis.test.tsx` — recharts does not lay out in jsdom, so `YAxis` is mocked by a probe that
  renders whatever formatter the chart handed it. Proves both that the scale is masked and that a
  toggle reaches it
- `money-guard.test.ts` — five lexical rules over the source tree (raw formatter allowlist, no
  hand-built currency format, no `€` in JSX, every amount axis imports `useMoney`, no
  amount-shaped identifier printed bare). Its docstring states what it cannot catch
- `AppSidebar.test.tsx` — the toggle flips, `aria-pressed` follows, and the choice is persisted

## Links

- [`docs/conventions/frontend.md`](../conventions/frontend.md) — the formatting rule
- [`docs/features/theme-persistence.md`](./theme-persistence.md) — the other persisted appearance flag
