# Convention: Frontend

## Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| React | 19 | UI framework |
| TypeScript | 5.9 (strict) | Type system |
| Vite | 7 | Build tool |
| TanStack Query | v5 | Server state (fetching, caching, syncing) |
| Zustand | v5 | Client state (auth, app settings) |
| shadcn/ui | latest | Component library (Radix primitives) |
| Tailwind CSS | v4 | Styling |
| React Router | v7 | Routing with lazy code splitting |
| Axios | latest | HTTP client with interceptors |
| react-i18next | latest | Internationalization (FR/EN) |
| Recharts | v3 | Charts |
| react-hook-form + Zod | latest | Form handling + validation |
| Sonner | latest | Toast notifications |
| next-themes | latest | Dark/light mode |

## Directory structure

```
src/
  app/              Entry: providers.tsx, routes.tsx
  pages/            Route pages (one file per route, lazy-loaded)
  components/
    layout/         AppSidebar, AppLayout (persistent shell)
    ui/             shadcn/ui generated — DO NOT EDIT
    shared/         App-specific reusable components
  features/         Feature slices: api.ts + hooks.ts per domain
  stores/           Zustand stores (auth-store.ts, app-store.ts)
  lib/              api-client.ts, utils.ts, constants.ts, query-client.ts
  types/            api.ts (mirrors backend DTOs), app.ts (frontend-only types)
  demo/             Demo mode interceptor + mock data
  i18n/             i18next initialization
  main.tsx          Bootstrap + demo mode setup
```

## State management

### Server state — TanStack Query

All remote data lives in TanStack Query. Feature hooks in `features/*/hooks.ts` own query keys and fetch functions.

```typescript
// features/goals/hooks.ts
export function useGoals() {
  return useQuery({ queryKey: ['goals'], queryFn: () => api.get('/goals') })
}
```

- Stale times configured in `frontend/src/lib/constants.ts`.
- No Redux, no Context for server data.

### Client state — Zustand

Only for auth and app-wide UI state (e.g., demo mode toggle).

```typescript
// stores/auth-store.ts
export const useAuthStore = create<AuthState>((set) => ({
  username: sessionStorage.getItem('picsou_user'),
  isAuthenticated: !!sessionStorage.getItem('picsou_user'),
  login: (username) => { sessionStorage.setItem('picsou_user', username); set(...) },
  logout: () => { sessionStorage.removeItem('picsou_user'); set(...) },
}))
```

Auth cookies are HttpOnly — the Zustand store is the JS-readable signal, persisted in `sessionStorage`.

## Hooks and React Compiler rules

`eslint-plugin-react-hooks` v7 ships the **React Compiler** lint rules (`purity`,
`set-state-in-effect`, `set-state-in-render`, `immutability`, `refs`,
`incompatible-library`) on top of the classic `exhaustive-deps`. `bun run lint` is
expected to be **zero-warning** — these are errors, not suggestions. The canonical
fixes below are already applied across the codebase; match them when you hit a rule.

| Rule | What trips it | Canonical fix |
|------|---------------|---------------|
| `purity` | `Math.random()` / `Date.now()` / `new Date()` read during render | Lazy `useState(() => …)` initializer — runs once at mount, value stays stable. Never `useMemo` for impure once-at-mount values (`useMemo` may re-run). |
| `set-state-in-render` | `setState()` inside a `useMemo`/render body | Compute the value and return it: `const x = useMemo(() => ({…}), deps)`. Drop the paired `useState`/`setState`. |
| `immutability` | Mutating a binding during render (`let total = 0; total += …` in JSX) | `reduce` / `useMemo` that *returns* the derived value. |
| `set-state-in-effect` | Synchronous `setState()` in an effect body — populate/reset-on-open effects | **Key-remount pattern** (below). For a genuine fetch-on-mount that syncs an external system, a *documented* `// eslint-disable-next-line react-hooks/set-state-in-effect` is acceptable (see `ConnectionGuard.tsx`). |
| `incompatible-library` | react-hook-form `watch('x')` | `useWatch({ control, name: 'x' })`. |
| `exhaustive-deps` | TanStack mutation in a dep array — `[health.mutate]` makes the rule demand the whole (unstable) mutation object | Destructure the stable fn into a local: `const { mutate: probeHealth } = useXHealth()`, depend on `probeHealth`. Keep the object binding if you also read `.isPending` in render. |

### Key-remount instead of populate/reset-on-open effects

Modals that used to seed/reset form state via `useEffect(…, [open])` now mount a child
form only while open, keyed so it remounts per edited entity. Initial state comes from
props through **lazy `useState` initializers** — no effect, no `set-state-in-effect`:

```tsx
// Parent: mount the form only while open, remount per entity
{open && entity && <EntityForm key={entity.id} entity={entity} … />}

// Child: seed every field from props via lazy initializers
function EntityForm({ entity }) {
  const [qty, setQty] = useState(() => String(entity.quantity))
  // …handlers do the work; no useEffect
}
```

Applied in `AddTransactionModal`, `EditHoldingModal`, `MonthEndBalanceModal`. Auto-fill
that previously lived in an effect (e.g. ticker → holding name) moves into the change
handler so it stays out of render.

### shadcn `ui/` and `react-refresh/only-export-components`

shadcn components dual-export a component plus its `*Variants` cva object, which trips
`react-refresh/only-export-components`. These files are **vendor-generated** (a future
`shadcn add` overwrites them), so the rule is scoped **off** for `src/components/ui/**`
in `eslint.config.js` rather than hand-edited. Do not add disables inside those files.

## API client

Single Axios instance in `frontend/src/lib/api-client.ts`:

```typescript
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
})
```

### 401 auto-refresh interceptor

On 401, the interceptor calls `POST /api/auth/refresh` and retries the failed request. Concurrent 401s are queued and replayed after a single refresh.

```typescript
api.interceptors.response.use(
  res => res,
  async error => {
    if (error.response?.status === 401 && !originalRequest._retry) {
      await api.post('/auth/refresh')
      return api(originalRequest)
    }
    // redirect to /login on refresh failure
  }
)
```

### Demo mode interceptor

When `VITE_DEMO_MODE=true` (or runtime toggle via `app-store.ts`), a request interceptor short-circuits to mock handlers with simulated 200-600ms delay. Mock data lives in `demo/data/`.

## Routing and code splitting

React Router v7 with `lazy()` per page:

```typescript
const DashboardPage = lazy(() =>
  import('@/pages/dashboard/DashboardPage').then(m => ({ default: m.DashboardPage }))
)
```

- Auth-protected routes wrapped in `<RequireAuth>` guard.
- Public-only routes (login) wrapped in `<PublicOnly>`.
- `SuspensePage` wrapper with `<LoadingSkeleton />` fallback.
- Vite path aliases: `@/` maps to `src/`.

## Styling

### Tailwind CSS v4

- Imported via `@import "tailwindcss"` in `index.css`.
- oklch color tokens for both light and dark themes (defined in `:root` and `.dark`).
- Font: **Geist Variable** (`@fontsource-variable/geist`).
- Radius scale from `--radius` base.

### shadcn/ui

Components in `components/ui/` are **generated** — avoid one-off product styling inside them. App-wide primitive standards such as button or tab sizing may live there when the change deliberately applies across the whole application; document those standards in this file.

#### Controls — input, button, menu, and segment sizing

Text inputs, text buttons, tabs, dropdown menus, segmented controls, and pill filters should use the same readable CTA rhythm as the setup wizard:

- Height: `h-10`
- Horizontal padding: `px-8` for normal buttons, `px-4` for inputs/selects, `px-6` for dense segmented controls
- Font size: `text-sm`
- Shape: **follow the shadcn theme radius, all derived from `--radius` in `index.css`.** Interactive text controls are `rounded-md` (buttons, filter chips, tabs/segmented items, menu items); their containers (segmented control, dropdown menu, popover) are `rounded-lg`. Never hardcode `rounded-full`, `rounded-xl`, or `rounded-2xl` on a text control or its container — a pill button next to `rounded-lg` cards reads as a foreign design system. `rounded-full` is reserved for avatars, switches, badges, and status dots; larger radii (`rounded-xl`/`rounded-2xl`/`rounded-4xl`) belong to cards and large surfaces only.

Avoid local `h-6`, `h-7`, `h-8`, `h-9`, `text-xs`, or narrow `px-2` overrides for text controls, and never re-pill a control with a `rounded-full` className that overrides the shadcn `Button`/`Tabs` primitive. OTP slots should follow the same readable `h-10` control rhythm unless a dense, space-constrained surface has a documented reason to shrink them. Reserve smaller sizing for pure icon buttons, badges, dense table data, chart labels, and non-interactive metadata.

`Label` and `CardDescription` are app-wide readable primitives (`text-sm`). Do not shrink form labels or section descriptions locally unless the element is truly dense metadata rather than an input label.

Color swatches should use a stable visible size and selection ring. Avoid
`scale-*` transforms on selected or hovered swatches because cards and dialogs
often clip overflow, which makes round swatches look cut off.

Code-copy rows and settings navigation rows follow the same readable scale:
their visible value container should be at least `min-h-10`, use `rounded-xl`
and `px-4`, and the associated action button should be full-width on mobile
then at least `min-w-36` on desktop. Avoid local `p-2`, `px-2 py-1.5`, and
`text-xs` on these rows.

Transitions must name the animated properties explicitly. Do not use
`transition-all`; prefer bounded values such as
`transition-[border-color,background-color,color]`, `transition-[width]`, or
`transition-transform`.

#### Focus styling

Picsou uses a centralized `:focus-visible` ring in `index.css` for keyboard focus
on interactive elements. Avoid one-off `focus:*`, `focus-visible:*`,
`group-focus:*`, or `focus-within:*` Tailwind classes on normal controls unless a
component needs to become visible only when focused, such as a skip link.

#### Page layout

Top-level app pages should start flush with the main content column, not centered inside `mx-auto` wrappers. Use `PageHeader` so every page title carries the same date surtitle and action alignment as the dashboard. Empty pages that are the primary page state should center their `EmptyState` in the remaining viewport height; nested empty states inside cards or modals should stay compact.

#### Color tokens — always semantic, never raw palette

All theme color tokens are defined in the `@theme` block of `frontend/src/index.css`. **Never use raw palette classes** (`text-gray-*`, `bg-gray-*`, etc.) — they bypass the theme tokens and do not adapt to dark mode.

| Intent | Use |
|--------|-----|
| Primary text | `text-foreground` |
| Muted / secondary text | `text-muted-foreground` |
| Subtle background | `bg-muted` |
| Card background | `bg-card` |
| Primary action color | `text-primary` / `bg-primary` |
| Destructive / error | `text-destructive` |

**Exception:** intentional status colors (`text-green-*`, `text-red-*`, `text-amber-*`, etc.) are fine when used as semantic UI signals (sync status badges, financial gain/loss indicators). Always include dark-mode variants or use the `dark:` prefix for those.

### Icons

Use icons from `lucide-react` as direct JSX components (e.g., `<Pencil className="size-4" />`). No other icon libraries.

## Internationalization

- react-i18next with FR (default), EN, DE, ES.
- Translation files: `frontend/src/i18n/locales/{fr,en,de,es}.json` — identical key sets; when adding a key, add it to all four files.
- Supported languages live in the `SUPPORTED_LOCALES` registry (`frontend/src/i18n/locales.ts`); selectors and `Intl` formatting derive from it — never hardcode language lists in components. Normalize raw tags with `resolveLocale()`.
- Flat keys with feature-based grouping.
- All user-visible text must use `useTranslation()` — no hardcoded strings in any language.
- Date/number formatting via `Intl.*` through the `frontend/src/lib/utils.ts` helpers (`formatDate`…), which resolve the active locale via `getLocale()`.
- **Amounts go through `<CurrencyDisplay>` or `useMoney()` (`frontend/src/hooks/use-money.ts`), never through `Intl` directly.** Privacy mode blanks every amount on screen, so a figure formatted outside that path stays legible during a demo. The raw formatter is private for that reason, and `frontend/src/lib/money-guard.test.ts` fails the build on a hand-built currency format, a `€` in JSX, or an amount-shaped value printed bare. See [`docs/features/privacy-mode.md`](../features/privacy-mode.md).
- Full details: [`docs/features/i18n.md`](../features/i18n.md).

## Types

`frontend/src/types/api.ts` mirrors backend DTO records exactly (e.g., `AccountResponse`, `GoalProgressResponse`). When a backend DTO changes, update this file to match.

## Charts

Recharts v3 for all data visualizations. Chart color tokens (`--chart-1` through `--chart-5`) are defined in the Tailwind theme.

## Scripts

```bash
bun run dev          # Dev server on :5173, proxies /api/* to localhost:8080
bun run build        # tsc + vite build (fails on type errors)
bun run typecheck    # TypeScript checking only
bun run lint         # ESLint
bun run format       # Prettier
bun run test:e2e     # Playwright E2E tests (e2e/*.spec.ts)
bunx vitest run      # Vitest unit tests (src/**/*.{test,spec}.{ts,tsx})
```

Vitest and Playwright both claim the `.spec.ts` extension, so `vitest.config.ts`
scopes `include` to `src/` — otherwise `vitest run` would try to execute the Playwright
e2e specs (which need a browser) and fail. Keep unit tests under `src/`, e2e under `e2e/`.

## Don'ts

- **Never use raw palette classes** (`text-gray-*`, `bg-gray-*`) — always semantic tokens (`text-foreground`, `bg-muted`). The gray palette is remapped to blue.
- **Never create API functions in components** — all API calls go in `features/*/api.ts`.
- **Never create hooks outside `features/`** — domain hooks live in `features/*/hooks.ts`. Only generic UI hooks (like `use-mobile`) go in `hooks/`.
- **Never use Redux, Context, or global state for server data** — TanStack Query only.
- **Never edit files in `components/ui/`** — these are shadcn/ui generated. Customize via theme tokens or the shadcn CLI. In particular, never inflate a primitive's radius away from the shadcn scale (e.g. `rounded-md`→`rounded-full` on `Button`, `rounded-lg`→`rounded-2xl` on `DropdownMenu`).
- **Never hardcode `rounded-full` / `rounded-xl` / `rounded-2xl` on interactive text controls** (buttons, filter chips, tabs, segmented items, menu items) or use a `rounded-full` className to override a shadcn primitive. Shape follows the `--radius` theme: `rounded-md` for controls, `rounded-lg` for their containers. `rounded-full` is only for avatars, switches, badges, and status dots.
- **Never use icon libraries other than `lucide-react`.**
- **Never hardcode user-visible strings** — always use `useTranslation()`.
- **Never use CSS modules, styled-components, or inline style objects** — Tailwind only.
- **Never call `Math.random()`/`Date.now()` in render** — lazy `useState(() => …)` initializer (React Compiler `purity`).
- **Never seed/reset form state in a `useEffect(…, [open])`** — use the key-remount + lazy-init pattern (`set-state-in-effect`).
- **Never use RHF `watch('x')`** — use `useWatch({ control, name: 'x' })` (`incompatible-library`).
- **Never silence a React Compiler rule with an undocumented `eslint-disable`** — only `ConnectionGuard`'s fetch-on-mount carries a commented one. `bun run lint` must stay at zero.
