# Feature: Demo mode

> Last updated: 2026-08-13

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

`RequireAuth` (in `frontend/src/features/auth/guards.tsx`) reads `demoMode` from the store. In demo mode, it passes through without checking `isAuthenticated`. `PublicOnly` (login) redirects to `/` if `demoMode` is active.

### State management

`useAppStore` (zustand) exposes `demoMode`. The value is initialized from the env var and **not persisted** in localStorage (via `partialize`) — ensuring the env variable always takes precedence on reload.

### Key files

- `frontend/.env` — `VITE_DEMO_MODE=true`
- `frontend/src/lib/api-client.ts` — mock adapter injection
- `frontend/src/demo/index.ts` — `createDemoAdapter()`, route handler table
- `frontend/src/demo/data/` — mock data (accounts, goals, dashboard, transactions, holdings, sync-status, analysis)
- `frontend/src/stores/app-store.ts` — `demoMode` state, not persisted
- `frontend/src/features/auth/guards.tsx` — auth bypass in demo mode

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Axios adapter (not an interceptor) | Completely replaces the transport — no network request is ever sent | Response interceptor: the request is still dispatched, risking network errors |
| `partialize` to exclude `demoMode` from persist | The env var must always win; without this, a stale localStorage value overwrites `VITE_DEMO_MODE` | Persisting `demoMode`: broke demo mode if the app had previously run with `false` |

## Gotchas / Pitfalls

- **`/login` redirects to `/` in demo mode** — `PublicOnly` redirects immediately. To see the login page, disable `VITE_DEMO_MODE`.
- **Mock handlers are keyed by exact route** — the key is `METHOD /path` with no query string or trailing slash. Any new API route needs a handler added in `frontend/src/demo/index.ts`, otherwise the call returns `{}` silently — and components that `.map()`/`.filter()` the payload crash the page. This bit the dashboard (`GET /history`), `GET /history/pnl`, intraday, access keys, MFA status, sessions, and family members until handlers were added (2026-07-07). Query params (e.g. `?split=true` on `/history`) are read from `config.params` inside the handler.
- **Artificial delay of 200–600 ms** — intentional, to simulate network latency. Do not remove it for visual testing.
- **`demoMode` is not persisted** — intentional. Do not add it to `partialize` without understanding the implications (see above).

## Tests

The Playwright suite (`frontend/e2e/*.spec.ts`, `bun run test:e2e`) runs entirely against demo mode — it is the de-facto regression net for the handler table. Not run in CI; run it locally against a dev server started with `VITE_DEMO_MODE=true`.
