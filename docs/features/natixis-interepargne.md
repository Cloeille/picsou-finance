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
- `services/natixis-auth/fixtures.py`: anonymized captures of a real saver space.
- `backend/.../adapter/NatixisAdapter.java`: the sidecar client, a concrete
  component with no port.
- `backend/.../service/NatixisSyncService.java`: the login hand-off, the job queue,
  the reconciliation and account writes, and `resyncReporting`.
- `backend/.../controller/NatixisController.java`: `/api/natixis` with
  `/auth/initiate`, `/auth/complete`, `/sync`, `GET /status` and `DELETE /session`.
- `backend/.../model/NatixisSession.java` and
  `db/migration/V125__natixis_session.sql`: one session per member, holding the
  encrypted session state and the sync status. No credentials are stored.
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

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| httpx, no browser | The login has no captcha and no script challenge; the keypad digits travel in clear | A Playwright sidecar like Amundi's |
| Keep only the refresh token | Every login asks for a code, so a stored password could never log in unattended | Stored credentials with re-login, as CMES does |
| Valuations from Natixis | FCPE have no ISIN and no market quote | Price providers or OpenFIGI |
| Plans reconciled twice | `products` is computed separately from `savings`, so a partial answer cannot pass for the whole | Trusting `savings` alone |

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
  a sync, whatever the reason, a 401 included. Elsewhere, only a 401 on the API or a
  refused refresh grant means `SESSION_EXPIRED`. A 403 is `UPSTREAM_FORMAT_CHANGED`,
  so a missing scope never forces the saver to type a new code. A refused
  authorization code during login is `AUTH_ATTEMPT_EXPIRED`.
- **A rotated refresh token is lost if the read then fails.** The `{"detail"}` error
  contract carries no session state. Natixis does not rotate today; if it starts, a
  failed read means a new code at the next sync, and the sidecar logs a warning when
  this happens.
- **Each sidecar route gives up after 40 s** (`UPSTREAM_UNAVAILABLE`), and each
  Natixis call after 15 s. That stays under the backend's 45 s adapter timeout, so a
  slow Natixis never leaves a code sent or tokens minted that the backend has stopped
  waiting for.
- **At most 5 wrong codes per login attempt**: the fifth drops the attempt
  (`AUTH_ATTEMPT_EXPIRED`), well before Natixis would lock the account.
- **Accounts without a password login** (passkey only), or without an SMS, e-mail or
  voice channel, get `MFA_TYPE_UNSUPPORTED`.
- **Dev port: 8015** (`application-dev.yml`).

## Tests

- `services/natixis-auth/test_main.py`: the whole login and positions flow against
  a fake Natixis; every error code; a log test proving that no credential, code,
  token, CSRF or SAML value is ever logged.
- `services/natixis-auth/test_parsers.py`: mapping and reconciliation on the
  anonymized fixtures.
- `services/natixis-auth/test_sidecar_auth.py`: the shared sidecar-key contract.
- `NatixisSyncServiceTest` and `NatixisControllerTest`; the NATIXIS rows of
  `SidecarContractTest` and `SidecarBankCodesTest`.
- `frontend/src/demo/natixis.test.ts` and `NatixisPanel.test.tsx`.

## Links

- Related ADR: [2026-11-06-natixis-interepargne-httpx-sidecar.md](../decisions/2026-11-06-natixis-interepargne-httpx-sidecar.md)
- Similar connectors: [cmes-epargne-salariale.md](cmes-epargne-salariale.md), [amundi-epargne-salariale.md](amundi-epargne-salariale.md)
