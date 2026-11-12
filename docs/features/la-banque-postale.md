# Feature: La Banque Postale sync (sidecar, replaces Enable Banking)

> Last updated: 2026-11-06
> Status: 🟢 **Live-validated by the author on a test stack; the maintainer's
> live run is still pending.** The backend contract, storage, adapter, sync
> service and REST endpoints are in place. `services/lbp-auth`
> runs the login in headless Chromium (base `chromium`) so the bank's anti-fraud
> scripts set `iscdName`; the keypad is clicked in that browser and the digits
> are never typed anywhere. After the keypad, the sidecar clicks the bank's
> strong-authentication notice (**Continuer**), which is what sends the mobile
> notification, then waits for the approval. The session cookies are replayed
> with `httpx` for the server-rendered pages. A live run on 2026-11-06 imported
> the current account, a Livret A and a LEP with 27 transactions, and a second
> sync 15 seconds later reused the stored cookies. The bank's web session is
> short-lived: a sync after ~9 minutes of inactivity was refused, so the
> connector needs a fresh login + A2F once the bank has logged out.

## Context

Enable Banking is PSD2-scoped: it reaches La Banque Postale payment accounts but
not the regulated savings products (Livret A, LDDS, LEP, Compte sur Livret,
Livret Jeune, PEL/CEL). Those products are visible in the bank's own web client
*Comptes & Contrats → Épargne et placements*, so a sidecar can read them where
PSD2 cannot.

This connector is a **full replacement** for Enable Banking on La Banque Postale
accounts, not a savings-only add-on: one source of ids, one sync history, no
duplicate current account. See
[the ADR](../decisions/2026-11-05-la-banque-postale-sidecar.md).

When switching, delete the old Enable Banking **account(s)**, not just the
connection: deleting a connection leaves its account orphaned (V76 sets
`requisition_id` to NULL) and its balance still counts. Deleting the last account
of a connection removes that connection too.

## Scope

**Target scope**

- Current account and its transactions.
- Livret A, LDDS, LEP, Compte sur Livret, Livret Jeune (balances and, when the
  bank exposes them, transactions).
- PEL / CEL as savings accounts, with their balances.
- The deferred-debit card as a `CREDIT_CARD` liability (later slice).
- Personal and home loans as `LOAN` accounts with a `Debt` (later slice).

**Out of scope (first slice)**

- Life insurance and PER (separate insurer flows, like Caisse d'Epargne's).
- PEA and securities accounts.
- Automatic re-login when the session expires (no credentials are stored).
- Bank logout on disconnect.
- IBAN-based matching with Enable Banking accounts.

## How it works

```
Sync page (La Banque Postale tab)
  │  user connects: identifier → keypad → mobile approval → session
  ▼
POST /api/lbp/auth/initiate ──► lbp-auth /initiate ──► bank login page
POST /api/lbp/auth/keypad   ──► lbp-auth /keypad   ──► clicked positions only
POST /api/lbp/auth/complete ──► lbp-auth /complete ──► wait for the mobile approval
                                     │
                                     ▼
                              encrypted session cookies
                                     │
POST /api/lbp/sync ───────────────► /accounts ──► normalized snapshot
                                     │
                                     ▼
                    LbpSyncService: one DB transaction
                    (accounts + transactions + snapshots)
```

The sidecar's backend-facing contract is **ours** and does not depend on how the
bank exposes its data. Only the transport below `/initiate` and `/accounts` is
bank-specific.

### Normalized snapshot

`POST /accounts` answers `{accounts, unsupported}`. Each account carries:

| Field | Meaning |
|---|---|
| `externalId` | SHA-1 of the bank's displayed account number, prefixed `lbp_` by the backend. The per-session `compte.numero` UUID is **not** stable and is used only for the statement URL. |
| `kind` | `CURRENT_ACCOUNT`, `LIVRET_A`, `LDDS`, `LEP`, `CSL`, `LIVRET_JEUNE`, `PEL`, `CEL`, `CARD`, `LOAN` |
| `name` | As the bank shows it |
| `balance` | Signed; a card's is its outstanding (≤ 0), a loan's is the bank's signed capital |
| `currency` | `EUR` only |
| `iban` | Never persisted by the backend |
| `ceiling`, `remainingDepositCapacity`, `fillingRatio` | Reserved: the sidecar never sends them today, the backend ignores them |
| `cardNature`, `parentExternalId`, `nextDueDate` | Cards only (`parentExternalId` reserved: never sent, never persisted) |
| `transactions` | `{externalId, date, dueDate, amount, currency, label, typeCode}` |
| `snapshotComplete` | The sidecar read the whole account, not a partial page |

`unsupported` is part of the snapshot contract but La Banque Postale currently
always emits `[]`: any product the bank shows but the sidecar does not import
refuses the whole sync as a format change instead of being listed — nothing is
partially imported. (When provisional kinds are introduced for LBP, they will
be listed here like the BPCE parser does.)

No real empty state has ever been observed: a synthesis page with an account-list
container but zero cards only parses as `[]` together with an explicit
"Aucun compte"-style message; without one it fails closed, so a customer holding
only a current account may see the sync refused until such a marker is captured
live.

### Login (built from two live captures)

The client must enter through `identif.ea?origin=particuliers`: that page sets
the WAF/SSO cookies and redirects to the login form. A direct
`/wsost/OstBrokerWeb/loginform` request is refused (403, message page).

The keypad is **ten text buttons shuffled on every page load**. The bank's own
script writes the clicked **DOM positions**, not the digits, into the `password`
field; the server maps them back with the per-load keypad. The sidecar returns
the shuffled labels to the UI, the user clicks their digits, and the clicked
positions travel back to the backend. The labels and the positions are together
**password-equivalent** (the server maps positions to digits), so they are held
only in transit and never logged or stored: Picsou keeps only the encrypted
cookie session, never the password.

A trusted device skips the second factor. A new device gets *Votre appareil de
confiance* ("une notification a été envoyée vers …"), and the sidecar polls the
page's `URL_AF_POLLING` every 3 s until the bank redirects. No SMS path was
observed, but an additive `/otp` step could be added without changing the rest
of the contract.

The backend can remember the **identifier** (10 digits) when the user ticks the
box: it is encrypted with `CryptoEncryption` and returned to the form on the next
login. The password and the keypad positions are still never stored; unticking
the box clears the remembered identifier on the next successful login.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /initiate` | `{customerId}` | `{processId, keypad: {keys[10], columns}, expiresInSeconds}` |
| `POST /keypad` | `{processId, positions}` | `{processId, status: "APPROVAL_PENDING"}` |
| `POST /complete` | `{processId}` | `{sessionState}` |
| `POST /token-check` | `{sessionState}` | `{ok, expiresIn}` |
| `POST /accounts` | `{sessionState}` | `{accounts, unsupported}` |

- Picsou never stores the password. The keypad labels and the clicked positions
  — together password-equivalent — are held only in transit and never logged or
  stored; the approval carries no secret at all.
- No login step is retried: a wrong password can lock the bank account.
- An unknown, used, expired or other-member `processId` is refused before the
  sidecar is called.
- Pending logins live in memory only; a restart drops the attempt.
- `iscdName` is a 52-character device fingerprint set by the bank's anti-fraud
  scripts. The login runs in headless Chromium so those scripts execute and set
  it; the keypad is clicked there, never in the backend.
- Request allow-list, split in two. Document destinations (httpx hops,
  top-level navigations, redirects, websockets, every Chromium Document hop)
  must be the bank origin or another `*.labanquepostale.fr` host — never a
  CDN, since anyone can register one. The two live-observed CloudFront
  distributions are pinned by exact hostname and allowed only as
  non-Document subresources (scripts, images). Everything else fails
  closed: link-local, metadata and arbitrary hosts, userinfo, backslashes,
  control characters, non-443 ports and malformed labels. The exact-origin
  version (added for the security review) broke the live login — the keypad
  scripts and anti-fraud payloads come from CloudFront and from
  `transverse.labanquepostale.fr`, and blocking them made the bank refuse the
  credentials. The `*.cloudfront.net` interim step proved too broad (anyone
  can create a distribution) and was replaced by pinning. Blocked requests
  log the host only, never the URL.
- Observed host inventory (one full live login + sync, all Documents on bank
  domains, no off-bank XHR): `voscomptesenligne.labanquepostale.fr`
  (documents, scripts, XHR, httpx replays), `transverse.labanquepostale.fr`
  (the `message.html` notice page, fonts, images, scripts),
  `www.labanquepostale.fr` and `www.services.labanquepostale.fr` (scripts),
  `dqnjn206bwvk2` / `d2ydsn9mah1r4u.cloudfront.net` (anti-fraud `fruprem.js` /
  `loreo.js` scripts only). Nothing else is needed; anything else fails
  closed.

### Storage

Table `lbp_session`, its own table (the sidecar banks of #423 and Banque Populaire
since #478 share `sidecar_session`; moving La Banque Postale there is a follow-up): one row per member,
`session_state` encrypted with `CryptoEncryption`, `encrypted_identifier` (the
optional remembered 10-digit identifier, also encrypted), `last_validated_at`,
`is_active`, `sync_status`, `last_sync_started_at`, `last_sync_completed_at`,
`last_sync_error` (CHECK on the error codes), `unsupported_products` (reserved,
always empty today). Deliberately no password or keypad-position column.

A session the bank rejects (`SESSION_EXPIRED`) or that cannot be decrypted
(`INVALID_SESSION_STATE`) becomes inactive and must be renewed by the user. A
transient failure leaves it usable.

### Sync semantics

- `POST /api/lbp/sync` queues one sync (202); rate-limited like the other sync
  endpoints. The daily 08:00 resync (`MemberSyncService`, source `lbp`, last in
  `CANONICAL_ORDER`) queues a live session normally; an expired overnight
  session reports `NEEDS_REAUTH`, not `FAILED`.
- The sync fetches outside any transaction, validates the whole snapshot, then
  writes every account and its transactions in **one** transaction. One
  doubtful account refuses the whole import: a partial list never overwrites a
  correct one.
- Transactions are upserted by `(account, externalId)`; rows are never deleted
  and manual rows are never touched.
- A read that hits a transient bank page (`502 UPSTREAM_UNAVAILABLE`) is retried
  **once** inside the sidecar, reusing the same cookie jar; a login step is never
  retried. The retry logs the failing path and status, so a deterministic failure
  stays diagnosable.
- A soft-deleted account is skipped, not recreated.
- The bank's `compte.numero` UUID changes at every login; accounts are keyed on
  a hash of the displayed account number so two sessions cannot create
  duplicates.
- The IBAN is not stored, so an Enable Banking sync cannot adopt these accounts
  by accident.
- Account types are set directly from `kind`. On import, `SavingsAutoConfigService`
  creates the default savings config for a regulated product (`LIVRET_A`, `LDDS`,
  `LEP`) when the account has none: product, `RegulatedRates` rate, NET basis and
  the legal ceiling. A config the user edited is never overwritten; a deleted one
  is recreated by the next sync. Livret Jeune, PEL and CEL stay manual because
  the current `SavingsProduct` model has no entry for them; Compte sur Livret
  stays manual because its rate is bank-specific (`COMMERCIAL`), not regulated.

### Configuration

`app.sidecars.lbp-auth.url` (env `LBP_AUTH_URL`, default
`http://lbp-auth:8001`), read by `SidecarProperties`. `LbpAdapter` asks
`SidecarWebClientFactory` for the `lbp-auth` service, which validates the URL and
adds the shared key.

### Error codes

`LbpAdapter` uses the shared `SidecarErrorCode`; the codes La Banque Postale may
store are declared once by `SidecarBank.LBP`:
`SESSION_EXPIRED`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_FORMAT_CHANGED`,
`INVALID_SESSION_STATE`, `INTERNAL_ERROR`, plus the login codes
`INVALID_CREDENTIALS`, `KEYPAD_CHANGED`, `KEYPAD_EXPIRED`, `INVALID_POSITIONS`,
`APP_VALIDATION_TIMEOUT`, `AUTH_ATTEMPT_EXPIRED`.
The `lbp_session.last_sync_error` CHECK enumerates exactly that set;
`SidecarBankCodesTest` fails if the two drift apart.

## What the captures showed

Two real sessions were captured (trusted device and new device):

1. **Password entry** — ten text buttons, **shuffled on every load**. The
   bank's script writes the clicked DOM positions into `password`, not the
   digits; the server decodes them. No JS-computed password.
2. **Mobile approval** — only on a non-trusted device. Trusted device skips it.
   The bank first shows the *Pour plus de sécurité* notice; its **Continuer**
   button opens `cu=attenteAF`, which sends the notification. No SMS path
   observed.
3. **Account data** — server-rendered XHTML, no account JSON. Synthesis pages
   plus `init-releve_ccp` / `init-releve_cne`.
4. **Anti-bot** — `fruprem.js` / `loreo.js` set a 52-character `iscdName`; no
   CAPTCHA observed.
5. **Session lifetime** — short, and idle-based. A sync immediately after login
   succeeds, and so does a second one 15 seconds later with the same stored
   cookies, so the replay and the cookie jar are sound. A sync 9 minutes after
   login was refused with 401: the bank logs the web session out after a few
   minutes of inactivity (public guidance says ~5 min). A dead session requires
   a new login + A2F. This is the same model as Caisse d'Épargne: no keep-alive,
   the panel runs the first sync right after login, and a dead session asks for
   a reconnect. The daily 08:00 resync includes La Banque Postale last: a live
   session syncs normally, an expired overnight one reports `NEEDS_REAUTH` (not
   `FAILED`), and the Sync-all modal routes the row to `/sync?tab=lbp` for a
   manual login + mobile approval.
6. **Transaction history** — `init-releve_ccp` returns about 90 days;
   `telechargementMouvements.ea` is the export path; the `relevePdf` history
   holds monthly statements.

The login layer is headless Chromium (the anti-fraud scripts must run); the data
layer is `httpx` cookie replay.

## State of the implementation (2026-11-06)

**Backend**

- `adapter/LbpAdapter.java` — concrete sidecar client (no port: one
  implementation, one caller), normalized snapshot and nested records, no retry,
  redacted request bodies.
- `adapter/sidecar/SidecarBank.java` — `LBP` declares the label, the `lbp-auth`
  service and the codes La Banque Postale may store.
- `model/LbpSession.java`, `model/LbpSyncStatus.java`,
  `repository/LbpSessionRepository.java`, `config/LbpSyncRecovery.java`,
  `config/SyncExecutorConfig.java` (`lbpSyncExecutor`), migrations
  `V143__lbp_session.sql` and `V144__lbp_saved_identifier.sql`.
- `service/LbpSyncService.java` — validates the whole snapshot, then writes every
  account and its transactions in one transaction; maps `kind` to
  `AccountType` directly, stores the optional encrypted identifier and asks
  `SavingsAutoConfigService` for the regulated-livret defaults.
- `service/SavingsAutoConfigService.java` — creates the Livret A / LDDS / LEP
  savings config (rate, NET basis, legal ceiling) when the account has none.
- `controller/LbpController.java` — `/api/lbp/auth/{initiate,keypad,complete}`,
  `/sync`, `/status`, `/session`, with strict bodies and per-IP buckets.
- Shared-service wiring, like CIC: deleting the last `lbp_*` account clears the
  session (and the optionally remembered identifier) through
  `AccountConnectionService`; `SyncStatusService` reports the `lbp` line
  (`SESSION_EXPIRED` and `INVALID_CREDENTIALS` read as `NEEDS_REAUTH`);
  `SourceSyncResult` classifies the same two codes as `NEEDS_REAUTH`;
  `MemberSyncService` runs `lbp` last in the sync order, queuing a live session
  and reporting an expired one without retrying; the MCP broker trigger covers
  it; the admin instance check probes `lbp-auth` (`sidecar-chromium` base) when
  its session table has a row.
- Tests: `LbpSessionTest`, `LbpAdapterTest`, `LbpControllerTest`,
  `LbpSyncServiceAuthTest`, `LbpSyncServiceStoreSessionTest`,
  `SavingsAutoConfigServiceTest`, `AccountConnectionServiceTest`,
  `SyncStatusServiceTest`, `MemberSyncServiceTest`, `SourceSyncResultTest`,
  `SyncToolsTest` and `InstanceStatusServiceTest` cover the wiring above, plus
  the shared `SidecarContractTest` rig, `SidecarBankCodesTest`, and the `V143` /
  `V144` migration tests.

**Sidecar**

- `services/lbp-auth/main.py` implements the contract and its validation.
- `services/lbp-auth/bank.py` — `BrowserBankClient`: headless Chromium opens
  `identif.ea`, fills the identifier, clicks the shuffled keypad, clicks the
  strong-authentication notice's **Continuer** (which is what sends the push),
  waits for the mobile approval and serializes the cookie jar. The inherited
  `HttpxBankClient` replays those cookies for `/token-check` and `/accounts`.
  The pending store pins one browser context per login.
- `services/lbp-auth/parsers.py` — login form/keypad, account cards, operations
  table and polling URL, with typed `FormatError`.
- `services/lbp-auth/fixtures.py` — synthetic XHTML modelled on the structure of the
  two captures, with every value invented; used by the tests only.
- Tests: the shared key contract, request validation, parsers and the
  `HttpxBankClient` login/approval/import flow through `httpx.MockTransport`
  (30 cases), run in the published image like every other sidecar. The browser
  login is exercised live only.
- Wired into Compose (`lbp` profile, `sidecar-chromium` base) and the Helm chart.

**Frontend**

- `components/sync/LbpPanel.tsx` — identifier form with the optional
  "remember the identifier" checkbox, shuffled keypad (labels and positions
  only), mobile-approval wait, status/sync/disconnect, first-sync orchestration
  and recovery when the completion answer is lost.
- `pages/sync/LbpTab.tsx`, the `lbp` entry in `features/sync/providers.ts`,
  `features/sync/api.ts`, `features/sync/keys.ts`, `types/api.ts`, and the
  `sync.lbp.*` keys in all four locales.
- `components/shared/AddAccountModal.tsx` and the setup integration
  (`SetupStepLbp`, catalog, route, admin toggle) expose the connector from
  Comptes → Ajouter un compte and from the first-run wizard.
- `components/sync/SyncAllModal.tsx` lists the connector (live session or LBP
  accounts present), syncs it in place when the session is live, and routes an
  expired one to `/sync?tab=lbp`.
- Tests: `LbpTab.test.tsx` (16 cases, including the remembered identifier).

**Verification** — the LBP backend tests pass, plus the shared
`SidecarContractTest` (235 cases across every bank), `SidecarBankCodesTest`
(25 cases, including the `lbp_session` CHECK) and the `V143` / `V144` migration
tests on real PostgreSQL. The 75 sidecar tests pass in the published image;
the browser login is live-only. The frontend typechecks, lints and builds, and
the 16 `LbpTab` tests pass.
The previous date-dependent `DividendControllerAuthorizationTest` failure was
fixed upstream (relative ex-dates).

The live run on 2026-11-06 exercised the whole path on an isolated stack: the
headless keypad, the notice's **Continuer** click, the mobile approval, the
cookie serialization, the `afficheSyntheseCCP` / `afficheSyntheseEP` import and
the write of the current account, a Livret A and a LEP with 27 transactions.
A second sync 15 seconds later reused the stored cookies and returned the same
three accounts. The session row was `SUCCESS` and active afterwards.

## Not in this slice

- Automatic savings configuration for Livret Jeune, PEL, CEL and Compte sur
  Livret: the model has no `SavingsProduct` for them, so they stay manual. The
  three regulated books that do map (`LIVRET_A`, `LDDS`, `LEP`) are configured on
  import.
- A long-lived session: the bank logs the web session out after a few minutes of
  inactivity, so a sync after that window requires a new login + A2F. Keeping
  the session warm would need a scheduled request and is deliberately not done.
- Card, loan, life-insurance and PER parsing.
- History beyond roughly 90 days: the export and the monthly PDFs are not
  parsed.
- Scheduled automatic re-authentication: no credentials are stored, so an expired
  session can only be renewed by the user on the LBP tab. The scheduled and MCP
  syncs report it as `NEEDS_REAUTH` instead.
- Enable Banking adoption/deduplication by IBAN.
- A multi-replica backend: pending logins and authentication generations are
  process-local, and the `lbp-auth` sidecar keeps the pending Chromium context in
  memory too. For the duration of an active sync (login through the account
  reads), route all `/api/lbp/*` requests for the same member to the same
  backend replica — and to the same `lbp-auth` replica — or run one replica of
  each; this includes disconnect requests from other devices. A restart, rollout
  or redeploy of either service mid-sync drops the pending state and fails the
  active sync, so the user starts again. See
  [docker-deployment](docker-deployment.md#gotchas--pitfalls).

## Links

- ADR: [`docs/decisions/2026-11-05-la-banque-postale-sidecar.md`](../decisions/2026-11-05-la-banque-postale-sidecar.md)
- Reference implementation: [`docs/features/caisse-epargne.md`](caisse-epargne.md)
- PSD2 scope limit: [`docs/decisions/2026-03-01-dual-bank-providers.md`](../decisions/2026-03-01-dual-bank-providers.md)
- Savings classification and projection: [`docs/features/savings-livrets.md`](savings-livrets.md)
