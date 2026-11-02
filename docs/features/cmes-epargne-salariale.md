# Feature: Crédit Mutuel / CIC Épargne Salariale sync

> Last updated: 2026-11-01

## Context

Crédit Mutuel Épargne Salariale runs the saver's space for Crédit Mutuel and CIC employee
savings (`www.creditmutuel-ere.fr`; CIC savers are sent there too). It has no public API and is
not covered by PSD2. The `cmes-auth` sidecar reads it the way a saver's browser does, and Picsou
imports one `EMPLOYEE_SAVINGS` account per plan (PEE, PEG, PERCOL…), with each fund as a holding.

## How it works

### Sidecar (`services/cmes-auth`, browserless)

The pages are server-rendered and the login is a plain form with no captcha, so the sidecar
speaks HTTPS with `httpx`, on the shared `sidecar-python` base.

1. **Login**: `POST /fr/identification/authentification.html`, form `bloc_ident` (`_cm_user`,
   `_cm_pwd`, `flag`, `_charset_`). The landing page tells a wrong password, a notice that only
   the saver can clear (`ACTION_REQUIRED`) and a second factor apart.
2. **Mobile confirmation**: on `premiers-pas/authentification-forte` (or `validation.aspx`), the
   page script carries a `transactionId` and the URL of `SOSD_OTP_GetTransactionState.htm`.
   `/complete` polls it until `VALIDATED`, then submits the page form. This is the choreography the
   platform uses on its banking sites, as documented by woob's `creditmutuel` module; it is
   reimplemented, not copied. A request not approved within 120 s is withdrawn from the phone
   (`SOSD_OTP_CancelTransaction`).
   **Code by SMS** (seen live on a CIC saver's access): the same page instead holds a form with
   `otp_password`. `/initiate` answers `mfaType: "SMS"`, `/complete` posts the code with the
   page's hidden fields and its named submit button, `_FID_CheckOrDeliverBackup`, as the browser
   sends it. That button is what checks the code: a post without it only refreshes the page, and
   the site answers it by sending a new SMS. A wrong code brings the same form back with an error: `INVALID_OTP`, and the attempt
   stays open for another try (if the pending pool is full it cannot be kept, and is disposed of).
   Digipass and Safetrans answer `MFA_TYPE_UNSUPPORTED`. A polling request that fails, or that
   answers without a state (an HTTP error status, or a redirect elsewhere), withdraws the
   confirmation from the phone before reporting the error, like the timeout does. HTTP 429 and
   5xx read as `UPSTREAM_UNAVAILABLE`; any other answer without a state as `AUTH_ATTEMPT_EXPIRED`.
   An unknown polling state is also withdrawn before it is reported as
   `UPSTREAM_FORMAT_CHANGED`.
3. **Remembered device**: once the second factor is passed, the site sets `auth_client_state`,
   which spares the confirmation for a while (90 days on the banking sites of the same platform).
   `/initiate` accepts the previous session and replays that cookie only, so a re-login with
   stored credentials completes without anyone at the phone.
4. **Positions**: `mon-epargne/situation-financiere-detaillee/index.html` lists each plan's total
   and each fund's value and gain percentage. Units, unit value and gain in euros are only on the
   fund's own pages: posting the page form with the fund's
   `_FID_GoFundSummary_selectedFundIndex:<n>` button lands on its "Synthèse" tab
   (`synthese-du-support.html`, which carries the fund code, usually no ISIN), whose "Mon épargne"
   tab (`epargne-sur-le-support.html`) holds the rest. Each fund starts from a fresh situation page,
   since the form carries the page state, and the button is sent with the value it was served
   with. A fund page that lands on the login or strong-authentication page reads as
   `SESSION_EXPIRED`, so the backend logs in again.
5. **Plan ids**: `cmes_<saver number>_<label slug>-<digest>`, where the digest is the first 16
   hex characters of the SHA-256 of the plan's full label. The id depends on the saver number
   and that label only, never on the other plans listed beside it, so a plan keeps its id when
   another plan opens or closes. The slug (40 characters at most) is for a human reading the
   table; the digest keeps apart labels that slug alike ("ACME+A" and "ACME A"). The page never
   lists one label twice, and `/positions` refuses a fetch where two plans would share an id
   (`UPSTREAM_FORMAT_CHANGED`). The site shows no plan identifier of its own: the plan rows only
   carry positional ids.
   Earlier builds used `cmes_<number>_<KIND>` (or the name slug for a plan without a kind),
   adding the name slug (`cmes_<number>_<KIND>-<name slug>`) when another plan of the same fetch
   shared the kind. That id changed as plans came and went, and "Plan d'épargne entreprise" and
   "Plan d'épargne interentreprises" could take each other's. Which of the two a plan got hung on
   the plans listed at its last old sync, which the current fetch cannot know, so each plan
   carries `legacyExternalIds`, both ids those builds could have given it. The ids alone prove
   nothing; the backend also checks the name (see below). The field can go once every deployment
   has synced on this version.

Every read is reconciled: the funds must add up to the plan total, units × unit value must give
the fund value, and the fund page must agree with the situation page. Anything else fails the
sync and keeps the last good snapshot. Unexpected pages are logged by path and field names only,
never values.

### Backend

`CmesSyncService` follows `AmundiSyncService` for the import and `BoursoSyncService` for the
session: credentials are stored encrypted (`cmes_session.encrypted_credentials`, V117), and a
sync whose session expired logs in again with them, passing the previous session for its
remembered-device cookie. The site's sessions last minutes, so this is the normal path of the
daily 08:00 sync (`MemberSyncService`, source `cmes`). Only when the site asks for a new
confirmation does the session turn inactive and the UI ask the saver to reconnect; the credentials
are kept for that reconnection. A rejected password clears them, so it is not replayed daily, and
turns the session inactive too.

During a second factor the password waits in memory (`pendingCredentials`, 10 min) until the
attempt completes; expired entries are evicted every minute. `/auth/complete` is refused with
`AUTH_ATTEMPT_EXPIRED` when no live entry exists for the attempt (expired, or lost to a backend
restart), so it can never replace the stored session and its credentials with a session that
could not log in again. Rate limits per IP: `/auth/initiate` 5 per 15 min (each call sends the
password to the site, which locks an access after a few failures); `/auth/complete` has its own
budget of 10 per 15 min.

An account still carrying one of a plan's `legacyExternalIds` moves to the new id on the first
sync, but only if it also carries the plan's account name ("<label> — <employer>", which every
build wrote on each sync). Same row, so its snapshots and history stay with the plan, and the
close-at-zero below does not see it as unlisted. The name check is what keeps a closed plan's
history from passing to another plan of its family: when a PEE synced alone as `cmes_<n>_PEE`
has closed and an interentreprise plan now names that id too, the old account is closed at zero
and the new plan starts its own. The matches are resolved before any write. An old account two
plans match fails the sync with `INVALID_DATA`, every account left as it was. A plan two old
accounts match (it already changed id once) takes the one synced last, and the other closes at
zero. An old account renamed since its last sync matches nothing: it closes at zero and the
plan starts a new account, splitting its history rather than risking another plan's. A plan
whose account was soft-deleted under its new id, or under an earlier id with its name, is not
imported again. A deletion under the new id wins even when an earlier account still matches:
an upgrade that found two old accounts moves one and leaves the other at zero, and deleting the
moved one must not let the other bring the plan back. That leftover account stays as it is (or
closes at zero) with the unlisted plans.

A plan that a complete fetch no longer lists (closed or transferred) is closed at zero: balance
0, holdings removed, a zero snapshot for the day. It is not soft-deleted, since a soft-deleted
account reads as removed by the user and is never imported again; a plan that reappears is
updated normally. Only complete fetches get there: an empty list or a plan flagged incomplete
fails the sync first.

Fund tickers are the ISIN when there is one, else `CMES-<fund code>`, else the label slug. They are
not priced by Yahoo: the provider is in `AccountService.PROVIDER_VALUED`, so the holdings keep
the site's valuation.

### Frontend

`CmesPanel` is a `SidecarSessionPanel` with `appPush`: login form, then either a waiting state
while the saver confirms on the phone or a field for the SMS code, depending on `mfaType`. Tab `cmes` on `/sync`.
With `otpRetryable`, a wrong SMS code keeps the attempt and the panel stays on the code form.

The "Sync all" modal lists CMES: a live session syncs in place (`POST /cmes/sync`), also from
the "Sync all" button. An expired session routes to the `cmes` tab, including one whose first
sync expired before any account was created (a stored session is never `IDLE`). Demo mode
answers every `/cmes/*` call with the disconnected status (`frontend/src/demo/index.ts`), so the
modal lists no CMES row there.

### Other surfaces

- **Account deletion** (`AccountConnectionService`): `cmes_` accounts resolve to the member's
  CMES connection. Deleting the last one also deletes the session and its stored credentials
  (`clearSession`), so the scheduled sync stops; while another plan remains, the session stays.
  `clearSession` also cancels a login still waiting on the bank: it bumps the member's auth
  generation, which `initiateAuth` read before calling the sidecar, and the session is stored
  only if the generation is unchanged. The check and the insert share one lock with
  `clearSession`, so a phone approval or a remembered-device login that lands after the removal
  stores nothing and answers `AUTH_ATTEMPT_EXPIRED`. A login that lands after the removal asking
  for a second factor is refused the same way before its password is put in `pendingCredentials`.
- **MCP**: `get_sync_status` reports a `cmes` line, with `INVALID_CREDENTIALS` read as
  `NEEDS_REAUTH` like `SESSION_EXPIRED` (the rejected password was cleared, only the saver can
  give it again). `trigger_broker_sync` includes `cmes`.
- **Instance status**: `cmes-auth` is probed on the `sidecar-python` base whenever a CMES session
  is stored; `/health` reports the base from `PICSOU_SIDECAR_BASE`.

## Key files

- `services/cmes-auth/{main.py,situation_parser.py,fund_parser.py}` and their tests; shared
  Euro-Information login and second-factor handling in
  `services/base/euro_information/{client.py,forms.py}`.
- `SidecarBank.CMES`, `adapter/CmesAdapter.java`,
  `service/CmesSyncService.java`, `controller/CmesController.java`, `model/CmesSession.java`,
  `db/migration/V117__cmes_session.sql`, `V120__cmes_session_invalid_otp.sql`.
- `frontend/src/components/sync/CmesPanel.tsx`, `features/sync/{api,hooks}.ts`.
- `docker/nginx.conf`: `/api/cmes/auth/complete` gets 180 s, the
  confirmation can take two minutes.

## Deployment

The `cmes-auth` image is built on `services/base` (target `python`) like the other browserless
sidecars and published as `ghcr.io/cloeille/picsou-finance/cmes-auth`. Compose starts it with no
published port; the backend reaches it at `CMES_AUTH_URL` (default `http://cmes-auth:8001`) with
the shared `APP_SIDECAR_API_KEY`. See `docs/features/docker-deployment.md`.

## Not covered yet

- Operations history, and the per-maturity breakdown (availability dates) of each fund.
