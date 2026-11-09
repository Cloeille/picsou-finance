# Feature: Natixis Interépargne sync

> Last updated: 2026-11-06

## Context

Natixis Interépargne keeps the employee savings (PEE, PERCO/PERECO) of many French
employers. Open banking cannot reach those plans. The `natixis-auth` sidecar logs
into the saver space once with a code sent by SMS, voice call or e-mail. From then
on, it syncs in the background with the refresh token Natixis issues, until Natixis
ends the session.

## How it works

### Key files

- `services/natixis-auth/main.py`: FastAPI and httpx sidecar. It runs the login
  (`/initiate`, `/complete`), refreshes the token and reads the positions
  (`/positions`).
- `services/natixis-auth/savings_parser.py`: maps `savings` and `products` to plans
  and fund lines, and reconciles them.
- `services/natixis-auth/fixtures.py` and `op_fixtures.py`: synthetic responses
  shaped after the real API, with no value from a real account.
- `backend/.../adapter/NatixisAdapter.java`: the sidecar client, a concrete
  component with no port.
- `backend/.../service/NatixisSyncService.java`: the login hand-off, the job queue,
  the reconciliation and account writes, and `resyncReporting`.
- `backend/.../controller/NatixisController.java`: `/api/natixis` with
  `/auth/initiate`, `/auth/complete`, `/sync`, `GET /status` and `DELETE /session`.
- `backend/.../model/SidecarSession.java` (`bank = 'NATIXIS'`) and
  `db/migration/V146__natixis_sidecar_session.sql`: one `sidecar_session` row per member,
  holding the encrypted session state and the sync status. No credentials are stored (the
  database refuses `encrypted_credentials` on it).
- `frontend/src/components/sync/NatixisPanel.tsx` and
  `frontend/src/pages/sync/NatixisTab.tsx`: the connection panel, also reachable
  from the add-account modal.

### Flow

```
UI ── email + password ──▶ /api/natixis/auth/initiate ──▶ sidecar /initiate
        authorize (PKCE) → SAML SSO page (CSRF meta) → login modes → password
        → "strong authentication required" → code sent (SMS > e-mail > voice)
UI ◀── processId, mfaType
UI ── code ──▶ /api/natixis/auth/complete ──▶ sidecar /complete
        code check → second factor → profile check → SAML response posted back
        → authorization code → tokens → users/me (employee id)
backend stores sessionState = {refreshToken, deviceId, employeeId} (encrypted), queues a sync

sync (manual or the 08:00 daily job) ──▶ sidecar /positions {sessionState}
        refresh-token grant → savings + products (+ one fund detail per fund for its AMF code)
        → reconcile → {plans, sessionState}
backend stores the returned sessionState, re-checks plan totals and writes one
EMPLOYEE_SAVINGS account per plan with its fund holdings
```

### Operations history

`/positions` also returns the saver's finished operations from the last five years,
taken from `transaction/v1/employees/{id}/transactions` and one detail per operation:

- **An investment** (profit-sharing, a voluntary payment…) gives one `BUY` per fund
  line. The employer top-up (`additionalInvestment`) is a separate `BUY` labelled
  "Abondement employeur".
- **A release** (early release, maturity) gives one `SELL` per fund line sold.

The backend reconciles these rows on the plan accounts in the same transaction as
the plans:
- they are upserted by external id;
- a Natixis row that vanished is deleted only when it is dated at least 90 days
  after `operationsFrom`, the first listing date the read asked Natixis for. Natixis
  lists by demand date but dates an operation when it settles, days later, so an
  operation dated near the start may belong to a demand listed just before it,
  outside the read. Without `operationsFrom` (an older sidecar), nothing is deleted;
- older rows and manual rows are kept.

When `operationsComplete` is false (the history could not be read in full), the
existing rows are left as they are. The history is optional data, like the fund
details: a read that fails (a 401 included), overruns its budget, or that the
response contract refuses (a malformed date, a label over 200 characters) is
dropped whole, logged by error type only, and the plans still sync. The
operations are validated against that contract inside `collect_plans`, before
the route answers, so a bad one can never fail `/positions`.

**Detail links.** The URL of each operation detail is rebuilt from the saver's id,
the operation type (`investmentDemands` or `paymentDemands`) and the link's last
segment, which must be plain characters (`[0-9A-Za-z_-]`). A link to another host,
another saver or another collection, or one with `..`, an encoded character, a query,
a fragment, whitespace or a control character, is a format change: the history is
dropped and the saver's token is never sent there. As a last guard, `_api_get` only
calls a path that matches one of the shapes the sidecar reads, whole (`_API_PATH`).
The first failed detail cancels the other reads at once. They are read with
`asyncio.wait`, not a `TaskGroup`: on Python 3.12 a failed `TaskGroup` child leaves
the request task cancelling, and the route deadline would then escape as a raw 500.

**Sales of units older than the window.** A PEE or PERCO release often sells units
bought more than five years ago, so their `BUY` is outside the history. The `SELL`
is still imported, as it is in the saver space. The realized-gains section then has
no cost basis for those units and counts the whole proceeds as gain; the ticker's
over-sell warning is the only signal. The plan balance and holdings are unaffected.

Operations are history only. They never change balances, holdings or snapshots,
and they feed the realized-gains section of the account.

**Budget:** each operation is written with `budgetNeutral = true`. This is the
`Transaction#budgetNeutral` flag (V126) that every budget, cash-flow, categorization
and global-transactions query filters out, and that CORUM Life also uses for
movements inside its contract. Without it, years of profit-sharing would read as
spending. The rows stay visible on the account page and in exports.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| httpx, no browser | The login has no captcha and no script challenge; the keypad digits travel in clear | A Playwright sidecar like Amundi's |
| Keep only the refresh token | Every login asks for a code, so a stored password could never log in unattended | Stored credentials with re-login, as CMES does |
| Valuations from Natixis | FCPE have no ISIN and no market quote | Price providers or OpenFIGI |
| Plans reconciled twice | `products` is computed separately from `savings`, so a partial answer cannot pass for the whole | Trusting `savings` alone |
| EUR only, fail-closed | Every amount node carries a `currencyCode`, and the backend books plans, holdings and operations in EUR; a non-EUR or missing code is `UPSTREAM_FORMAT_CHANGED` | Converting, or storing the value as euros |

## Gotchas / Pitfalls

- **The refresh token's lifetime is unknown.** When Natixis refuses it, the result
  is `SESSION_EXPIRED`: the session becomes inactive, the daily job reports
  `NEEDS_REAUTH`, and the saver reconnects from the Natixis tab with a new code.
- **The session state is replaced after every successful `/positions`**, even when
  the backend then rejects the plans as incomplete. Natixis does not rotate refresh
  tokens today, but if it starts, the old token may already be spent.
- **The SAML response carries no `relayState`.** The SPA posts the literal string
  `"undefined"`, and the sidecar does the same. Do not "fix" this to an empty value
  without checking against the live site.
- **The x-csrf-token is read from `<meta name="_csrf">`** on the SSO page and stays
  the same for the whole login. `x-device-id` is a random UUID per login.
- **A fund held in two compartments** (employee savings and voluntary payments)
  becomes one holding, with its units and amounts added up. Voluntary payments keep
  their funds one level down, in `subCompartments`, which the parser walks too. A
  fund line without a Natixis `fundId` whose label is already taken is rejected
  (`UPSTREAM_FORMAT_CHANGED`) rather than merged on a guess.
- **Every line has a stable `fundCode`:** the AMF `QS…` code when the fund detail
  gives one, or else `NIE-<fundId>`. Fund details are read four at a time within a
  10 s budget. Any failure there (an error, a timeout, a 401) only leaves the
  `NIE-` id and never fails the sync.
- **Plans are counted as well as summed.** `savings` must list as many plans as
  `products` lists Natixis products. Within the 0.1 % tolerance, a small plan could
  otherwise go missing, and the backend would close it at zero. A saver with no plan
  left (an empty list, and a products total of 0) is a valid portfolio, and their
  old accounts close.
- **A plan at 0 €** (an empty PERCO) is still listed and gets an account at 0. A
  plan Natixis stops listing is closed at zero, as with CMES.
- **A login slot is reserved before Natixis sends the code.** A full pending store
  (16 attempts) refuses the login before any code goes out. `POST /cancel
  {processId}` frees an abandoned attempt at once; the backend calls it when a
  member disconnects during the second factor.
- **A mistyped code keeps the login attempt open:** the saver retries with the same
  `processId` until the 10-minute TTL runs out.
- **A missing fund detail** only leaves the line's `fundCode` empty and never blocks
  a sync, whatever the reason, a 401 included. The operations history is treated the
  same way. Elsewhere, only a 401 on the API or a
  refused refresh grant means `SESSION_EXPIRED`. A 403 is `UPSTREAM_FORMAT_CHANGED`,
  so a missing scope never forces the saver to type a new code. A refused
  authorization code during login is `AUTH_ATTEMPT_EXPIRED`.
- **A rotated refresh token is lost if the read then fails.** The `{"detail"}` error
  contract carries no session state. Natixis does not rotate today; if it starts, a
  failed read means a new code at the next sync, and the sidecar logs a warning when
  this happens.
- **Timeouts.** The login routes (`/initiate`, `/complete`) give up after 40 s,
  under the backend's 45 s adapter timeout, so a slow Natixis never leaves a code sent
  that the backend has stopped waiting for. `/positions` gives up
  after 100 s, under its 120 s adapter timeout; inside it, fund details get 10 s and
  the operations history 30 s, and only those parts are dropped when they overrun.
  Each Natixis call is capped at 15 s. A deadline that expires answers
  `UPSTREAM_UNAVAILABLE`.
- **A login can mint tokens it then loses.** `/complete` exchanges the authorization
  code for tokens, then reads the saver's id (`users/me`). If that read fails or times
  out, `/complete` returns an error without the tokens, and the saver has to start the
  login over (a new code). Natixis cannot be asked to revoke them; they expire on
  their own.
- **At most 5 wrong codes per login attempt**: the fifth drops the attempt
  (`AUTH_ATTEMPT_EXPIRED`), well before Natixis would lock the account.
- **Accounts without a password login** (passkey only), or without an SMS, e-mail or
  voice channel, get `MFA_TYPE_UNSUPPORTED`.
- **Natixis accounts are never valued at market prices.** `AccountService.NEVER_MARKET_PRICED`
  skips quotes for them, so a quote found for a fund's ticker (a label slug can match an
  unrelated symbol) never replaces the Natixis value. CMES and Amundi are unchanged.
- **Ticker scheme:** `NATIXIS-<fundCode>`. Without a code, the ticker is the label slug cut
  to 21 characters, a hyphen, then the first 8 hex characters of the label's SHA-256. Two
  funds whose names start alike therefore never collide.
- **Lock order:** the sync commit locks the member's Natixis accounts (by id), then the
  session row. That is the order an account deletion already takes, so the two cannot
  deadlock.
- **Interrupted jobs:** at boot, every QUEUED or RUNNING job last updated before this
  backend started is failed as retryable at once. The jobs live in a process-local
  executor, and Helm and Compose run a single backend, so such a job has no worker
  left. Then, every 5 minutes, a job not updated for 10 minutes is failed the same way,
  for a worker that died without a restart. If the move to RUNNING itself fails, the job fails as retryable
  (`UPSTREAM_UNAVAILABLE`) instead of staying queued.
- **Disconnecting during the second factor** cancels the login on the sidecar (`/cancel`,
  best effort, 10 s). When this happens through an account deletion, the call runs inside
  that deletion's transaction.
- **Dev port: 8015** (`application-dev.yml`).

## Tests

- `services/natixis-auth/test_main.py`: the whole login and positions flow against
  a fake Natixis; every error code, the route deadline after a failed detail
  included; the API-path guard, which sends nothing outside `_API_PATH`; a log test
  proving that no credential, code, token, CSRF or SAML value is ever logged.
- `services/natixis-auth/test_parsers.py`: mapping and reconciliation on the
  synthetic fixtures.
- `services/natixis-auth/test_sidecar_auth.py`: the shared sidecar-key contract.
- `services/natixis-auth/test_operations.py`: the operations mapping and the
  detail-link guard (traversal, encoded characters, another host or saver).
- `NatixisSyncServiceTest`, `NatixisSyncRecoveryTest` and `NatixisControllerTest`; the NATIXIS rows of
  `SidecarContractTest` and `SidecarBankCodesTest`.
- `frontend/src/demo/natixis.test.ts` and `NatixisPanel.test.tsx`.

## Links

- Related ADR: [2026-11-06-natixis-interepargne-httpx-sidecar.md](../decisions/2026-11-06-natixis-interepargne-httpx-sidecar.md)
- Similar connectors: [cmes-epargne-salariale.md](cmes-epargne-salariale.md), [amundi-epargne-salariale.md](amundi-epargne-salariale.md)
