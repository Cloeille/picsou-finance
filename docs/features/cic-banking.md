# Feature: CIC online-banking sync (livrets)

> Last updated: 2026-10-31

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
  script names none) or an SMS code posted with the form's `_FID_CheckOrDeliverBackup` button. On
  re-login the previous session's whole cookie jar is replayed, as woob does. The "Gestion des pouvoirs"
  notice is postponed with its `_FID_DoStopPaciAndRemind` button, as woob does.
- **Accounts**: `/fr/banque/comptes-et-contrats.html`, one `table.ei_comptescontrats` per category,
  captioned "Liste des comptes liés à la catégorie : …". "Comptes courants" rows are `CHECKING`;
  "Livrets et comptes d'épargne" rows are typed from their label (Livret A, LDDS, LEP, Livret Jeune,
  PEL, CEL, else `SAVINGS`). Category names accept straight and typographic apostrophes. Tables
  outside these categories are skipped only when their rows contain no account or balance data,
  such as links to employee savings. An unnamed or unrecognized table with account data fails the
  whole page. A balance-bearing row that cannot be read also fails the page, including one whose
  title marker is missing or whose label is empty. Header and action rows without account or
  balance markers are skipped. Before certifying `snapshotComplete`, the parser also checks
  tables and rows outside its recognized selectors. Account or balance data in a renamed table,
  an orphan row, or unparsed markup inside a contract table fails the fetch with
  `UPSTREAM_FORMAT_CHANGED`. Existing balances and snapshots remain unchanged. Unrelated tables
  without account data and summary amounts outside tables or rows are ignored.
  Each account row must have one title, number, and balance; malformed row boundaries cannot merge
  multiple accounts into a single parsed record.
  External id `cic_<account number digits>`.
- **Backend**: `CicSyncService`, shaped like `CmesSyncService` (stored credentials, re-login with the
  previous session, daily source `cic`), balance-only accounts with `upsertSnapshot`. Migration
  V121 `cic_session`. `/api/cic/*`, `/api/cic/auth/complete` given 180 s in nginx.
  `/auth/initiate` and `/auth/reconnect` share 5 password checks per IP per 15 min; `/auth/complete`
  has its own 10, so retyped SMS codes do not use them up. An account a complete fetch no longer
  lists is closed at zero (balance 0, zero snapshot), not soft-deleted, so it can come back.
  Savings labels are typed with accents and typographic apostrophes folded.
- **Frontend**: `CicPanel` (`SidecarSessionPanel` + app push / SMS), tab `cic` on `/sync`. While
  the user approves the push in the CIC app, iOS drops Safari's pending `/auth/complete` request; the
  backend finishes regardless. A completion lost without an answer (network error, 502/504) keeps the
  waiting card and polls the session status for up to 150 s instead of reporting a failure.

## Connection lifecycle

Deleting the member's last `cic_` account removes the stored session and encrypted credentials.
Another live CIC account keeps the shared connection. CMES accounts belong to a separate connection.
Disconnect also revokes pending logins. A completion requires the same member's unexpired pending
attempt, and checks revocation and expiry again after the bank answers. Session writes and
disconnects share a lock, so an approval arriving after disconnect cannot recreate the connection.
A stored-credentials reconnect captures the cancellation generation before reading or decrypting
the credentials and keeps it through initiation and completion. Disconnect therefore also revokes
a reconnect paused before its request to the bank. A new login started after disconnect can create
a new connection.

A rejected stored password is forgotten and makes the session inactive with `INVALID_CREDENTIALS`,
including rejection during an explicit reconnect. That reconnect clears credentials in a locked
transaction only if they still match the rejected value and the connection has not been removed
since it started. A newer login's credentials survive an older rejection. The member must enter
new credentials after rejection. `SESSION_EXPIRED` also makes the session inactive but keeps the
credentials for reconnect. Other sync failures leave the session active for retry.

Pending second factors hold plaintext credentials in memory for ten minutes. A task runs every
minute to remove expired entries even without another login. Completing an expired attempt also
removes its credentials immediately. Unexpired attempts remain available for SMS correction.

An incorrect SMS code keeps the attempt and code form available, so the member can correct it
without another login. An expired session stays in **Sync all** even when its first import created
no accounts. Its reconnect action uses stored credentials when available.

MCP `get_sync_status` reports CIC alongside full synchronization, including queued, running,
completed, and failed imports. `SESSION_EXPIRED` and `INVALID_CREDENTIALS` report `NEEDS_REAUTH`.
Transient upstream failures report `FAILED` without requiring another login.

Terminal phone-confirmation polling failures withdraw the confirmation request. HTTP 429 and 5xx
report `UPSTREAM_UNAVAILABLE`. Redirects and authentication errors report `AUTH_ATTEMPT_EXPIRED`.
Unexpected responses or states report `UPSTREAM_FORMAT_CHANGED`.

**Daily sync limit**: CIC asks its customers to confirm on the phone again every 24 hours, with
no remembered device. The daily 08:00 re-login therefore only gets through within a day of the
last confirmation; otherwise it withdraws the request at once (no notification left on the phone)
and the session turns inactive until the customer reconnects. Savings balances move rarely, so
reconnecting now and then is enough.

The current account also comes from Enable Banking when that connection exists: hide one of the
two in Settings → Accounts.

## Regression coverage

- `services/cic-auth/test_accounts_parser.py` and `test_main.py` reject incomplete markup before
  `/accounts` can return a certified snapshot.
- `CicAdapterTest` preserves the format-change error across the sidecar boundary.
  `CicSyncServiceTest` checks that a failed fetch leaves existing balances and snapshots untouched.
- `CicControllerTest` exercises the shared initiate/reconnect quota and independent completion
  quota through HTTP. `RateLimitConfigCicTest` checks both capacities and their 15-minute refill.
