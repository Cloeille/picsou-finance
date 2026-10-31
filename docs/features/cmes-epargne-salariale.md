# Feature: Crédit Mutuel / CIC Épargne Salariale sync

> Last updated: 2026-10-31

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
   page's hidden fields and no button (the page's buttons ask for another channel or a new
   code). A wrong code brings the same form back with an error: `INVALID_OTP`, and the attempt
   stays open for another try (if the pending pool is full it cannot be kept, and is disposed of).
   Digipass and Safetrans answer `MFA_TYPE_UNSUPPORTED`. A polling request that fails withdraws
   the confirmation from the phone before reporting the error, like the timeout does.
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
5. **Plan ids**: `cmes_<saver number>_<KIND>` (PEE, PEG, PERCOL…). When several plans of one
   fetch share a kind (two PEG from two employers), each gets `cmes_<number>_<KIND>-<name slug>`;
   a plan without a known kind uses its name slug.

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
sync expired before any account was created (a stored session is never `IDLE`).

## Key files

- `services/cmes-auth/{main.py,forms.py,situation_parser.py,fund_parser.py}` and their tests.
- `backend/.../port/CmesPort.java`, `CmesErrorCode.java`, `adapter/CmesAdapter.java`,
  `service/CmesSyncService.java`, `controller/CmesController.java`, `model/CmesSession.java`,
  `db/migration/V117__cmes_session.sql`, `V120__cmes_session_invalid_otp.sql`.
- `frontend/src/components/sync/CmesPanel.tsx`, `features/sync/{api,hooks}.ts`.
- `docker/nginx.conf`, `frontend/nginx.conf`: `/api/cmes/auth/complete` gets 180 s, the
  confirmation can take two minutes.

## Not covered yet

- Operations history, and the per-maturity breakdown (availability dates) of each fund.
- The account-page disconnect action does not list this source yet.
