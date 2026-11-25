# Frontend — CLAUDE.md

## Commands

See [`CONTRIBUTING.md`](../CONTRIBUTING.md#commands).

## Architecture (quick ref)

```
src/
  app/              providers.tsx, routes.tsx (lazy routing + guards)
  pages/            One dir per route (lazy-loaded)
  features/         Domain slices: api.ts + hooks.ts per feature
  components/
    layout/         AppSidebar, AppLayout
    ui/             shadcn/ui — DO NOT EDIT
    shared/         App-specific reusable (PageHeader, etc.)
  stores/           Zustand (auth-store, app-store)
  lib/              api-client.ts, utils.ts, constants.ts, query-client.ts
  types/            api.ts (mirrors backend DTOs), app.ts (frontend-only)
  demo/             Demo mode interceptor + mock data
  i18n/             react-i18next (FR/EN/DE/ES) — SUPPORTED_LOCALES registry in locales.ts
```

## Key patterns

**API layer (`frontend/src/lib/api-client.ts`):** Single Axios instance, `withCredentials: true`. Response interceptor silently refreshes on 401 (queues concurrent retries). Auth calls excluded to avoid loops. Feature-specific API functions live in `features/*/api.ts`.

**Server state:** TanStack Query via hooks in `features/*/hooks.ts`. No Redux, no Context for server data. Query keys co-located in hook files. Stale times in `frontend/src/lib/constants.ts`.

**Auth guard:** `RequireAuth` in `frontend/src/features/auth/guards.tsx` checks `sessionStorage.getItem('picsou_user')` (JS-readable signal; the actual session cookies are HttpOnly). sessionStorage doesn't survive a tab/browser close, so when it's empty `RequireAuth` probes the cookie-backed session once via `useSessionProbe()` (`frontend/src/features/auth/hooks.ts`, calls `POST /auth/refresh`) before redirecting to `/login` — this is what makes "Remember Me" (`persistent_token`, 90 days) actually keep users signed in across restarts instead of only within a single tab session. Because the probe's success is cached, **always log out via `useLogout()`** (never the raw `useAuthStore().logout()` action directly) — it's the only path that also revokes the session server-side and clears the query cache (`resetClientState`), so a later probe can't silently resurrect a session the UI claims is logged out. When a refresh (interceptor or probe) answers 401 `SSO_REVALIDATE`, the SPA marks `lib/sso-revalidate.ts` and `/login` restarts the IdP round trip on its own. `PublicOnly` on `/login?redirect=/oauth2/...` probes the session itself and resumes the Authorization Server request on success. `useLogout()` navigates to the IdP when logout returns an `endSessionUrl`.

**Routing:** React Router v8 in `frontend/src/app/routes.tsx`. The entry point imports `RouterProvider` from `react-router/dom` for automatic DOM `flushSync` integration. Lazy code-splitting per page. `/sync/callback` and `/sync` share SyncPage (OAuth redirect target). The SSO routes sit outside every guard, like `/consent`: `/login/sso/callback` (the IdP's redirect URI; `SsoCallbackPage` re-posts `code`/`state` to `/oauth2/sso/code/oidc` as a same-origin form POST) and `/login/sso-complete` (`SsoCompletePage` resets client state, reads the user through `POST /auth/refresh`, then navigates). The SSO start is a form POST too (`features/sso/start.ts`), never an API call. See `docs/features/sso-login.md`.

## Conventions

Full conventions (styling, icons, i18n, charts, state management details): see [`docs/conventions/frontend.md`](../docs/conventions/frontend.md)
