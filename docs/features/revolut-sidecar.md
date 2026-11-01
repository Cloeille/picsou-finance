# Feature: Revolut Sidecar Connector

> Last updated: 2026-10-31 (hCaptcha content-quad clipping; shared architecture-aware Camoufox base; legacy Enable Banking coexistence and IBAN log privacy)
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
  `409 SYNC_IN_PROGRESS` / `503 BROWSER_LAUNCH_FAILED` / `504 LOGIN_SCREEN_TIMEOUT` /
  `403 HUMAN_VERIFICATION_REQUIRED` / `424 UPSTREAM_FORMAT_CHANGED`. The call can block up to ~5 min
  waiting for mobile approval, plus bounded browser launch and harvest time.
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
against Enable Banking by IBAN, currency, and member, and ingests transactions (feeds Budget). **Pocket nesting:** the sidecar
emits one synthetic **wallet parent** account per wallet (`externalId = wallet_id`, IBAN, `balance = sum
of its same-currency children`); pockets/vaults carry `parentExternalId = wallet_id` and become children.
This matters because `DashboardService`/`AccountsPage` **exclude children** (`parentAccountId != null`)
from net-worth totals — the parent must carry the sum, or balances are lost. Cross-currency pockets stay
top-level (the sidecar has no FX). Credentials are stored encrypted (AES-256-GCM) only if the user ticks
"remember"; otherwise they're passed per sync and not stored. Revolut is the **primary** source; Enable
Banking stays as a fallback for the current account.

After upgrading from an Enable Banking version that did not import IBANs, a separate legacy
Enable Banking row can already coexist with a sidecar row for the same wallet. Enable Banking
now prefers its exact uid and leaves its IBAN unset when the sidecar owns that IBAN and currency.
It preserves both rows and their history without moving the uid onto the sidecar row or making
Revolut's IBAN lookup ambiguous. If the legacy Enable Banking row is deleted, its uid blocks
Enable Banking's IBAN fallback, so later syncs leave the sidecar row and its transactions alone.
See [bank-sync.md](./bank-sync.md#account-matching-resilience-enable-banking-0164).

Discovery and persistence use the same currency-aware IBAN match, then fall back to the external
account id and member. EUR and GBP accounts imported by Enable Banking can share an IBAN without
breaking Revolut's selection preview or overwriting each other's balances. Missing or blank Revolut
currencies default to EUR; other values are trimmed and uppercased before matching and creation.
The IBAN-based soft-delete guard also includes currency. Deleting one currency leaves other
currencies eligible for import. Voluntary selection restores only the selected external id or
IBAN and currency, so selecting EUR leaves a deleted GBP wallet deleted.
When this guard skips a deleted account, its INFO log contains only
`LogSanitizer.fingerprint(iban)` for correlation, never the full IBAN.

Accounts keep their native balances. Snapshot balances and invested amounts are stored in EUR,
using `AccountService.upsertSnapshotFromNative`. Confirming a GBP wallet with an unchanged balance
of GBP 200 at a synthetic GBP/EUR rate of 1.20 keeps both snapshot amounts at EUR 240, including
when Revolut matches an account previously imported by Enable Banking.

### Transactions for pockets and vaults

The harvest fetches the existing bounded 90-day transaction window for every real
fiat pocket, including money-boxes/vaults. A vault keeps its persisted money-box
`externalId`; a private harvest mapping uses its nested `pocket.id` for
`internalPocketId`. Synthetic wallet parents are never queried. A real pocket
whose ID equals its wallet ID is still queried.

Transaction collection fails closed with HTTP 502 if an upstream request fails,
the response is malformed, the cursor stops advancing before the 90-day cutoff,
or the page cap is reached. A valid empty transaction list remains a successful
empty history. A failed harvest is not presented as a complete partial import.
This rejects the whole sync, including balance updates for other accounts, if a
very active pocket exhausts the page limit inside the window. Per-account partial
success is not implemented. Until FX conversion is available, non-EUR accounts
keep their balances but their transactions are not imported into EUR Budget and
Cashflow totals. The same rule applies per transaction: a leg whose own
`currency` is not EUR is skipped even on an EUR pocket, and a stored row's
`native_currency` is the leg's currency when it has one, else the pocket's. A foreign leg
that arrives without a currency (a debit `Exchanged to EUR` or a credit `Exchanged from
EUR`) is dropped by `RevolutSyncService`, because the pocket fallback would book it as EUR
at the wrong scale. In one pocket's feed, two rows with the same id and different
currencies are both kept: they are the two legs of one exchange.

Before #251 the foreign leg of an exchange was stored as EUR at the wrong scale
(units x 10^6, so 10x to 20x its euro value for BTC, 10^7x for a 0.10 EUR coin).
`V118__revolut_exchange_leg_repair.sql` deletes it, row by row, from the wording and
the sign. `Exchanged to EUR` means EUR was received, so the EUR leg is the credit and a
debit with that description is the foreign leg. `Exchanged from EUR` means EUR was spent,
so the EUR leg is the debit and a credit with that description is the foreign leg. The
row goes when its account is a Revolut account (soft-deleted ones included), it is not
manual, it has an `external_id`, it matches one of those two cases (case and spacing
ignored), and either it is stamped `EUR` or another Revolut account of the same member
holds a row with the same `external_id` and the opposite sign. It works whether Revolut
gives each leg its own description or both legs the same one.

An EUR-stamped row needs no partner. The old sidecar never read crypto pockets, so both legs
came from the EUR pocket's feed under one id, and the per-pocket dedup kept only the first.
When the foreign leg came first, it is the only row of that exchange. The EUR leg of the same
wording has the opposite sign, so it never matches. A row stamped in another currency (a USD
pocket's real leg) still needs a partner.

The rule ignores amounts, because the gap depends on the asset's price. It ignores
`external_transaction_id` and the number of rows per exchange, because legacy rows have no
`external_transaction_id` and an exchange can span duplicate Revolut accounts, so it can
hold more than two rows. A pair between two non-EUR currencies is left alone. The wording is
English only; a row in another language is not repaired.

The deleted rows are first copied to `transaction_v118_backup`, so a manual category stays
recoverable. To undo, run `INSERT INTO transaction SELECT * FROM transaction_v118_backup;`,
then drop the table once the result is checked. The undo works while `transaction` has not
lost a column, and it does not restore `ai_call_log.transaction_id` (set to NULL by the
delete). Recurring series are only refreshed by the
next detection run (any sync, or `POST /api/recurring/detect`). `docker/deploy.sh` takes a
`pg_dump` before migrating; any other deployment should take one first.

The source's generic `TRANSFER` type alone does not prove an own-account move.
Both legs are classified as `TRANSFER` only when exactly two distinct harvested
accounts share a transaction ID, date and currency, with opposite nonzero
amounts and source type `TRANSFER` on both legs. Source-type bookkeeping is
removed before serializing the response. Unmatched, ambiguous and different-ID
legs are not classified by this detector. This conservative contract is covered
by synthetic parser fixtures, not a captured live bank response; real-account
confirmation remains necessary for Revolut payloads using other identifiers.

An unused account with no recent activity can validate login and empty-history
handling only. It cannot validate pocket/vault activity or mirrored transfers.
The remaining live acceptance needs a maintainer with existing recent activity;
do not generate financial activity just to exercise this connector.

Run the Python tests with real installed dependencies:
`PYTHONPATH=../base python -m pytest -p no:logging --asyncio-mode=auto -q tests` from
`services/revolut-auth/`. CI's
`revolut-auth-sidecar` job runs the same command inside the built image, after
installing pinned `pytest` and `pytest-asyncio`, and image publishing waits for it.

The sidecar uses `services/base/picsou_sidecar` for app creation, shared-key
authentication, `/health`, and sanitized logging. The local `captcha` module remains
responsible for challenge detection during screen selection, after passcode entry,
and while polling for approval; shared app initialization does not replace it.

### Key files

- `services/revolut-auth/main.py` — sidecar: `/sync`, `/progress/{memberId}`, Camoufox launch, login
  auto-fill, harvest + synthetic wallet-parent emission (`_with_wallet_parents`), per-member
  serialization + stale profile-lock clearing.
- `services/revolut-auth/captcha.py` owns the shared challenge detector. It checks hCaptcha frame
  URLs and visible geometry throughout login, including nested frames.
- `services/revolut-auth/tests/{test_profile_lock.py,test_harvest_shape.py,test_sidecar_auth.py}` —
  regression tests (concurrent-sync 409, stale-lock removal, progress read-back; wallet-parent +
  same-currency sum; shared-key 401 challenge, `/health` exemption, startup refusal);
  run `PYTHONPATH=../base .venv/bin/python tests/test_<name>.py` from
  `services/revolut-auth/` (anyio, no pytest).
- `services/revolut-auth/Dockerfile` — thin non-root layer on the same `camoufox`
  base digest as AMEX. Common FastAPI/uvicorn pins come from `services/base/requirements.txt`;
  `picsou_sidecar` comes from the same base (no `SIDECAR_LIB` copy); the old
  per-sidecar requirements and browser downloads are removed. The image copies both `main.py`
  and `captcha.py`, and uses `/opt/sidecar-base/entrypoint.sh` with port 8002, display `:99`
  and a `1280x1024x24` Xvfb screen; no local entrypoint is needed.
- `services/base/pin_camoufox.py` — installs Firefox 152.0.4-beta.30 for the target
  CPU (`lin.x86_64` or `lin.arm64`), GeoIP and addons once in the base. The GeoIP
  download uses a direct release asset URL to avoid GitHub API rate limits.
  `services/base/test_pin_camoufox.py` covers both CPUs and direct downloads.
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
  child-check implies parent-check). `features/sync/revolut-phase.ts` — shared phase and error labels.
- `frontend/src/features/sync/{api.ts,hooks.ts}` (`useSyncProgress`/`useStartRevolutSync`/`useConfirmRevolutSync`),
  `types/api.ts`, `components/shared/AddAccountModal.tsx` (RevolutWizard), `components/sync/SyncAllModal.tsx`.
- `SyncAllModal` syncs a remembered Revolut session in place (discover → auto-confirm all discovered
  accounts with `remember=true`, `voluntary=false`, so the stored credentials survive and deleted accounts
  stay deleted). Without remembered credentials the row button opens the Revolut tab. "Sync all" never navigates.
- The in-place discovery/confirm flow is owned by the dashboard modal. Closing the modal keeps
  its component mounted, but navigating away from the dashboard unmounts it: discovery may
  finish on the server without the client sending confirm, so no accounts are imported by
  that attempt. Stay on the dashboard until the row finishes, or retry from the Revolut tab.
  Moving quick-sync orchestration above the page is a separate lifecycle improvement.

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
| `playwright==1.64.1` | Reviewed with the pinned Firefox 152.0.4-beta.30 protocol and the shared headless and headful offline smoke tests | Upgrading without testing the pinned browser |
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
- **Transaction amounts use their own currency's scale.** A transaction carries its own
  `currency`, which can differ from its pocket's: the source leg of an `Exchanged to EUR` is in
  the source asset. `_fetch_transactions` emits that `currency` and divides a fiat leg by 100
  and any other (crypto) leg by 10^8. The 10^8 scale comes from a community client
  (tducret/revolut-python), not from a captured payload. A malformed `currency` fails closed
  with `502 INVALID_DATA` for the whole sync, like a malformed amount; an absent one means the
  pocket's (#251). Every fiat leg is read as cents, which is wrong for 0-decimal (JPY) and
  3-decimal (KWD, BHD) currencies. It does not matter while only EUR legs are booked.
- **`/api/retail/wallets` is a dict keyed by account type** (`{PERSONAL:[...], PERSONAL_JOINT:[...],
  YOUTH:[], ...}`), NOT a list, and NOT under a `wallets` key. Each wallet has a `pockets` list.
- **Money-box balance is nested**: `{"amount": <cents>, "currency": "EUR"}` (not a flat number).
- **Pocket filtering**: money-box pockets are excluded by their `pocket.id` (else vaults appear twice —
  once CHECKING, once SAVINGS); `MERCHANT`/`REVX_FIAT` and non-fiat (crypto) pockets are dropped.
- **Login waits for the first SSO screen; it never sniffs the URL.** `app.revolut.com` redirects to
  `sso.revolut.com/signin`, and a known device (persistent profile) only lands on `/passcode` after a
  delay, while a fresh one renders the phone input. `_wait_for_login_screen` polls (bounded by
  `LOGIN_SCREEN_WAIT_S`, 30 s) for whichever appears first, and after the phone step waits again for
  the passcode screen (no fixed sleep after the phone step: the poll is the wait). A screen counts only
  once its **inputs** exist (6 textboxes, or `input[type=password]` / `input[inputmode=numeric]` for the
  passcode); the URL is ignored because the SPA switches to `/passcode` before the boxes render, and
  typing then hits nothing. Deciding after a fixed sleep used to pick the phone branch on a stale `/signin`
  URL and time out on a phone input that never came (HTTP 500). If neither screen appears the sidecar
  returns `504 LOGIN_SCREEN_TIMEOUT`; Java has no dedicated mapping, so it logs the body and shows the
  generic "Failed to sync Revolut accounts" message.
- **The approval wait starts only after the passcode was typed.** Revolut can answer the phone step
  with an **hCaptcha image challenge**: the page stays on `/signin` with the phone field, an
  hCaptcha frame is shown, and no push is ever sent in that observed case. `_wait_for_login_screen`
  also returns `"captcha"` when `captcha.visible(page)` finds a rendered hCaptcha frame,
  which ends the wait at once. The detector walks `page.frames` and matches only HTTP or HTTPS
  URLs on `hcaptcha.com` or its subdomains. A URL containing that string on another host does
  not count. Each matching frame and its parent frames must have visible screen space in common.
  Firefox's `checkVisibility` checks the frame's visibility and opacity, including ancestor display
  and opacity. An iframe with `visibility:visible` can override an ancestor's `visibility:hidden`
  or `visibility:collapse`.
  Ancestor `display:none` and zero opacity still hide the frame. A one-shot `IntersectionObserver`
  applies overflow clipping and viewport bounds, then disconnects. The browser follows the CSS
  containing-block chain, so a fixed or absolute frame can escape an ancestor's overflow clip when
  its containing block is outside that ancestor. A transformed ancestor that establishes the
  containing block still clips the frame. Overflow clips at the transformed padding box, excluding
  borders. A zero-height or zero-width padding box does not expose a challenge, even after rotation.
  For `hidden`, `auto`, and `scroll` overflow, the detector rejects empty padding quads along the
  containing-block chain before trusting the observer's axis-aligned rectangle.
  Rotating an empty clip can give that rectangle nonzero area.
  It checks the quad's coordinates rather than integer `clientWidth` or `clientHeight`, so a fractional
  padding box can still expose content after scaling. Ancestors outside a positioned frame's
  containing-block chain do not apply an overflow clip.
  Root overflow and body overflow propagated to the viewport use the observer's viewport clip.
  `overflow:clip` also stays with the observer because `overflow-clip-margin` can expand its clip.
  The observer's border-box intersection is restricted to the iframe's transformed content quad,
  obtained through Camoufox's `getBoxQuads({box: 'content'})`. The quad is clipped exactly against
  the observer's rectangle, so a clip that exposes only an empty corner of a rotated frame's
  axis-aligned bounds does not count. A frame with no content quad is hidden. Layout size does not
  matter, so a sub-pixel frame scaled by its own transform still counts. Browser launch and the
  offline fixture enable Firefox's `layout.css.getBoxQuads.enabled` preference for this geometry API.
  Visibility and content bounds are checked again when the asynchronous observer callback runs,
  so a frame hidden while the observation is pending does not count as an active challenge.
  A strip showing only the iframe's own border or padding does not expose a challenge.
  The visible content must exceed one pixel on both axes.
  Zero-size and one-pixel content frames do not count, even when their borders have a larger box.
  Playwright's `:visible` alone includes transparent and offscreen elements and cannot inspect nested
  documents. Frame elements are released after each check. A detached frame is ignored for that poll.
  Unexpected browser errors still propagate. The captcha is checked before
  passcode and phone inputs because a challenge can block interaction with either screen.
  The sidecar also checks immediately after passcode entry and before each approval poll.
  A detected challenge returns a flat `403 HUMAN_VERIFICATION_REQUIRED`. If it appears during the
  approval wait, the sidecar replaces progress with `LOGGING_IN`, removing the elapsed and remaining
  seconds before returning the error. The backend then reports the translated terminal error.
  The error text does not claim whether a push was sent. The visible challenge cannot establish that.
  French sign-in errors address the member as "vous", including the blocked-sign-in message.
  A missing passcode screen after the phone step, a missing passcode input, or a Playwright timeout
  during login returns a flat `424 UPSTREAM_FORMAT_CHANGED`. The timeout handling covers navigation,
  screen waits, and input interactions that previously caused a 500. The same code also covers a rejected phone number
  (Revolut keeps `/signin` with an inline error) and a slow network, so the user-facing wording says
  "the sign-in didn't go through, check your phone number" rather than blaming the page. `424` because `502` is already the harvest's
  `UPSTREAM_UNAVAILABLE`/`INVALID_DATA`, which the backend treats as a generic failure.
  A challenge detected before approval polling never starts the countdown. A later challenge ends
  the wait on the next poll instead of consuming the remaining 300 s budget.
  The challenge is **reported, not solved**: letting the member complete it (remote view of the sidecar
  browser) is not implemented, and no third-party solver is used. A successful login on the persistent
  profile may avoid the challenge on later syncs, which is unverified.
  `tests/test_login_screen.py` covers captcha precedence over each passcode input shape, a challenge
  after passcode entry, and one during approval, alongside the nominal and timeout paths. These
  overlapping and later challenge states are synthetic regressions, not captured live bank pages.
  `tests/test_captcha_detection.py` covers URL matching, nested frame traversal, clipping between
  parent frames, and detachment. It can also run with `python -m unittest discover -s tests
  -p test_captcha_detection.py`. `tests/test_captcha_browser.py` runs offline Camoufox fixtures for
  CSS visibility, nested documents, and a challenge inserted after the first poll. Positioned-frame
  fixtures cover escaped overflow and clips imposed by positioned or transformed containing blocks.
  Bordered-wrapper fixtures cover empty and partially visible padding boxes with scaling and rotation.
  Visibility-override fixtures retain ancestor display and opacity checks. Bordered-iframe fixtures
  cover strips with no content and partially visible content on both axes under scaling and rotation.
  Frames with sub-pixel layout size and their own scale, and clips over a rotated frame's quad or
  only its bounding-box corner, cover the content-quad geometry.
  The tests route all browser requests to local fixture responses and never contact Revolut or hCaptcha.
  The fixture browser excludes Camoufox's bundled uBlock Origin (`exclude_addons=[DefaultAddons.UBO]`):
  its blocking `webRequest` listener intermittently stalls http(s) requests, sometimes for the
  browser's remaining life, which made `Page.set_content` time out waiting for the iframe's load event.
  Human challenge completion and the proposed Camofox runtime evaluation remain follow-up work.
- **Backend and UI carry the code, not the sentence.** `RevolutAdapter` maps `403`/`424` to
  `SyncException` with the code as both message and `getCode()`; `RevolutSyncService.friendly()` turns
  them into English sentences, and `discover` passes the code to `SyncProgressService.error`, which
  exposes it as `SyncProgress.errorCode`. The three Revolut surfaces (Sync tab, Add-account wizard,
  Sync-all row) render `revolutErrorLabel` (`features/sync/revolut-phase.ts`), which translates the code
  (`sync.revolut.errors.*`) and falls back to the English sentence for any other failure. The
  unattended scheduler path keeps its generic `FAILED` result.
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
  `xvfb` and starts `Xvfb :99` directly through `services/base/entrypoint.sh` before uvicorn.
  Port 8002, screen 1280x1024x24 and the 65-second keep-alive are preserved. Both services
  clear stale Xvfb locks on restart and fail explicitly without a ready display.
  Shared browser assets live in `/opt/camoufox-cache/camoufox`; profiles persist on the
  `revolut_profiles` docker volume. The `revoauth` user is pinned to UID 999 to
  retain access to volumes created by earlier images, including mode-700 member
  directories. Its group remains automatically allocated; profile access depends
  on the owner UID.
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

- `services/revolut-auth/tests/test_captcha_browser.py` covers offline Camoufox visibility, opacity
  changes between polls and during observation, nested frames, positioned frames escaping overflow,
  rotated empty padding boxes, viewport overflow propagation, and fractional padding boxes that
  expose content after scaling. `test_captcha_detection.py` covers host matching, frame traversal,
  detached frames, and unexpected browser errors.
- `.github/tests/test_sidecar_workflows.py` ensures the Revolut CI job checks
  that the built image runs as UID 999, preserving ownership of existing profiles.
- `services/base/test_entrypoint.py` covers the shared Xvfb startup, the per-service
  settings declared in each Dockerfile (checked against fixed values), stale display cleanup
  and explicit failures. `services/base/smoke_camoufox.py` checks the installed ELF
  architecture and launches headless/headful Firefox against offline HTML, resolving a fixed
  IP through the installed GeoIP database. CI runs both derived non-root images with networking disabled on native
  ARM64 and on AMD64. These checks do not verify bank login compatibility.

- `services/revolut-auth/tests/test_login_screen.py` — fake-page tests of `sync()`: known device /
  phone screen nominal paths, passcode URL before its inputs render, `?redirect=…passcode` query
  string, single-field passcode fallbacks, `504 LOGIN_SCREEN_TIMEOUT`, captcha after the phone step
  and on the first screen (also over a visible phone field; `403`, approval loop never entered),
  captcha over each passcode input shape, after passcode entry, and during approval with countdown
  removal, and the unchanged `408 APPROVAL_TIMEOUT` when no challenge appears,
  unknown post-phone state / missing passcode input / Playwright timeout in `goto` or a login step
  (`424`). Needs `pytest` + `pytest-asyncio` (see the run command above).
- `RevolutAdapterTest` (`403`/`424` mapping), `RevolutSyncServiceTest` (code + friendly message on
  `discover`/`sync`), `SyncProgressServiceTest` (`errorCode`), and `features/sync/revolut-phase.test.ts`
  plus `SyncAllModal.test.tsx` (translated captcha message, no countdown).
- `backend/.../service/RevolutSyncServiceTest.java` — sync mapping (pockets/vaults), IBAN dedup vs
  Enable Banking, transaction dedup, remembered-vs-not credentials, always-upsert session marker row.
- `SyncServiceCurrencyMatchTest` persists synthetic Enable Banking accounts with a shared IBAN,
  then runs Revolut discovery and confirmation for EUR, GBP, and missing-currency wallets. It
  checks currency-specific updates, EUR snapshot balances and invested amounts, deletion guards,
  and voluntary restoration against real repository queries on H2.
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
