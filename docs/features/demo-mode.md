# Feature: Demo mode

> Last updated: 2026-10-31

## Context

Allows running the frontend without a backend or authentication. Used for local UI testing or demonstrations. All API requests are intercepted and return mock data.

## How it works

### Activation

The `VITE_DEMO_MODE=true` environment variable in `frontend/.env` enables the mode. It is a build-time value read via `import.meta.env`.

### Request interception

At startup, if `VITE_DEMO_MODE === 'true'`, a custom Axios adapter replaces the real HTTP transport:

```
VITE_DEMO_MODE=true
  → api-client.ts injects createDemoAdapter() on api.defaults.adapter
  → every Axios request is intercepted, resolved with mock data after a random delay (200–600 ms)
```

### Auth guard

`RequireAuth` (in `frontend/src/features/auth/guards.tsx`) reads `demoMode` from the store. In demo mode, it renders the app at once without waiting for `isAuthenticated`, and still runs the session probe: the demo adapter answers `POST /auth/refresh` with the demo admin (`demo`, member 1), so pages that read the signed-in user (the Settings username row, `RequireAdmin`) get one. `PublicOnly` (login) redirects to `/` if `demoMode` is active.

### State management

`useAppStore` (zustand) exposes `demoMode`. The value is initialized from the env var and **not persisted** in localStorage (via `partialize`) — ensuring the env variable always takes precedence on reload.

### Key files

- `frontend/.env` — `VITE_DEMO_MODE=true`
- `frontend/src/lib/api-client.ts` — mock adapter injection
- `frontend/src/demo/index.ts` — `createDemoAdapter()`, route handler table
- `frontend/src/demo/data/` — mock data (accounts, goals, dashboard, transactions, holdings, sync-status, analysis)
- `frontend/src/demo/data/history.ts` — twelve monthly balances per account, the one source for account charts, `/history`, the dashboard and the goal calendars
- `frontend/src/demo/data/dates.ts` — local-date helpers; every fixture date is relative to today
- `frontend/src/stores/app-store.ts` — `demoMode` state, not persisted
- `frontend/src/features/auth/guards.tsx` — auth bypass in demo mode

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Axios adapter (not an interceptor) | Completely replaces the transport — no network request is ever sent | Response interceptor: the request is still dispatched, risking network errors |
| `partialize` to exclude `demoMode` from persist | The env var must always win; without this, a stale localStorage value overwrites `VITE_DEMO_MODE` | Persisting `demoMode`: broke demo mode if the app had previously run with `false` |

## Gotchas / Pitfalls

- **`/login` redirects to `/` in demo mode** — `PublicOnly` redirects immediately. To see the login page, disable `VITE_DEMO_MODE`.
- **Mock handlers are keyed by route** — the key is `METHOD /path` with no query string or trailing slash. A `:name` segment (`GET /accounts/:id/holdings`) matches any value and reaches the handler as `params.name`; an exact key wins over a pattern, so one id can override the default. Any new API route needs a handler added in `frontend/src/demo/index.ts`, otherwise the call returns `{}` silently — and components that `.map()`/`.filter()` the payload crash the page, or a widget waits on it forever. Per-id registration loops are what left `/accounts/{id}/holdings`, `/goals/4` and the provider `/status` routes unanswered (#277); register a pattern instead. Query params (e.g. `?split=true` on `/history`) are read from `config.params` inside the handler.
- **Figures are derived, not typed in twice** — the dashboard's net worth, distribution and liabilities follow from the accounts with `DashboardService`'s rule (pockets skipped, a liability is the amount owed); a savings goal's total is its accounts' balances and its calendar actuals are their monthly balance deltas, from which `isOnTrack` follows with `GoalService`'s rule; the family view reads the same accounts and goals. A hand-typed copy is how the dashboard came to show 41 862 € beside an Accounts total of 464 618 €.
- **No randomness in the data** — `Math.random()` only sets the response delay. The goal calendar used to draw each month's actual at random, so the same page showed different amounts on each load.
- **Format dates from local parts** — `toISOString()` on a local-midnight `Date` gives the previous day east of UTC, which put the budget's month picker on the previous month in France. Use `isoDate()` from `data/dates.ts`.
- **Artificial delay of 200–600 ms** — intentional, to simulate network latency. Do not remove it for visual testing.
- **`demoMode` is not persisted** — intentional. Do not add it to `partialize` without understanding the implications (see above).

## Tests

- `frontend/src/demo/coverage.test.ts` scans the source for every `api.get('/...')` call, expands each path parameter over every demo account, goal and category id, and fails on any URL the demo adapter has no handler for. Mutations are not checked: an unhandled one still answers `200 {}` by design.
- `frontend/src/demo/fixtures.test.ts` checks that the fixtures agree across pages (dashboard against Accounts, family against accounts and goals, goal badge against its calendar), that the calendar is identical between loads, and that the budget opens on the current month in a UTC+ zone.

The Playwright suite (`frontend/e2e/*.spec.ts`, `bun run test:e2e`) runs entirely against demo mode — it is the de-facto regression net for the handler table. Not run in CI; run it locally against a dev server started with `VITE_DEMO_MODE=true`.
