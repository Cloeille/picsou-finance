# Feature: Frontend utility library (`frontend/src/lib/utils.ts`)

> Last updated: 2026-08-19 (DateInput no longer rewrites text mid-typing; `ageAt`)

## Context

Shared formatting functions used across the frontend. Centralised in one file to ensure consistent number/date formatting and avoid ad-hoc `Intl` calls scattered across components. The locale is resolved from the active UI language (via `document.documentElement.lang`, kept in sync by the i18n bootstrap) through the `SUPPORTED_LOCALES` registry (see [i18n.md](./i18n.md)).

## How it works

### Key files

- `frontend/src/lib/utils.ts` — all helpers
- `frontend/src/lib/utils.test.ts` — Vitest unit tests

### API surface

| Function | Signature | Output example |
|----------|-----------|---------------|
| `cn` | `(...inputs: ClassValue[]) => string` | Merges Tailwind classes via clsx + tailwind-merge |
| `getLocale` | `() => string` | Intl locale (`'fr-FR'`, `'en-US'`, `'de-DE'`, `'es-ES'`) resolved from `document.documentElement.lang` via `localeFromLanguage()` |
| `localeFromLanguage` | `(language) => string` | Maps i18n/browser language tags to the registry's Intl locale (`resolveLocale().intlLocale`) |
| `formatCurrency` | `(value, currency='EUR', locale=getLocale())` | `"1 234,50 €"`; falls back to decimal + code for invalid currency values |
| `formatDate` | `(dateStr, locale=getLocale(), format?)` | `"08/04/2026"` (locale) or `"08-04-2026"` (iso) |
| `parseDate` | `(input, locale=getLocale(), format=store.dateFormat) => string \| null` | `"08/04/2026"` → `"2026-04-08"` (inverse of `formatDate`); `null` if unparseable |
| `formatDateTime` | `(dateStr, locale=getLocale(), format?)` | `"08/04/2026 14:30"` (locale) or `"08-04-2026 14:30"` (iso) |
| `normalizeDecimal` | `(value: string \| null \| undefined) => string` | `"12,50"` → `"12.50"` (replaces first `,` with `.`) |
| `parseAmount` | `(value: string \| null \| undefined) => number` | `"12,50"` → `12.5`; tolerant `parseFloat` over `normalizeDecimal` |
| `formatLocalDate` | `(dateStr, locale=getLocale())` | `"8 avril 2026"` (long month) |
| `formatPercent` | `(points, { fractionDigits=1, signed=false }, locale=getLocale())` | `8.3` → `"8,3 %"` (fr), `"8.3%"` (en); `signed` → `"+8,3 %"`, never on zero |
| `formatDecimal` | `(value, fractionDigits, locale=getLocale())` | `4.7` → `"4,7"` (fr) — a plain number beside a unit that is not a percentage |
| `withDrawableSpaces` | `(formatted) => string` | Replaces U+202F with U+00A0; applied by every number formatter here and in `money.ts` |
| `formatTimeAgo` | `(dateStr, locale=getLocale())` | `"il y a 3 heures"` via `Intl.RelativeTimeFormat` |
| `ageAt` | `(birthDate, at) => number \| null` | `('1998-09-20', '2042-08-31')` → `43`; completed years between two `yyyy-MM-dd` dates |
| `todayLabel` | `(locale=getLocale(), date=new Date())` | `"Mardi 8 avril 2026"` (weekday + full date, sentence-cased) |
| `safeRedirect` | `(redirect, fallback='/')` | Returns the path only if it starts with `/`, else fallback |

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| `Intl.NumberFormat` / `Intl.DateTimeFormat` for all formatting | Native, locale-aware, no extra dependency | date-fns / numeral.js |
| `Intl.RelativeTimeFormat` for `formatTimeAgo` | Locale-correct relative strings (fr/en) | Manual string building per locale |
| `formatDate` reads `dateFormat` from Zustand store | User can toggle between locale-aware and fixed `DD-MM-YYYY` in settings | Hardcoded format — no user preference |
| `formatPercent` takes points (0–100) | Every percentage the API sends (`pnlPercent`, `sharePercent`, `annualPercent`…) is in points. The earlier ratio signature matched Intl but no caller, so every site hand-rolled `toFixed(1) + '%'` and French got "8.3%" (#274) | A ratio (0–1) — each caller divides by 100 and one forgotten division prints "830 %" |
| Percent templates carry no `%` | The caller passes `formatPercent(...)` into `{{value}}`, so the locale decides the separator and space, not the translator | `"{{value}} %"` in fr.json — the number inside is still `toFixed`, so the decimal stays a dot |
| U+202F becomes U+00A0 | Geist ships no U+202F glyph, so the French digit-group space fell back to a system-font sliver and "51 646,95 €" read as "51646,95 €" (#274) | Shipping a font subset with U+202F — a font build step for one character |
| `parseAmount` instead of bare `parseFloat` everywhere | French users type `12,50`; native `type="number"` inputs reject commas in FR locales and `parseFloat("12,50")` → `12`. One chokepoint fixes all amount entry | Per-field `.replace(',', '.')` (easy to forget on new fields) |

### Decimal input — `NumericInput`

`frontend/src/components/shared/NumericInput.tsx` is the shared amount-entry component. It wraps the shadcn `Input` as `type="text" inputMode="decimal"` (mobile numeric keypad, never rejects a comma) and sanitizes keystrokes to digits + a single `.`/`,` separator + an optional leading `-`. It rewrites `e.target.value` **before** calling the passed `onChange`, so it works with:

- **controlled-string forms** — `value`/`onChange` reading `e.target.value`, then `parseAmount(...)` at submit;
- **react-hook-form** — `register(name, { setValueAs: v => parseAmount(v) })` (RHF also reads the sanitized `e.target.value`). Relies on React 19 treating `ref` as a regular prop, so no `forwardRef` is needed.

All numeric inputs across Picsou (account balances & loan fields, goal target, month override/manual contribution, transaction qty/price/amount, holding qty/buy-in, month-end balances) use `NumericInput` + `parseAmount`.

### Page header date

`frontend/src/components/shared/PageHeader.tsx` uses `todayLabel(locale, headerDate)` as its default surtitle so top-level pages keep the same dated header rhythm. The header stores the date once at mount, then formats it from the active i18n language (`fr-FR` / `en-US`) so changing the app language updates the visible weekday and month names without needing a page reload. Pass an explicit `surtitle` only when a page needs a different label.

`frontend/src/i18n/index.ts` also synchronizes `<html lang>` after init and on
every language change, so lower-level helpers and charts that call `getLocale()`
inherit the same language as the visible app.

`todayLabel` sentence-cases the first character after `Intl.DateTimeFormat`
returns the localized string, so French headers show `Lundi 6 juillet 2026`
rather than `lundi 6 juillet 2026`.

### Date input — `DateInput` (hybrid native/desktop)

`frontend/src/components/shared/DateInput.tsx` is the shared date-entry component.
Its external contract is **always an ISO `yyyy-MM-dd` string** (`{ value, onChange:
(iso) => void, id?, required?, disabled?, className? }`), regardless of how it's
displayed. It exists because a native `<input type="date">` always renders in the
OS/browser locale and **cannot** be coerced to honour the in-app `dateFormat`
setting (`dd/mm/yyyy` vs `dd-mm-yyyy`):

- **Touch devices** (`useIsTouchDevice()` → `matchMedia('(pointer: coarse)')`,
  `frontend/src/hooks/use-touch-device.ts`) render the native `<input type="date">`
  — the OS picker is the best mobile experience and already speaks ISO.
- **Desktop** renders a text field that *displays* `formatDate(value)` and *parses*
  typed text back to ISO with `parseDate`. It only emits `onChange` once the text
  parses to a real date (or `''` when cleared), so a half-typed value never
  propagates a garbage ISO upstream. A `common.dateHint` placeholder shows the
  expected shape, and `aria-invalid` flags an unparseable value after blur.

It resyncs the visible text when the external `value` or the `dateFormat` setting
changes, derived **during render** (tracking `lastValue`/`lastFormat` in state) to
avoid a cascading re-render — the same "derive during render" pattern used in
`ConfirmDialog`.

**It deliberately does not resync from its own echo.** Every keystroke that happens
to parse is emitted upstream and arrives straight back as a new `value`; rewriting
the text from it overwrote what was still being typed. Entering a 1990s birth year
meant typing `19`, watching the field become `…/2019` — `parseDate` expands a
two-digit year to the 2000s — and then fighting the caret for the remaining digits.
`lastEmitted` tells the echo apart from a genuine external change (a form reset, a
key-remount seeding another entity), which still resyncs. The canonical formatting
the resync used to apply incidentally now happens **on blur** instead, so
`14.6.1990` still settles to `14/06/1990` once the field is left.

Wired into the four date fields: `AddTransactionModal` (transaction date),
`GoalsPage` (deadline), and `AccountForm` (loan start/end — via react-hook-form
`<Controller>` so the ISO value flows through `value`/`onChange`).

## Gotchas / Pitfalls

- **`getLocale()` reads `document.documentElement.lang`** — `frontend/src/i18n/index.ts` keeps that attribute in sync on every `languageChanged` event, but `getLocale()` itself is not reactive: an already-rendered component won't re-render on language switch. For UI that must react immediately, use `localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)` with the `useTranslation()` hook (the established pattern in charts and `CurrencyDisplay`).
- **`formatCurrency()` validates its locale before calling `Intl.NumberFormat`** — invalid translation/mocking values must not crash the UI. Invalid currency codes fall back to a decimal number followed by the raw code.
- **`formatDate` vs `formatLocalDate`**: `formatDate` outputs `dd/mm/yyyy` (compact, for tables) or `DD-MM-YYYY` if the user selected the ISO format in settings; `formatLocalDate` outputs long-month form (for readable labels). Don't swap them.
- **`formatDate` format resolution**: reads `useAppStore.getState().dateFormat` at call time (`'locale'` or `'iso'`). The optional `format` parameter overrides the store value — used by callers that need a specific format regardless of user preference.
- **Store import in `utils.ts`**: `formatDate` imports `useAppStore` directly — safe because `app-store.ts` has no dependency on `utils.ts` (no circular dependency).
- **`formatPercent` expects points** (50 = 50 %), not a ratio. A 0–1 ratio (recurring-series `confidence`, goal-calendar ratios) must be multiplied by 100 first.
- **Amount axes use `money.tick`**, never a `toFixed` ladder: it is the compact currency format at two fraction digits (`60 k €`, `1,25 M €`, `€60K`), masked to `***` in privacy mode. German CLDR does not compact thousands (`-250.000 €`), so every money axis takes `width={MONEY_AXIS_WIDTH}` (84px). The label is 66px in Geist at 12px, and Recharts anchors it at `width − tickMargin (8) − tickSize (6)`, reserving the tick size even with `tickLine={false}`: 76px clipped the minus sign by 4px, 80px leaves none to spare. Recharts' `width="auto"` was tried and dropped the net-worth axis entirely (Recharts 3.8). `money-guard.test.ts` requires every `<YAxis>` to pass `tickFormatter={money.tick}` and every `<ChartTooltipContent>` to pass a `formatter` (or to be `MoneyChartTooltip`); the percent-scale `AllocationTrajectory` is the one allowlisted axis.
- **`ageAt` works on the string parts, never through `Date`.** It is asked about points twenty
  years out, and building two `Date` objects to subtract them would reintroduce the very
  local-midnight problem `toDate` exists to avoid, for no gain. It returns `null` on a malformed
  input rather than `NaN`, and a negative number is possible — a birth date after the date asked
  about — which callers interpret rather than the helper pretending it cannot happen.
- **Date-only values are anchored at local midnight** — `new Date("2026-04-08")` is specified to parse as *UTC* midnight, so west of UTC every backend `LocalDate` (transaction dates, goal deadlines, `priceAsOf`) rendered as the day before. `formatDate`, `formatDateTime` and `formatLocalDate` all route through a shared `toDate` helper that appends `T00:00:00` to a `yyyy-MM-dd` string and leaves anything carrying a time to `Date` untouched — there the offset is real information, not a parsing artefact. This gotcha used to advise reaching for `formatLocalDate` instead, which had the identical flaw; the choice between them is now purely about long-month vs compact output.
- **`parseDate` is the strict inverse of `formatDate`** — the year is the last token in every shape we render (`dd-mm-yyyy`, `dd/mm/yyyy`, `mm/dd/yyyy`); only day/month order varies (`mm/dd` for **en-US in non-iso mode**, `dd/mm` otherwise). It accepts `/`, `-`, `.` separators interchangeably, expands 2-digit years to the 2000s, and round-trips impossible dates (e.g. `31/02`) to `null` by re-checking via `new Date`. When changing `formatDate`'s output shape, update `parseDate` and the round-trip test together.
- **`DateInput` desktop branch never emits an invalid ISO** — `onChange` fires only when `parseDate` succeeds (or `''` on clear). Consumers therefore can't rely on `onChange` firing for every keystroke; the displayed text is internal state until it parses.
- **`DateInput` does emit *intermediate* dates while a year is being typed.** `20/09/19` parses to `2019-09-20` and is emitted before the user reaches `1998`. Harmless for a form that saves on submit, which is every current consumer — but a consumer that acted on each `onChange` (a fetch, a navigation) would fire on a date the user never meant.
- **`safeRedirect` is a security guard** — always use it before redirecting to a URL from query params to prevent open redirect attacks.
- **Account-type labels are translation keys** — use `accountTypeLabelKey()` from `frontend/src/lib/constants.ts` with `t()`; the former hardcoded-French `accountTypeLabel` helper was removed (2026-07-07). New backend `AccountType` values need an `ACCOUNT_TYPES` entry plus `accountTypes.*` keys in all locale files.

## Tests

- `frontend/src/lib/utils.test.ts` — covers `cn`, `formatCurrency` including invalid currency/locale fallback, `formatDate`, `formatPercent` and `formatDecimal` in fr/de/es/en, and `parseDate` (dd/mm vs mm/dd ordering, iso mode, mixed separators, 2-digit years, impossible-date rejection, malformed input, and the `formatDate`∘`parseDate` round-trip across both formats × both locales).
