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
- `frontend/src/demo/data/history.ts` — twelve month-start balances per account plus today's live one, the one source for account charts, `/history`, the dashboard and the goal calendars. The dates are fixed at load with the balances; the live point is the account's current balance, so an edit (an SCPI position) shows in its history
- `frontend/src/demo/data/loans.ts` — the amortization calculation shared by loan summaries and historical outstanding balances
- `frontend/src/demo/data/dates.ts` — local-date helpers; every fixture date is relative to today
- `frontend/src/stores/app-store.ts` — `demoMode` state, not persisted
- `frontend/src/features/auth/guards.tsx` — auth bypass in demo mode

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Axios adapter (not an interceptor) | Completely replaces the transport — no network request is ever sent | Response interceptor: the request is still dispatched, risking network errors |
| `partialize` to exclude `demoMode` from persist | The env var must always win; without this, a stale localStorage value overwrites `VITE_DEMO_MODE` | Persisting `demoMode`: broke demo mode if the app had previously run with `false` |
| Shared checking-account ledger with localized display labels | Account transactions, global search, and budget views keep the same rows after categorization. Other accounts' synthetic transaction labels use French, matching the demo fixtures | Restoring a separate checking-account fixture while translating its labels |
| One categorization function with a derived review inbox | `categorizeDemoTransaction` replaces the ledger and changed row. The inbox and budget views read that ledger, so previous responses stay unchanged and refetches agree | Keeping separate mutable inbox state or two categorization functions |

## Gotchas / Pitfalls

- **`/login` redirects to `/` in demo mode** — `PublicOnly` redirects immediately. To see the login page, disable `VITE_DEMO_MODE`.
- **Mock handlers are keyed by route** — the key is `METHOD /path` with no query string or trailing slash. A `:name` segment (`GET /accounts/:id/holdings`) matches any value and reaches the handler as `params.name`; an exact key wins over a pattern, so one id can override the default. Any new API route needs a handler added in `frontend/src/demo/index.ts`, otherwise the call returns `{}` silently — and components that `.map()`/`.filter()` the payload crash the page, or a widget waits on it forever. Per-id registration loops are what left `/accounts/{id}/holdings`, `/goals/4` and the provider `/status` routes unanswered (#277); register a pattern instead. Query params (e.g. `?split=true` on `/history`) are read from `config.params` inside the handler.
- **CIC session routes return their production shapes.** Status, completion, and sync return an inactive `IDLE` session with `canReconnect: false`. Initiation and reconnect return no pending process or second factor. Session deletion returns `null`. The demo bypasses bank confirmation and keeps the existing shared financial fixtures.
- **Figures are derived, not typed in twice** — the dashboard's net worth, distribution and liabilities follow from the accounts with `DashboardService`'s rule (pockets skipped, a liability is the amount owed); a savings goal's total is its accounts' balances and its calendar actuals are their monthly balance deltas, from which `isOnTrack` follows with `GoalService`'s rule; the family view reads the same accounts and goals; the budget's allocation stock sums the live accounts per asset class with `AllocationService`'s rule (`AssetClass.of`, hidden accounts and pockets skipped). `/history`, `/history/pnl` and the intraday series compute over the request's `accountIds`, as the backend does. An account with holdings (PEA, securities, crypto) is worth its holdings repriced with `quoteDaysAgo` (`data/holdings.ts`), the curve `/prices/:ticker/history` serves: its balance, monthly history, invested amount (the holdings' cost basis) and the range P&L at `from` all follow from those quantities and quotes, as `HistoryService` values them. Each month start is priced on its own date, and the live value is a separate point dated today, as `HistoryService` appends it. The 24H series reprices the same holdings with `quoteHoursAgo`, the curve `/prices/:ticker/intraday` serves, and holds every other account at its live balance. The budget's ledger (`mockLedger`, served by `GET /transactions`) holds one cycle per month since January; cashflow totals and buckets, the spending breakdown, its drill and the Sankey sum its rows for either period, so CYCLE and YTD both reconcile with a transaction search. The uncategorized line is the review inbox, and every spending date stays inside its cycle, so on a cycle's first day the rows land on that day. A hand-typed copy is how the dashboard came to show 41 862 € beside an Accounts total of 464 618 €.
- **Categorization persists until reload.** `PUT /transactions/:id/category` calls `categorizeDemoTransaction` (`data/budget.ts`). The review inbox derives its rows from the ledger, so categorizing a row removes it from the inbox without a second state update. Unknown transaction or category IDs change nothing. The real endpoint answers 404 for these IDs; the demo adapter returns `200 {}`.
- **No randomness in the data** — `Math.random()` only sets the response delay. The goal calendar used to draw each month's actual at random, so the same page showed different amounts on each load.
- **Format dates from local parts** — `toISOString()` on a local-midnight `Date` gives the previous day east of UTC, which put the budget's month picker on the previous month in France. Use `isoDate()` from `data/dates.ts`.
- **Monthly recurring dates clamp to the next month's last day.** The upcoming calendar follows `LocalDate.plusMonths(1)`, so October 31 advances to November 30 instead of skipping November.
- **Transaction search uses the shared ledger.** `mockTransactionSearch` filters the ledger by inclusive dates, account, and category, then sorts newest first. The main checking account's `/accounts/:id/transactions` endpoint uses the same rows and filters. Salary, categorized spending, and the review inbox are included for both CYCLE and YTD. `PUT /transactions/:id/category` validates both IDs before replacing the ledger array and the changed row. Unknown transaction IDs or category IDs leave the data unchanged. The inbox, spending, category drills, budget envelopes, cashflow, and Sankey derive their responses from that ledger, so a category change appears on refetch and leaves previous responses unchanged. Spending groups the categories actually assigned, including direct parent assignments. Parent drills include their own rows and their children's rows. TRANSFER categories are excluded from income and expense totals. Changes last until reload.
- **Cashflow buckets cover the whole period.** CYCLE uses seven-day buckets with a final partial week when needed. YTD uses calendar months, ending the current bucket today. Each bucket sums only ledger rows within its inclusive dates, and the totals equal the sum of all buckets.
- **Loan history follows amortization terms.** Each monthly outstanding balance comes from `demoLoanSchedule` with that point's date. Loan summaries use the same calculation, and net-worth history negates those balances as liabilities. The summary's default date is fixed at module load, like the history dates.
- **Artificial delay of 200–600 ms** — intentional, to simulate network latency. Do not remove it for visual testing.
- **`demoMode` is not persisted** — intentional. Do not add it to `partialize` without understanding the implications (see above).

## Tests

From `frontend/`, run `bun run typecheck` and `bun run lint` alongside the demo
tests (`bunx vitest run src/demo src/features/auth/guards.test.tsx`). Vitest
transpiles TypeScript without checking for unused imports. An unused import in
`src/demo/index.ts` fails both checks and blocks `bun run build`, including the
demo build used by the Docker image.

- `frontend/src/demo/cic.test.ts` checks all six CIC session routes through the adapter, including authentication, reconnect, sync, and deletion response shapes.
- `frontend/src/demo/coverage.test.ts` scans the source for every `api.get('/...')` call, expands each path parameter over every demo account, goal and category id, and fails on any URL the demo adapter has no handler for. Mutations are not checked: an unhandled one still answers `200 {}` by design.
- `frontend/src/demo/fixtures.test.ts` freezes the clock before the fixtures load and checks that they agree across pages (dashboard against Accounts, allocation stock against the accounts, an account's history against its edited balance, family against accounts and goals, goal badge against its calendar), that the calendar is identical between loads, and that the budget opens on the current month in a UTC+ zone. Categorization checks cover invalid IDs, removal from the inbox, ledger and spending updates, previously unused categories, and TRANSFER exclusion.
- `frontend/src/demo/consistency.test.ts` rebuilds the fixtures on fixed dates (the first and last day of a month) and checks that P&L and the 24H series follow the dashboard's `accountIds`, that the investment accounts' balances, history, P&L and every hour of the 24H series equal their holdings repriced through `/prices`, that the live point is dated today, and that over CYCLE and YTD the ledger, the spending breakdown, its drill, the Sankey and the review inbox list the same rows. It compares cashflow totals and every bucket with the dated ledger, checks the checking account against global search, and compares every loan-history point with its dated installment. Categorization regressions cover inbox removal, immutable responses, parent and previously unused category assignments, TRANSFER exclusion, envelopes, cashflow, Sankey, and invalid IDs.
- `frontend/src/demo/data/budget.test.ts` checks local cycle dates and every projected recurring due date in Paris, Tokyo, and Los Angeles, including DST changes, month ends, and year ends.
- `frontend/src/demo/transactions.test.ts` checks date, account, and category filters, newest-first order, spending amounts and counts including the review inbox, and session recategorization through the adapter. Category filters select an explicitly categorized row, including on October 31, November 1, and January 1.

The Playwright suite (`frontend/e2e/*.spec.ts`, `bun run test:e2e`) runs entirely against demo mode — it is the de-facto regression net for the handler table. `playwright.config.ts` forces `VITE_DEMO_MODE=true` on the Vite dev server it starts, so a missing `frontend/.env` still runs the suite in demo mode. The specs find demo data by its displayed text (a goal or account name, a label), so renaming a fixture means updating the specs that name it: `e2e/mobile-layout.spec.ts` still looked for the goal "Vacances été 2025" after it became "Voyage au Japon". The same holds for counts that follow from a fixture's relative dates: `e2e/goals.spec.ts` counts goal 2's calendar months, which is its `createdMonthsAgo` + 1 (12) on any date.

The goals E2E test waits for each named savings goal and its progress bar, including "Voyage au Japon". Navigation alone does not establish readiness because route fallbacks wait 200 ms before appearing. The demo session probe still sets the signed-in user while `RequireAuth` renders immediately; the loading spinner only applies outside that bypass.

CI (`.github/workflows/ci.yml` job `e2e`) installs Chromium, runs the same command, and uploads `playwright-report/` on failure. A red e2e job blocks the Docker publish job.
