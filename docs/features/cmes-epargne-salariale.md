# Feature: Crédit Mutuel / CIC Épargne Salariale sync

> Last updated: 2026-10-24

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
   reimplemented, not copied. SMS, Digipass and Safetrans answer `MFA_TYPE_UNSUPPORTED`. A
   request not approved within 120 s is withdrawn from the phone (`SOSD_OTP_CancelTransaction`).
3. **Remembered device**: once a confirmation is approved, the site sets `auth_client_state`,
   which spares the confirmation for a while (90 days on the banking sites of the same platform).
   `/initiate` accepts the previous session and replays that cookie only, so a re-login with
   stored credentials completes without anyone at the phone.
4. **Positions**: `mon-epargne/situation-financiere-detaillee/index.html` lists each plan's total
   and each fund's value and gain percentage. Units, unit value and gain in euros are only on the
   fund's own pages: posting the page form with the fund's
   `_FID_GoFundSummary_selectedFundIndex:<n>` button lands on its "Synthèse" tab
   (`synthese-du-support.html`, which carries the fund code, usually no ISIN), whose "Mon épargne"
   tab (`epargne-sur-le-support.html`) holds the rest. Each fund starts from a fresh situation page,
   since the form carries the page state.

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
are kept for that reconnection. A rejected password clears them, so it is not replayed daily.

Fund tickers are the ISIN when there is one, else `CMES-<fund code>`, else the label slug. They are
not priced by Yahoo: the provider is in `AccountService.PROVIDER_VALUED`, so the holdings keep
the site's valuation.

### Frontend

`CmesPanel` is a `SidecarSessionPanel` with `appPush`: login form, then a waiting state while
the saver confirms on the phone. Tab `cmes` on `/sync`.

## Key files

- `services/cmes-auth/{main.py,forms.py,situation_parser.py,fund_parser.py}` and their tests.
- `backend/.../port/CmesPort.java`, `CmesErrorCode.java`, `adapter/CmesAdapter.java`,
  `service/CmesSyncService.java`, `controller/CmesController.java`, `model/CmesSession.java`,
  `db/migration/V117__cmes_session.sql`.
- `frontend/src/components/sync/CmesPanel.tsx`, `features/sync/{api,hooks}.ts`.
- `docker/nginx.conf`, `frontend/nginx.conf`: `/api/cmes/auth/complete` gets 180 s, the
  confirmation can take two minutes.

## Not covered yet

- Operations history, and the per-maturity breakdown (availability dates) of each fund.
- The "Sync all" modal and the account-page disconnect action do not list this source yet.
