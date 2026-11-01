# Feature: CIC online-banking sync (livrets)

> Last updated: 2026-11-01

## Context

PSD2 (Enable Banking) only returns a CIC customer's current account; the savings accounts — Livret A,
LDDS, PEL… — never reach Picsou. The `cic-auth` sidecar reads them from the customer site
(`www.cic.fr`) the way a browser does, and Picsou imports each current and savings account with its
balance.

## How it works

- **Sidecar** (`services/cic-auth`, browserless): same platform as Crédit Mutuel Épargne Salariale, so
  their login, second-factor and session code lives in
  `services/base/euro_information/client.py`. Each sidecar passes the shared client its own frozen
  `Site`, while keeping its bank-specific routes and parsers. Login form `bloc_ident` on
  `/fr/authentification.html`; second factor on
  `/fr/banque/validation.aspx`, either a mobile confirmation (polled on
  `/fr/banque/async/otp/SOSD_OTP_GetTransactionState.htm`, woob's documented path, when the page
  script names none) or an SMS code posted with the form's `_FID_CheckOrDeliverBackup` button. On
  re-login the previous session's whole cookie jar is replayed, as woob does. The "Gestion des pouvoirs"
  notice is postponed with its `_FID_DoStopPaciAndRemind` button, as woob does.
- **Accounts**: `/fr/banque/comptes-et-contrats.html`, one `table.ei_comptescontrats` per category,
  captioned "Liste des comptes liés à la catégorie : …". "Comptes courants" rows are `CHECKING`;
  "Livrets et comptes d'épargne" rows are typed from their label (Livret A, LDDS, LEP, Livret Jeune,
  PEL, CEL, else `SAVINGS`). Product labels ignore accents and typographic apostrophes. Category
  names accept straight and typographic apostrophes. Tables
  outside these categories are skipped only when their rows contain no account-number markers
  or balance data. A product title alone, such as "Vos contrats et nos offres", can be a
  contract or offer link without a balance, including when accompanied by an empty `ei_sdsf_solde` cell.
  An actual `doux` account-number
  marker or any visible non-whitespace text in an `ei_sdsf_solde` element fails closed, including
  bare numeric balances without a currency. Product-title text requires decimal punctuation,
  a currency symbol, or a whole number with grouped thousands (at least four digits) to count as
  money. A name such as "Contrat 2024 USD" is not a balance, while nested text such as
  "1 234 EUR" or "1 234,56 EUR" is. Amount markers
  still count even inside titles. Balance elements can be cells, spans, divs, or other
  containers. Unbalanced title markup cannot suppress balances in following cells;
  an unterminated title is also scanned for whole-number balances, even if an enclosing end tag
  or a new `td` or `th` implicitly closes it. Empty balance cells remain
  account markers in supported
  tables and unrecognized table or row blocks, preserving strict completeness checks.
  An unnamed or unrecognized table with a balance fails the whole page. Its error names the
  normalized category, truncated to 60 characters. Product titles are omitted because they
  can contain holder names and balances that must not reach sidecar logs. Balance
  detection collects element class tokens, balance-element text, and text with and without
  titles in one HTML parse per fragment. Unknown rows also fail on visible French decimal
  amounts (a comma and two decimal digits) or a numeric amount followed by the whole currency
  word `euro` or `euros` (case-insensitive) outside the title,
  even without a currency symbol or balance class. Other plain-text money requires an uppercase ISO currency code
  (except `EUR`, which remains case-insensitive) or a `€`, `$`, or `£` symbol on either side of the amount,
  including whole-number amounts. Numeric runs are tokenized once so long text without a
  currency does not trigger quadratic regex retries.
  Balance-free offer text such as "Découvrez nos offres en Europe" or "Montants en euros"
  remains skippable; `1 EURO` and `1 234,56 euros` still fail closed.
  Explicit percentages such as "À partir de 2,99 % TAEG" or "Taux annuel : 3,50 %"
  are excluded only from the bare-decimal fallback, with or without whitespace before `%`.
  Account markers, populated balance elements, currency amounts, and other bare monetary
  decimals in the same row still fail closed.
  Outside supported contract tables, an alphabetic currency prefix requires decimal punctuation
  or at least four digits in the amount. Uppercase prose such as `TOP 3`, `ALL 4 YOU`, or `SOS 112`
  therefore preserves the accounts in layout tables, unknown-category captions, and offer subtitles.
  Number-first amounts and currency symbols still fail closed, as do decimal amounts such as
  `EUR 12,34` and larger whole-number balances such as `ARS 2026` or `EUR 1 234`.
  Supported contract tables retain strict whole-number prefix detection. Class-marked account
  numbers, amounts, and nonempty balance elements fail closed regardless of these text rules.
  Arbitrary visible text outside a title remains allowed so help text does not block imports.
  Alphabetic currency prefixes must be whole words, so a date such as `31 mars 2026`
  in a layout table is not mistaken for an `ARS` balance. Lowercase words such as
  `ils`, `top`, `mur`, or `all` are not currency tokens. A number immediately
  followed by a unit, as in `SOS 24h/24`, is not a balance.
  Alphabetic currency tokens must belong to the local ISO 4217 code set, taken from
  [SIX List One](https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml)
  published on 2026-09-17. Ordinary text such as `© 2026 CIC` does not count as money. Scanning
  continues after nonmonetary text, so a later visible balance still fails the page.
  An HTML parser identifies comments and script and style bodies before table discovery and
  marker detection, preserving the remaining source markup. A fake `<script>` or `<style>`
  opening inside a comment or quoted attribute cannot consume visible contract tables up to
  a later closing tag. Account parsing and category discovery use the same preprocessing. JavaScript
  replacement strings such as `"$1"`, `"$1$2"`, and `"$1 $2"`, CSS selectors, and markup inside these
  bodies cannot be mistaken for accounts or balances. Account titles, account numbers, and balances
  are detected in leftover `<table>` or `<tr>` blocks through parsed element class tokens. Marker
  names in text, unrelated attributes, partial class names, or comments do not count as element classes.
  Plain-text balances are read from parsed HTML text nodes, excluding comments, attribute values,
  and script and style bodies. A `>` inside a quoted attribute cannot expose a hidden amount as text.
  Visible balances and actual account markers in those leftover table or row blocks still fail
  the page. Summary amounts, promotions, and grey-text (`doux`) elements outside tables or rows
  are ignored, as are unrelated tables without account data.
  Before certifying `snapshotComplete`, the page must end with an explicit `</html>` tag and
  every recognized contract table must have a closing tag, even an empty one. Account data
  outside a complete row within a contract table also fails with `UPSTREAM_FORMAT_CHANGED`.
  Cuts between tables, inside table openings, or within account rows cannot certify a partial
  snapshot. Supported categories require exactly one title, number, and readable euro balance
  per account row, including when only a title remains. A missing row end cannot merge two accounts
  into one. Header and action rows without account or balance markers
  are skipped. A complete checking-only page remains valid.
  `/accounts/listing` returns the accounts and `categoriesRead` (`CHECKING` / `SAVINGS`),
  distinguishing a present empty table from a missing category. This evidence is returned only
  after parsing succeeds. Both account endpoints collect categories during account validation,
  preprocessing the full page once instead of parsing it again for category discovery.
  `/accounts` keeps its existing bare-list response for older backends.
  External id `cic_<account number digits>`.
- **Backend**: `CicSyncService`, shaped like `CmesSyncService` (stored credentials, re-login with the
  previous session, daily source `cic`), balance-only accounts with `upsertSnapshot`. Migration
  V121 `cic_session`. `/api/cic/*`, `/api/cic/auth/complete` given 180 s in nginx.
  `/auth/initiate` and `/auth/reconnect` share 5 password checks per IP per 15 min; `/auth/complete`
  has its own 10, so retyped SMS codes do not use them up. An account a complete fetch no longer
  lists is closed at zero (balance 0, zero snapshot), not soft-deleted, so it can come back; only
  when the table of its category (current or savings) was read. `/accounts/listing` answers
  `{accounts, categoriesRead}`, `categoriesRead` naming the in-scope tables the page carried, an
  empty one included: closing the last livret leaves an empty savings table and closes the account,
  while a table missing from the page leaves its accounts as they were. Page validation completes
  before `/accounts/listing` reports any accounts or categories. An incomplete or malformed page
  returns `UPSTREAM_FORMAT_CHANGED` without a partial listing. `/accounts` still serves
  the bare list for backends that predate it, and the adapter falls back to that list when an older
  sidecar answers 404 on `/accounts/listing`; without the tables, a category counts as read when
  another account of it came back.
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

- Admin instance-status monitoring includes `cic-auth` when its session table has a row,
  including an expired session, using `CIC_AUTH_URL` and expecting the `sidecar-python` base.
  The unauthenticated `/health` endpoint, served by the shared `picsou_sidecar` app, echoes
  `PICSOU_SIDECAR_BASE` when set; without it, the response remains `{"status":"ok"}`.
  `InstanceStatusServiceTest` covers CIC's registry entry, URL and session-count supplier;
  `test_sidecar_auth.py` runs the shared `SidecarAuthContract`, which covers both health
  responses, and CI checks the built CIC image inherits `PICSOU_SIDECAR_BASE=sidecar-python`.
- `services/cic-auth/test_accounts_parser.py` retains the contract-link, currency, class-token,
  and hidden-markup cases alongside the restored completeness checks. It rejects truncated
  documents and tables, orphan or unparsed account rows, and merged account rows. Cuts
  before and throughout the savings-table opening, after its caption, inside a title class,
  after a balance, and after the last table cannot certify a snapshot. Totals,
  promotional amounts, and legal text outside tables or rows preserve all parsed accounts.
  Title-only contracts with empty balance cells or currency codes in their names are skipped,
  while visible balances after nested or malformed titles still fail the whole page.
  Non-cell balance elements, uppercase layout prose that overlaps ISO codes, comments and
  attributes containing fake script or style openings, and privacy of unknown-category error
  messages have explicit regression cases.
- `services/cic-auth/test_main.py` checks that incomplete and renamed tables return HTTP 502
  before a snapshot or category evidence is certified. Unknown-category warnings from the shared
  `euro-information` logger omit product names and balances. Complete checking-only pages remain
  valid, and listing responses distinguish missing savings tables from empty ones. Both account
  endpoints preserve accounts behind fake script openings and reject whole-number balances in
  malformed titles or grouped amounts nested in closed titles before certifying a snapshot.
- `CicAdapterTest` preserves the format-change error across the sidecar boundary.
  `CicSyncServiceTest` checks that a failed fetch leaves existing balances and snapshots untouched.
  It also covers category-aware closure with explicit table reporting and legacy bare-list responses,
  and rejects incomplete accounts before any account writes.
- `CicControllerTest` exercises the shared initiate/reconnect quota and independent completion
  quota through HTTP. `RateLimitConfigCicTest` checks both capacities and their 15-minute refill.
