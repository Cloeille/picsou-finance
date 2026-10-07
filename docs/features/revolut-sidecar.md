# Feature: Revolut Sidecar Connector

> Last updated: 2026-07-08 (bounded Camoufox launch + unattended sync guard)
> Status: ⚠️ Code shipped + unit-tested; live end-to-end coverage still depends on a real Revolut
> approval session.

## Context

Enable Banking (PSD2) only exposes Revolut current accounts (`CACC`) — not vaults, multi-currency
pockets, crypto or investments. Revolut has no personal API, so this connector drives the Revolut
**web app** (`app.revolut.com`) through a browser sidecar and reads its internal `/api/retail/...`
endpoints, to sync all Revolut assets. Phase 1 = `app.revolut.com` (current accounts, pockets,
vaults, transactions); Invest/crypto are separate future phases (endpoints noted under Gotchas).

## How it works

The `services/revolut-auth/` sidecar (Python + FastAPI + **Camoufox** = stealth Firefox) owns a
**persistent browser profile per member** and exposes ONE data endpoint:

- `POST /sync {phoneNumber, passcode, memberId, allowLogin=true}` — first tries to **reuse the member's still-live
  profile session** (headless, no login, no mobile approval); if that session is dead/absent it does
  an **automated login** (Camoufox auto-fills phone + passcode; the user approves the push on their
  phone) then harvests. `allowLogin=false` is for unattended scheduler calls: reuse a live profile if
  possible, but return `401 SESSION_EXPIRED` instead of starting a fresh login/mobile approval.
  Returns `{accounts: [...]}` or `408 APPROVAL_TIMEOUT` / `401 SESSION_EXPIRED` /
  `409 SYNC_IN_PROGRESS` / `503 BROWSER_LAUNCH_FAILED`. The call can block up to ~5 min waiting for
  mobile approval, plus bounded browser launch and harvest time.
- `GET /progress/{memberId}` — the sidecar's live phase for the in-flight `/sync` (CHECKING_SESSION →
  LOGGING_IN → AWAITING_APPROVAL w/ countdown → HARVESTING w/ accounts-found). Purely additive; it does
  not change `/sync`'s control flow or error codes.

**Live progress + selection (backend as a background job).** The backend no longer blocks the HTTP
request for the whole sidecar call (which nginx killed at its 60 s `/api` read-timeout on a fresh login).
`POST /api/revolut/sync` now starts a background **discovery** and returns `202` + an initial
`SyncProgress`; the frontend polls `GET /api/revolut/sync/progress` (~1.5 s) — the adapter meanwhile polls
the sidecar's `/progress` and relays phase/countdown/count — and once discovery is done the member
**confirms** which accounts to import via `POST /api/revolut/sync/confirm`. Both flows are **additive**:
- **Add account** offers only not-yet-imported accounts and confirms with `voluntary:true` (re-adding a
  trash-deleted account resurrects it).
- **Sync tab / Sync-all** is automatic: it refreshes imported accounts and auto-adds new pockets, confirming
  with `voluntary:false` (a trash-deleted account is NOT resurrected). Deletion happens only via the trash
  icon — never by deselecting. The unattended scheduler still uses the synchronous `sync(...)` path
  with `allowLogin=false`, so it never waits for a phone approval when no user is present.

Auth (established by live recon): the retail API needs the httpOnly session cookie (kept in the
Camoufox profile) **plus** header `x-device-id` (= the JS-readable `revo_device_id` cookie) +
`x-browser-application: WEB_CLIENT` + `x-client-version: 100.0`. Access tokens live ~4 min and are
refreshed with `PUT /api/retail/token`. All API calls run in-page (`page.evaluate(fetch...)`), below
the app's own JS, which can't be hooked.

Java maps pockets → `CHECKING` sub-accounts (parent = wallet), money-boxes → `SAVINGS`, dedups
against Enable Banking by IBAN, and ingests transactions (feeds Budget). **Pocket nesting:** the sidecar
emits one synthetic **wallet parent** account per wallet (`externalId = wallet_id`, IBAN, `balance = sum
of its same-currency children`); pockets/vaults carry `parentExternalId = wallet_id` and become children.
This matters because `DashboardService`/`AccountsPage` **exclude children** (`parentAccountId != null`)
from net-worth totals — the parent must carry the sum, or balances are lost. Cross-currency pockets stay
top-level (the sidecar has no FX). Credentials are stored encrypted (AES-256-GCM) only if the user ticks
"remember"; otherwise they're passed per sync and not stored. Revolut is the **primary** source; Enable
Banking stays as a fallback for the current account.

### Key files

- `services/revolut-auth/main.py` — sidecar: `/sync`, `/progress/{memberId}`, Camoufox launch, login
  auto-fill, harvest + synthetic wallet-parent emission (`_with_wallet_parents`), per-member
  serialization + stale profile-lock clearing.
- `services/revolut-auth/tests/{test_profile_lock.py,test_harvest_shape.py,test_sidecar_auth.py}` —
  regression tests (concurrent-sync 409, stale-lock removal, progress read-back; wallet-parent +
  same-currency sum; shared-key 401 challenge, `/health` exemption, startup refusal);
  run `.venv/bin/python tests/test_*.py` (anyio, no pytest).
- `services/revolut-auth/Dockerfile` — Camoufox image (Firefox deps + Xvfb + `camoufox fetch`).
- `backend/.../adapter/RevolutAdapter.java` — `SidecarWebClientFactory` client → sidecar `/sync` (480 s
  timeout) + a best-effort `/progress` poll side-channel relayed into `SyncProgressService`; maps
  `401`/`408`/`409`/`503`. A 401 carrying the `Picsou-Sidecar-Key` challenge is a key mismatch
  (`SIDECAR_UNAUTHORIZED`), never `SESSION_EXPIRED`.
- `backend/.../service/sync/SyncProgressService.java` — per-member+provider live progress (single-flight
  guard, phase/countdown/count, + Revolut's harvested-but-unpersisted discovery held in memory between
  discover and confirm). `dto/{SyncProgress,DiscoveredRevolutAccount}.java`, `service/sync/{SyncProvider,
  RevolutSyncPhase}.java`, `config/SyncExecutorConfig.java` (`revolutSyncExecutor`).
- `backend/.../service/RevolutSyncService.java` — `sync` (synchronous, scheduler), `discover` (background
  harvest), `confirmSync(selected, remember, voluntary)` (additive upsert of the selection), IBAN-first upsert.
- `backend/.../controller/RevolutController.java` — `/api/revolut/{sync→202,sync/progress,sync/confirm,status,session}`.
- `backend/.../service/DashboardService.java` — excludes pocket children from net-worth AND from the
  history account-id set (the latter fixes a double-count once real pocket balances flow).
- `backend/.../model/RevolutSession.java`; `db/migration/V48__revolut_session.sql`, `V49__…credentials.sql`.
- `frontend/src/pages/sync/RevolutTab.tsx` — auto-sync tab (live phase → auto-confirm all, no selection).
- `frontend/src/components/sync/RevolutSelectionCard.tsx` — Add-account selection (only not-imported;
  child-check implies parent-check). `features/sync/revolut-phase.ts` — shared phase label.
- `frontend/src/features/sync/{api.ts,hooks.ts}` (`useSyncProgress`/`useStartRevolutSync`/`useConfirmRevolutSync`),
  `types/api.ts`, `components/shared/AddAccountModal.tsx` (RevolutWizard), `components/sync/SyncAllModal.tsx`.

### Flow

Manual (Add account / Sync tab) — background job + poll + confirm:
```
POST /api/revolut/sync ──202──> RevolutSyncService.discover(memberId, phone, passcode)  [revolutSyncExecutor]
   frontend polls GET /sync/progress ◀── adapter polls sidecar /progress (phase/countdown/count)
     → RevolutAdapter → sidecar POST /sync : reuse live session ─ yes → harvest
                                             └ no → auto-login (user approves push) → harvest
     → sidecar emits wallet parents + pockets/vaults as children → SyncProgressService.setDiscovered (in-memory)
   discovery done → frontend picks accounts →
POST /api/revolut/sync/confirm {selectedExternalIds, remember, voluntary}
   → persistSelected (parents-first upsert, IBAN-first dedup vs Enable Banking, ingestTransactions → Budget;
     additive — deselect never deletes; voluntary=true lifts trash tombstones, false leaves them)
   → upsert RevolutSession (lastSyncedAt; encrypted creds iff remember)
```
Unattended: `SchedulerService.dailyBankSync → resyncIfSessionActive` → synchronous `sync(..., allowLogin=false)`
(imports everything; only if creds remembered; reuses live session or no-ops with `SESSION_EXPIRED`).

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| **Camoufox** (stealth Firefox) | Revolut's anti-bot fingerprints & blocks a vanilla Playwright Chromium; a Firefox engine is accepted (the user's own Firefox browser logs in fine) | Playwright Chromium (blocked); patchright/Chromium (still Chromium); plain httpx (Bourso's approach — dies on JS challenges) |
| **Persistent Camoufox profile per member** | Keeps fingerprint **and** cookies stable across launches, which is what keeps a session alive | Storing a `storageState` blob and replaying it from fresh instances (random fingerprint → session invalidated) |
| **On-demand `/sync`** (reuse-or-login) | The captured session is short-lived; sync when the user asks, reuse a live session if present | Stored-session daily unattended sync (blocked until TTL is measured) |
| `playwright==1.55.0` | Camoufox 0.4.11's Firefox speaks an older Juggler protocol | 1.60+ (breaks with `Browser.setDefaultViewport`/`setContrast` errors) |
| Optional remembered creds (user's choice) | Convenience (1-click) vs security (re-enter) — the user decides | Always store / never store |
| Guard PSD2 reconstruction on `RevolutSession.lastSyncedAt` | Stand down ONLY once the sidecar produced real pockets | `provider='Revolut'` account check (matches EB-synced wallets too → breaks EB-only users) |

## Gotchas / Pitfalls

- **The sidecar requires `APP_SIDECAR_API_KEY`.** It refuses to start without it and answers every
  route except `/health`, `/progress` included, with a 401 `Picsou-Sidecar-Key` challenge unless the
  backend presents it. See [docker-deployment.md](./docker-deployment.md#sidecar-shared-secret--app_sidecar_api_key).
- **Don't hammer logins.** Many rapid logins get the account flagged (captchas, "Mauvais code" on web
  while the mobile app still works, short-lived sessions). Sessions are a rare, deliberate action.
- **Session longevity is unknown.** With active keep-alive the session still died in ~6 min — but that
  was measured while the account was flagged from over-testing (a fresh morning session lived 30+ min).
  Measure the true TTL after a ~24 h cool-down before deciding if unattended daily sync (keep-alive
  loop) is viable. On-demand sync works regardless.
- **Balances are integer MINOR units** (cents): `12345` == `123.45` → `_minor_to_major` divides by 100.
- **`/api/retail/wallets` is a dict keyed by account type** (`{PERSONAL:[...], PERSONAL_JOINT:[...],
  YOUTH:[], ...}`), NOT a list, and NOT under a `wallets` key. Each wallet has a `pockets` list.
- **Money-box balance is nested**: `{"amount": <cents>, "currency": "EUR"}` (not a flat number).
- **Pocket filtering**: money-box pockets are excluded by their `pocket.id` (else vaults appear twice —
  once CHECKING, once SAVINGS); `MERCHANT`/`REVX_FIAT` and non-fiat (crypto) pockets are dropped.
- **6-digit passcode auto-submits** — do not click "Continuer" after typing it (that hung `auto_login`).
- **One Firefox per profile — serialize per member.** A persistent Camoufox profile can be opened by
  only ONE Firefox process at a time. Two `/sync` calls for the same member (double-click, or the
  daily scheduler overlapping a manual sync) used to launch two browsers on the same profile and the
  second died with `TargetClosedError` / "Firefox is already running, but is not responding" → HTTP
  500. Fixed with a per-member `asyncio.Lock` (fast-fail `409` when one is already running; the single
  uvicorn worker makes an in-process lock sufficient) **plus** clearing stale `lock`/`.parentlock`
  files before each launch (a browser killed mid-login — container restart, OOM — leaves them and
  wedges every later sync until the volume is cleaned by hand).
- **The sidecar login runs headful under Xvfb** (Camoufox `headless=False`), so the image installs
  `xvfb` and starts `Xvfb :99` directly in `entrypoint.sh` before uvicorn. Profiles persist on the
  `revolut_profiles` docker volume.
- **Camoufox needs Firefox system libs** (gtk/dbus-glib/xtst/…), different from Chromium.
- **Headless launches silently fail without GL/Mesa libs.** `_harvest_from_profile` (the
  session-reuse path tried on *every* `/sync`, before falling back to a fresh login) launches
  Camoufox with `headless=True` — no Xvfb, no `DISPLAY`. Firefox still spawns a `glxtest`
  child process to probe GPU capabilities even in that mode, and without `libgl1`/`libegl1`/
  `libgbm1` in the image, the dynamic linker fails to resolve it — surfacing as a misleading
  `Failed to spawn child process ".../glxtest": No such file or directory` rather than a "missing
  shared library" error. The sidecar bounds Camoufox launch to `CAMOUFOX_LAUNCH_TIMEOUT_S` (default
  60s) instead of Playwright's longer persistent-context timeout. If an existing profile prevents
  launch, the sidecar moves that profile under `.quarantine/` and retries once with a clean profile.
  If launch still fails, `/sync` returns flat `503 BROWSER_LAUNCH_FAILED` so the backend logs a
  controlled connector failure rather than a cancelled reactive response.
- **Future phases (Invest / Revolut X)** are separate surfaces with stricter auth: `invest.revolut.com`
  (`/api/retail/trading/accounts`, `/trading-access/portfolios/<id>`, `/trading/v2/users/<id>/SECURITY/allocation`,
  `/trading/transactions`) and `exchange.revolut.com` (Revolut X crypto). Both need extra in-memory headers
  (`x-registered-identity`, `browser-session-id`, a longer `x-device-id`) captured via the browser.
- Dev helpers (gitignored) in `services/revolut-auth/`: `capture_login.py` (manual), `auto_login.py`
  (auto-fill), `validate_harvest.py` / `validate_persistent.py`, `keepalive_test.py`.

## Tests

- `backend/.../service/RevolutSyncServiceTest.java` — sync mapping (pockets/vaults), IBAN dedup vs
  Enable Banking, transaction dedup, remembered-vs-not credentials, always-upsert session marker row.
- Live end-to-end (login + harvest against a real account) is still pending the account cool-down.
- Note: 3 tests are red independently of this feature (`CashflowFlowServiceTest` ×3 — pre-existing
  budget NPE issues; verified via `git stash`). `RevolutPocketServiceTest` and
  `SyncServicePocketTest` no longer exist — the PSD2 pocket-guess reconstruction they covered was
  removed on 2026-07-14 (see [Links](#links) below).

## Links

- Precedent ADR: [tr-auth slim sidecar](../decisions/2026-04-25-tr-auth-sidecar-slim-image.md)
- Related: [Bank Sync](./bank-sync.md), [Trade Republic](./trade-republic.md),
  [Encryption at rest](./encryption-at-rest.md), [Budget](./budget.md)
- Supersedes: [Revolut pockets reconstruction](../decisions/2026-06-28-revolut-pockets-reconstruction.md)
  — the PSD2 heuristic reconstruction was fully removed on 2026-07-14, this connector is now the
  only source of pocket data
