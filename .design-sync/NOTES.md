# Picsou design-sync — repo notes

Repo-specific gotchas for future syncs. Field-level config lives in `config.json`.

## Shape & entry
- **Package shape, synth via a barrel.** Picsou is an app, not a published lib —
  `package.json` has no `main`/`module`/`exports`, and `dist/` is a Vite *app* build
  (not importable). We ship a hand-written barrel `frontend/.ds-entry.tsx`
  (`export * from` each scoped component) and pass it via `--entry`. This makes the
  converter resolve `PKG_DIR` to `frontend/` (it walks up from the entry to the first
  named `package.json`) and bundle exactly our chosen surface. Without `--entry`,
  `PKG_DIR` would be `frontend/node_modules/picsou` (nonexistent).
- Build (from repo root):
  `node .ds-sync/package-build.mjs --config .design-sync/config.json --node-modules frontend/node_modules --entry frontend/.ds-entry.tsx --out ./ds-bundle`
- `cfg.srcDir = "src/components"` scopes discovery/enrichment to components (not all of `src/`).
- `cfg.tsconfig = "tsconfig.app.json"` carries the `@/* → ./src/*` alias esbuild needs.
- Keep `frontend/.ds-entry.tsx` in step with `cfg.componentSrcMap`.

## Styling (Tailwind v4)
- Components are styled entirely by Tailwind utility classes; `src/index.css` is only
  `@import "tailwindcss"` + `@theme` + tokens, so the raw file is NOT a usable stylesheet.
  `cfg.cssEntry` points at the **compiled** app CSS `dist/assets/index-<hash>.css`.
- **Re-sync risk: the CSS filename hash changes on every `bun run build`.** Update
  `cfg.cssEntry` to the new hash after rebuilding the app (`ls frontend/dist/assets/index-*.css`),
  or the converter fails a `[CSS_*]` tag.

## Fonts
- Brand font Geist ships via `cfg.extraFonts: ["node_modules/@fontsource-variable/geist/index.css"]`
  (relative @font-face urls, family "Geist Variable"). Working.
- The compiled CSS's own Geist @font-face uses `/assets/*.woff2` (absolute, unresolvable) —
  dropped as dead blocks. Expected.
- **Accepted font fallbacks (non-blocking):** `[FONT_MISSING] Segoe Script, Snell Roundhand`
  and `[FONT_DANGLING] Homemade Apple` come from `.font-homemade` (`src/pages/setup/setup.css`),
  used ONLY by the onboarding `HelloGreeting` page — OUTSIDE the DS scope. The woff2 isn't
  committed (`public/fonts/` has only a README), so the real app also degrades to system
  `cursive`. No DS component uses it. Do not chase.

## Synced surface (36 components)
- **general/** — 25 shadcn primitives (`src/components/ui/*`). 23 have authored previews;
  **ChartContainer** and **Toaster** ship the floor card (see below).
- **shared/** — 11 Picsou-specific presentational compositions (`src/components/shared/*`):
  AccountCard, AccountTypeBadge, CurrencyDisplay, EmptyState, ErrorState, GoalProgressBar,
  MerchantAvatar, NumericInput, PageHeader, PriceFreshnessDot, TimeRangeSelector.
- **Deliberately NOT synced**: chart feature components (recharts — see below), data-bound
  modals/sections (heavy react-query/router state), pure-plumbing (ErrorBoundary, guards).

## i18n (the barrel side-effect that makes translations work)
- `frontend/.ds-entry.tsx` starts with `import "@/i18n/index"`. react-i18next installs a
  **global** instance via `initReactI18next`, so `useTranslation()` in any component resolves
  real FR strings **without a provider** — as long as that side-effect import runs. Remove it
  and every `shared/` card shows raw keys (`accounts.lastSync`) instead of "Dernière sync".
  Note the explicit `/index` — `@/i18n` alone resolves to a directory and esbuild fails.

## recharts does not render in the static capture (important)
- recharts v3's `ResponsiveContainer` measures 0×0 in the headless `?story=` capture (blank
  1.2KB PNG), independent of `isAnimationActive`. **ChartContainer** is therefore floor-carded.
  Any future attempt to sync the app's chart feature components (NetWorthChart, DistributionPie,
  BalanceHistoryChart, AccountsStackedChart, LoanAmortizationChart, CashflowSankey) will hit the
  same wall — they'd floor-card too. Don't burn cycles re-trying; it's a harness limitation.

## Overlay previews
- Dialog / Sheet / DropdownMenu / Tooltip / Sidebar author their **open** state and use
  `cfg.overrides.<Name>: {cardMode: "single", viewport, primaryStory}` so the portaled/fixed
  content renders inside the card instead of escaping. Sidebar previews wrap in `SidebarProvider`;
  Tooltip in `TooltipProvider`.

## Known render warns
- 2 floor cards by design: **ChartContainer** (recharts, above), **Toaster** (a toast host with
  no static visual — used imperatively via `toast()`). Both are honest baselines, not failures.
- Font warnings `[FONT_MISSING] Segoe Script/Snell Roundhand` + `[FONT_DANGLING] Homemade Apple`
  are accepted (onboarding-only cursive, out of scope — see Fonts above).

## Re-sync risks
- **CSS hash instability** — `cfg.cssEntry` points at `dist/assets/index-<hash>.css`; the hash
  changes on every `bun run build`. Update it (`ls frontend/dist/assets/index-*.css`) or the
  converter fails a `[CSS_*]` tag. #1 thing to fix on a rebuild.
- `cfg.cssEntry` depends on `dist/` existing — run `bun run build` in `frontend/` first.
- The barrel `frontend/.ds-entry.tsx` and `cfg.componentSrcMap` must stay in step; a component
  added to one but not the other is silently missing a card or an export.
- Mock objects in `.design-sync/previews/{AccountCard,GoalProgressBar}.tsx` are hand-built to
  match `Account` / `GoalProgress` — if those types gain required fields the component reads,
  update the mocks.
