# ADR: Natixis Interépargne through a browserless sidecar that keeps only the refresh token

> Date: 2026-11-06
> Status: ✅ Active

## Context

Natixis Interépargne keeps the employee savings (PEE, PERCO/PERECO) of a large
share of French employers. Like Amundi and CMES, it is out of reach of PSD2 and
Enable Banking: these are not payment accounts.

The saver space (`epargnants.interepargne.natixis.fr`) is an Angular SPA over a
JSON API (`nie.api.natixis.com`). A live session was captured and then replayed
outside the browser, which established the following:

- The login is an OAuth2 authorization code with PKCE from a fixed public client
  (`nie-auth.api.natixis.com`), bridged over SAML to an identity provider with a
  JSON API (`auth-nie.natixis.com/auth/v1/*`). The user enters an e-mail, then a
  password, and **the second factor is mandatory on every login**: an SMS, a voice
  call or an e-mail, whichever the account has.
- There is no captcha and no script challenge. The on-screen keypad only displays
  the digits: the API receives them in clear. The CSRF token is a `<meta>` tag on
  the SSO page.
- The scope includes `offline_access`. The token endpoint therefore returns a
  **refresh token** alongside a 30-minute access token. Replaying it from plain
  HTTP returned 200, and it came back unchanged, so it is not rotated.
- "Mémoriser mes identifiants" only pre-fills the e-mail in `localStorage`. The
  "trusted device" is a WebAuthn passkey, which a server cannot use.

Fund lines come back as structured JSON: units, last unit value and date, amount,
gains and the locked/available split. Funds carry an AMF `QS…` code rather than an
ISIN.

## Decision

1. **The connector is `services/natixis-auth`**, a FastAPI sidecar on the
   `sidecar-python` base. It speaks plain httpx with no browser, and builds on
   `picsou_sidecar.create_app` and `PendingStore`.
2. **The login runs in two calls.** `/initiate {email, password}` goes as far as
   sending the code. `/complete {processId, code}` verifies it, posts the SAML
   response back, exchanges the authorization code and returns `sessionState`. That
   is an opaque string (it encodes the refresh token, device id and employee id as
   JSON); clients pass it back unchanged and never parse it. A mistyped code keeps
   the attempt open for a retry.
3. **Only the session is stored, never the credentials.** A stored password could
   not log in again without someone at the phone, because the code is asked on
   every login. Java encrypts `sessionState` with `CryptoEncryption`. When the
   refresh token is refused (`SESSION_EXPIRED`), the session becomes inactive and
   the scheduler reports `NEEDS_REAUTH`.
4. **`/positions {sessionState}` refreshes the token itself**, reads `savings`
   and `products` and returns `{plans, sessionState}`. The backend stores the
   returned state after every successful read, so a future rotation of the refresh
   token would not lose the session.
5. **Valuations come from Natixis**, with no price provider and no OpenFIGI. The
   lines of each plan must add up to the plan amount, and all plans together must
   match the separately computed `products` total. Both checks run in the sidecar,
   and the backend checks the plan totals again. A mismatch is
   `PORTFOLIO_INCOMPLETE` and keeps the last good snapshot.
6. **The second factor is chosen as SMS, then e-mail, then voice**, among the
   modes the account offers. An account with no password login, or with none of
   these channels, gets `MFA_TYPE_UNSUPPORTED`.
7. **Operations are imported as history.** BUY and SELL transactions per fund line
   (investments, the employer top-up, releases) from Natixis' five-year history. They
   are reconciled by external id, never change balances, and are written
   `budgetNeutral` so the budget and categorization ignore them.
8. **Errors use the strict `{"detail": CODE}` contract** with codes already in
   `SidecarErrorCode`. As with every connector since the ports ADR, there is no
   port: `NatixisAdapter` is a concrete component.

## Alternatives considered

### Browser sidecar (Playwright), as for Amundi

- **Pros**: closest to what a user does; it would survive changes to the login
  pages more easily.
- **Cons**: it needs a Chromium image for a flow that has no JavaScript challenge.
  It is heavier and slower, and it does not avoid the code on every login. The
  httpx spike succeeded end to end on the first real attempt.

### Store the credentials and log in again (the CMES pattern)

- **Pros**: the same backend shape as CMES.
- **Cons**: it is pointless, because every login asks for a code. Keeping a
  password that can never be used unattended only adds a secret at rest.

### Ask the user to paste a token from the browser devtools

- **Pros**: no login automation at all.
- **Cons**: the access token lasts 30 minutes, and getting a refresh token out of
  the SPA is not something a non-technical user can do.

## Reasoning

The refresh token is the one thing that makes background sync possible. Keeping
it, and nothing else, gives the smallest secret at rest and the best user
experience: one code at connection, then unattended syncs. Since nothing in the
flow needs a browser, the cheaper and faster httpx path wins.

## Trade-offs accepted

- **The refresh token's lifetime is unknown.** It is opaque, and Natixis gives no
  `refresh_expires_in`. If it turns out to be short, syncs report `NEEDS_REAUTH`
  more often. The flow stays correct and the UI says so, but the experience gets
  worse. The lifetime is being measured on a real account.
- **The `RelayState` the SPA posts is the literal string `"undefined"`**, a
  JavaScript bug the server accepts. The sidecar mirrors it. If Natixis fixes it,
  the sidecar follows whatever `relayState` the IdP returns.
- **A rotated refresh token would be lost on a failed read**, because the error
  contract carries no session state. Natixis does not rotate today. If it starts, a
  partial read costs a new code at the next sync, and the sidecar logs a warning
  when that happens.
- **The login depends on a fixed `client_id` and redirect URI.** A change to
  either fails closed with `UPSTREAM_FORMAT_CHANGED`.

## Consequences

- There is a new sidecar, `natixis-auth`, with its Compose profile `natixis`, its
  Helm entry and `NATIXIS_AUTH_URL`.
- On the backend: `SidecarBank.NATIXIS`, `NatixisAdapter`, `NatixisSyncService`,
  `/api/natixis`, a `NATIXIS` row in the shared `sidecar_session` table
  (ADR 2026-11-02, one sidecar session table) and the migration
  `V146__natixis_sidecar_session.sql`, which adds the bank and its error codes.
  The table has no credentials column for it.
- There is a "Natixis Interépargne" entry in the add-account modal and on the sync
  page.
- See [natixis-interepargne.md](../features/natixis-interepargne.md).
