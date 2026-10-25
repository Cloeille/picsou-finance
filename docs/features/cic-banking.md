# Feature: CIC online-banking sync (livrets)

> Last updated: 2026-10-24

## Context

PSD2 (Enable Banking) only returns a CIC customer's current account; the savings accounts — Livret A,
LDDS, PEL… — never reach Picsou. The `cic-auth` sidecar reads them from the customer site
(`www.cic.fr`) the way a browser does, and Picsou imports each current and savings account with its
balance.

## How it works

- **Sidecar** (`services/cic-auth`, browserless): same platform as Crédit Mutuel Épargne Salariale, so
  the login and second-factor code is `cmes-auth`'s, kept in step by hand (each sidecar is its own
  build context). Login form `bloc_ident` on `/fr/authentification.html`; second factor on
  `/fr/banque/validation.aspx`, either a mobile confirmation (polled on
  `/fr/banque/async/otp/SOSD_OTP_GetTransactionState.htm`, woob's documented path, when the page
  script names none) or an SMS code posted with the form's `_FID_CheckOrDeliverBackup` button. The
  remembered-device cookie (`auth_client_state`) is replayed on re-login. The "Gestion des pouvoirs"
  notice is postponed with its `_FID_DoStopPaciAndRemind` button, as woob does.
- **Accounts**: `/fr/banque/comptes-et-contrats.html`, one `table.ei_comptescontrats` per category,
  captioned "Liste des comptes liés à la catégorie : …". "Comptes courants" rows are `CHECKING`;
  "Livrets et comptes d'épargne" rows are typed from their label (Livret A, LDDS, LEP, Livret Jeune,
  PEL, CEL, else `SAVINGS`). Other categories (insurance, links to sister sites such as employee
  savings) carry no balance and are skipped by name. A balance-bearing row that cannot be read fails
  the page. External id `cic_<account number digits>`.
- **Backend**: `CicSyncService`, shaped like `CmesSyncService` (stored credentials, re-login with the
  previous session, daily source `cic`), balance-only accounts with `upsertSnapshot`. Migration
  V119 `cic_session`. `/api/cic/*`, `/api/cic/auth/complete` given 180 s in nginx.
- **Frontend**: `CicPanel` (`SidecarSessionPanel` + app push / SMS), tab `cic` on `/sync`. While
  the user approves the push in the CIC app, iOS drops Safari's pending `/auth/complete` request; the
  backend finishes regardless. A completion lost without an answer (network error, 502/504) keeps the
  waiting card and polls the session status for up to 150 s instead of reporting a failure.

The current account also comes from Enable Banking when that connection exists: hide one of the
two in Settings → Accounts.
