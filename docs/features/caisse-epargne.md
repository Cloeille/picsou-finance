# Feature: Caisse d'Epargne sync

> Last updated: 2026-10-31
> Status: sidecar, backend and frontend are in the codebase. `docker/docker-compose.yml` runs the `caisse-epargne-auth` sidecar; `application.yml` gives the backend its URL. The user connects from the Sync page (Caisse d'Epargne tab) or the add-account modal: a pop-up shows the bank's virtual keypad, the user clicks his own digits, then approves in Sécur'Pass. There is no keypad table: the sidecar never maps a key image to a digit. Sync is on demand only. Tested offline only: no test covers a full login against the real bank, and the card-transaction `accountIds` is still unverified live (see the sidecar README).

## Context

The `caisse-epargne-auth` sidecar drives the browser login (`POST /initiate`, `POST /keypad`, `POST /complete`), then replays the stored bank session to a bearer token and reads accounts and transactions (`POST /token-check`, `POST /accounts`). The backend stores that session encrypted, calls the sidecar on demand and writes the result. It mirrors the BoursoBank connector with the differences below.

## How it works

**PER detail:** the sidecar reads the insurer detail the way the bank's insurer app does
(observed live 2026-10-15): `prepareRouting` for the insurer's own contract id, a third OAuth
token from the same stored session, the insurer's `clientToken` JWT, then
`etna-web/rest/v2/contratVie/load`. See the sidecar README. A PER whose
detail cannot be read is listed in `unavailable` and keeps its last values; it never fails the
other accounts. Mixed euro-fund/support classification is still tested with synthetic fixtures
only (the observed contract held no euro fund). A real end-to-end sync is still required
before claiming a successful PER import.

- `POST /api/caisse-epargne/sync` queues one sync (202). It is **on demand only**: no scheduler, no startup sync, no auto-resync hook. It is rate-limited like the other sync endpoints (10 requests/min per IP, shared `syncBuckets`).
- `GET /api/caisse-epargne/status` returns `isActive`, `syncStatus`, timestamps, `lastSyncError`, `unsupportedCount` and the `unsupported` list (`externalId`, `familyCode`).
- `DELETE /api/caisse-epargne/session` deletes the stored row and answers `{removed, bankSessionRevoked: false, message}`. **It does not revoke the bank session**, which may stay active until it expires. The UI must say so and must not claim a logout.
- The sync fetches outside any transaction, validates the whole snapshot, then writes every account and its transactions in one transaction. A failure on any account rolls all of them back.

### Storage (decision D5: cookies only)

Table `caisse_epargne_session` (migration `V109`) is `bourso_session` without `encrypted_credentials`. The password and the Secur'Pass are never stored. The session state is encrypted with `CryptoEncryption`. A session the bank rejects (`SESSION_EXPIRED`) or Picsou cannot read (`INVALID_SESSION_STATE`) becomes inactive and the user must reconnect: it cannot be renewed silently. `last_sync_error` is a CHECK listing exactly `SidecarBank.CAISSE_EPARGNE.codes()`. `unsupported_contracts` holds the last list of contracts the bank reported but Picsou does not import.

### Supported products

- **Current account** (`CURRENT_ACCOUNT` → `CHECKING`), with its transactions.
- **Livret A** (`LIVRET_A` → `LIVRET_A`), with its transactions.
- **LDDS** (`LDDS` → `LDDS`), with its transactions. It is a product of its own: it shares the Livret A's regulated rate, never its ceiling. The bank sends each account's own ceiling (`ceiling` in the sidecar contract); nothing hardcodes an LDDS ceiling or reuses the Livret A's.
- **PER** (`PER` → `PER`): owned unit-linked positions (ISIN, quantity, bank NAV, NAV date and line value) and the separate euro-fund pocket when complete insurer details are available. EUR market quotes update each resolved line between bank syncs; other lines retain their bank value, marked stale with the bank NAV date. The euro fund stays fixed until another sync. No transactions are read, no IBAN. Negative or unreconciled details refuse the sync. Legacy payloads without PER details preserve existing positions rather than clearing them.
- **Personal loan and home loan** (`LOAN` → `LOAN`; sub codes `12` and `13`): the bank shows the outstanding capital as a figure <= 0; Picsou stores it positive (like any `LOAN`) and applies the liability sign itself. A positive outstanding refuses the sync. No transactions are read on the loan itself. See "Personal loan" below.
- **Card** (`CARD` → `CREDIT_CARD`): liability; balance = outstanding, stored <= 0 like the Amex card; a positive outstanding refuses the sync. Its nature (`IMMEDIATE_DEBIT`, `DEFERRED_DEBIT`, `CREDIT`) is stored in `account.card_nature` (migration `V111`), with `paymentDueAmount` (the outstanding) and `paymentDueDate` (next due date).
- **Not supported:** home insurance and any other contract family or sub code (PEA, life insurance, other savings). The sidecar lists them in `unsupported` by id and family code only; they never become accounts and never fail the sync.

### Personal loan

- **Second token.** The loan detail is refused to the main token. The sidecar mints a second OAuth token from the same stored SSO session (another client and scope), cached apart from the main one and only when the synthesis holds a loan. Nothing new is stored by the backend: the same encrypted session serves both.
- **Loan → Debt.** When the sidecar could read the detail (`loan` not null and every field usable), the sync creates or updates the loan account's `Debt`: borrowed amount, nominal rate, monthly payment (insurance included), monthly insurance, lender `Caisse d'Epargne`, `startDate = firstDueDate − 1 month` (the amortization schedule dates installment *i* at `startDate + i months`, so the first lands on the first due date; when the first due date is the last day of its month and the last due date falls on a later day, the first was clamped, so `startDate` takes the last due date's day: a loan from 2042-02-28 to 2042-05-31 runs Feb 28, Mar 31, Apr 30, May 31; when the anchor month is itself shorter, as for a loan from 2042-03-31 whose `startDate` can only be 2042-02-28, the schedule takes the due day from `endDate`, see `docs/features/loans.md`), `endDate = lastDueDate`. Only these fields are written: file fees and anything else the user typed stay. A missing or odd detail never fails the sync; the account is still imported and an existing `Debt` is left as it was. An amount the `debt` columns (`NUMERIC(20, 8)`) cannot hold, from 999 999 999 999.999999995 up, counts as odd: its insert would roll back every account of the import.
- **Home loan.** Same import, read from the home-loan app with a third token on the BPCE hosts (see the sidecar README). Its detail gives no debit IBAN: the sync never sets or clears the `Debt`'s linked account, so a property the user linked the mortgage to stays linked across syncs and still counts in that property's equity. The installment rows are not tagged.
- **Duration limits.** Both loan parsers accept integer durations from 1 through 600 months (50 years). The ceiling bounds the cost of the exact annuity fallback. Longer durations raise `INVALID_LOAN_DETAIL` before date or insurance calculations. A home loan within the limit whose computed first due date falls before year 1 raises the same error. Only the affected loan is listed in `unavailable`; all other accounts remain importable.
- **Insurance.** For both loan types, monthly insurance is the installment minus the nominal-rate annuity, rounded to the cent with half-cent ties rounded up. The annuity divides the borrowed amount by the sum of discounted payments. Binary doubling computes that sum in logarithmic time without subtracting nearly equal numbers. Its Decimal precision grows with the rate's negative exponent. Directed rounding bounds the exact annuity from below and above. If those bounds round to different cents, exact rational arithmetic determines the rounded annuity. A synthetic loan of 100.50 over two months at 12 percent has an exact annuity of `51.005`, rounded to `51.01`, leaving insurance of `28.99` from a payment of 80. A loan of 12,000.24 over 48 months with a payment of 280 and a nominal rate of `2e-100` percent retains its annuity of `250.01` and insurance of `29.99`. With a principal of 12,000 at `1e-56` percent, insurance remains `30.00`.
- **Debited account.** When the bank sends a debit IBAN, `Debt.linkedAccount` is the member's EUR account with that IBAN: the one in this import when the bank shows that IBAN exactly once, else an account already stored with it, else none. The connector accepts only EUR, so the stored-account lookup includes currency. EUR and GBP accounts imported by Enable Banking can share an IBAN without making loan linking ambiguous or aborting the sync. The IBAN itself is still never stored on a Caisse d'Epargne account.

### Budget tagging of the deferred-debit card settlement

The card's operations already count once as expenses on the card account. The monthly debit that settles a `DEFERRED_DEBIT` card on its current account is therefore tagged `virement-interne` (internal transfer), so it is not counted twice. Strict on purpose: the current account is the card's parent as the bank reports it (`parentExternalId`) and must be in this import, the due date must be reached, the debit must fall on that day for exactly the sum of the card's operations for that due date, with a settlement type code (or none), exactly one candidate and no category yet. Anything else stays untouched; re-running changes nothing. A member with no `virement-interne` transfer category gets no tagging. The card account itself is never given a `parentAccountId` (see gaps).

### Budget tagging of loan rows

On the current account the loan is debited from, two kinds of rows are categorized. The rules match the card settlement tagging:

- The debited account must be **in this same import** under an IBAN the bank shows once. A matching account stored outside the import is not tagged (none of its rows are in the import).
- **Disbursement:** a credit equal to the borrowed amount (±0.01), whose label contains `FINANCEMENT` (case-insensitive), dated from the signature date − 5 days to the first due date → `virement-interne` (internal transfer, not income). Skipped when the bank gives no signature date.
- **Installment:** for each due date `firstDueDate + k months` up to the last due date, a debit equal to the monthly payment (±0.01), dated within ±5 days → `credit` (expense, named *Crédit*). That default category is created for the member on first need; an archived one is never revived, and the rows then stay untagged.
- Exactly one candidate per expected event, else nothing is tagged. A row that already has a category is never overwritten. Re-running the sync changes nothing. Only counts are logged.

- External account ids and transaction ids are prefixed `ce_`. Transactions are upserted by `(account, externalId)`; rows are never deleted, manual rows are never touched.
- Anything unexpected refuses the whole snapshot with `UPSTREAM_FORMAT_CHANGED`: unknown `kind`, non-EUR, missing balance, duplicate account, `snapshotComplete=false`, empty list, id over 100 characters.
- A positive computed card outstanding refuses the whole sync (`UPSTREAM_FORMAT_CHANGED`) on purpose: the sign of the amount is unconfirmed, and it stays refused until a live run confirms it.
- `unsupported` contracts are only counted in the status. They never become accounts.
- Only the synthesis and the current account can fail the whole sidecar import. Any other account the sidecar cannot read (Livret A, LDDS, card, loan detail, PER detail) is left out of `accounts` and listed in `unavailable` (`externalId`, `kind`, `code`). The backend ignores that key: the account is simply not updated this run, and nothing is deleted.
- The IBAN is **not stored** and the card account is **not linked** to its current account (see gaps).

### Error codes

`SESSION_EXPIRED`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_FORMAT_CHANGED`, `INVALID_SESSION_STATE`, `INTERNAL_ERROR`, plus the login codes `INVALID_CREDENTIALS`, `KEYPAD_CHANGED`, `APP_VALIDATION_TIMEOUT`, `AUTH_ATTEMPT_EXPIRED`, `KEYPAD_EXPIRED`, `INVALID_POSITIONS` (the last two added to the `last_sync_error` CHECK by `V110`). Same RFC 7807 `detail` scheme as Bourso.

### Configuration

`app.sidecars.caisse-epargne-auth.url` (env `CAISSE_EPARGNE_AUTH_URL`, default `http://caisse-epargne-auth:8001`). The shared sidecar key and URL validation come from `SidecarWebClientFactory`.

## Authentication

Login is three backend calls that drive the sidecar's browser login (`POST /initiate`, `POST /keypad`, `POST /complete`). The bank's password page is a virtual keypad whose ten key images are shuffled at every connection. The sidecar does not read them: it sends the images to the UI, the user clicks his own digits in a pop-up (`CaisseEpargnePanel`), and only the clicked positions come back. The human then approves the sign-in in the Sécur'Pass app: nothing automates that step. The completion request stays open while they do; when the approval happens in the bank's app on the same iPhone, iOS drops Safari's pending request (nginx logs a 499) while the backend carries on and stores the session. The panel then keeps the Sécur'Pass card and polls the session status every 3 s for up to 180 s: as soon as it is live it queues the first sync, as a direct answer would have. The bank's session lives a few minutes only, so a sync started by hand much later finds it expired.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/caisse-epargne/auth/initiate` | `{customerId}` | `{processId, keypad: {images: [10 PNG data URIs], columns: 5}, expiresInSeconds}` |
| `POST /api/caisse-epargne/auth/keypad` | `{processId, positions}` | `{processId}` |
| `POST /api/caisse-epargne/auth/complete` | `{processId}` | `{connected: true}` |

- Bodies are read strictly: an unexpected field (a password, typically) is refused, never ignored. `customerId` is 1 to 20 digits; `positions` is 6 to 12 indexes, each 0 to 9, in the user's click order. Anything else is a 4xx before the sidecar is called and before an attempt is consumed.
- **No password reaches Picsou.** It only exists in the user's browser, as clicks on key images. The positions are the password in disguise: they are never stored, logged, echoed or put in an error, and the adapter's request bodies redact the identifier and the positions in `toString`. Decision D5 still holds: only the session cookies are stored.
- The keypad the sidecar returns is checked before it is shown: exactly 10 images in 5 columns, each a PNG data URI of at most 16 KB with a plain base64 payload. Anything else is `KEYPAD_CHANGED` and the process is never registered. The UI renders the images with `<img src>`.
- **No retry anywhere.** A wrong password consumes a bank attempt and can lock the account. The adapter has no retry filter (asserted against a real HTTP server), the service calls the sidecar once per request, and each step takes the process id out before calling the sidecar, so a failure cannot be replayed from the backend. `/keypad` is accepted once per process.
- Rate limits per IP: `/auth/initiate` **3 per 15 minutes** (`caisseEpargneAuthBuckets`, stricter than Bourso's 5); `/auth/keypad` 5 per 15 minutes in its own bucket, so it never eats a login attempt. A refused call answers 429 with `Retry-After`. `/auth/complete` takes no secret and draws from no bucket.
- The service keeps the pending login (`processId`, member, deadlines) in memory only: the keypad deadline is the sidecar's `expiresInSeconds`, the approval window 180 s after the keypad step. An unknown, used, expired or other member's process id is refused with `AUTH_ATTEMPT_EXPIRED` (or `KEYPAD_EXPIRED` when the user took too long to click) without contacting the sidecar. A restart drops the attempt and the user starts again.
- On success `completeAuth` stores the encrypted session through `storeSession` and does **not** queue a sync (sync stays on demand). A failed login never deactivates or deletes an existing session; only a sync that ends `SESSION_EXPIRED` or `INVALID_SESSION_STATE` does.
- Timeouts: `/initiate` 90 s, `/keypad` 70 s, `/complete` 170 s (above the sidecar's 150 s wait for the phone).

### Recovering a lost completion response

The panel queues the first sync once after login. Both a successful `/auth/complete`
response and an active session discovered through `/status` use the same first-sync path.
A bank session can expire after a few minutes, so the panel starts this sync as soon as
login is confirmed. The parent view is notified only after that first sync succeeds.

If the completion response is lost (no HTTP response, 502 or 504), the panel keeps the
Sécur'Pass approval card. It polls `/status` every 3 seconds for up to 180 seconds after
the loss, covering the backend's 170-second completion window with a margin.
Each tick sends an independent status read with a 10-second timeout. Slow reads
can finish without the next tick cancelling them, and a stalled read cannot
block subsequent checks. Overlapping live responses still queue only one sync.
If no active session appears, the panel shows the approval timeout and returns to the
login form. Other completion errors are shown immediately.

Recovery never replays `/auth/initiate`, `/auth/keypad` or `/auth/complete`.
Polling stops after 180 seconds. A read sent before that deadline can still confirm
the session within its 10-second limit and clear the timeout message.
Responses from expired reads, an earlier login attempt, or an unmounted panel
cannot queue a sync.
The approval countdown starts at the sidecar's 150-second human wait. If the
completion response is lost, it restarts at 180 seconds to show the recovery window.

### Sidecar to backend error mapping

| Sidecar | Backend code |
|---|---|
| 401 `INVALID_CREDENTIALS` | `INVALID_CREDENTIALS` |
| 409 `KEYPAD_CHANGED` | `KEYPAD_CHANGED` |
| 408 on `/keypad` | `KEYPAD_EXPIRED` |
| 408 on `/initiate` or `/complete` | `APP_VALIDATION_TIMEOUT` |
| 422 on `/keypad` | `INVALID_POSITIONS` |
| 410 `AUTH_ATTEMPT_EXPIRED` | `AUTH_ATTEMPT_EXPIRED` |
| 429 `TOO_MANY_PENDING` | `UPSTREAM_UNAVAILABLE`, "too many pending" message |
| 502 / 5xx / timeout | `UPSTREAM_UNAVAILABLE` (or `UPSTREAM_FORMAT_CHANGED` when the sidecar says so) |

The login codes are in `SidecarBank.CAISSE_EPARGNE` and in the `last_sync_error` CHECK (`V109`, extended by `V110` for `KEYPAD_EXPIRED` and `INVALID_POSITIONS`).

## Not in this slice

- A live end-to-end login: everything above is tested offline only (the sidecar README lists the selectors not yet observed live).
- Automatic sync: no scheduler, no startup sync, no auto-resync hook.
- Preview LINK / CREATE / SKIP at first sync.
- Matching with Enable Banking accounts by IBAN (the IBAN is deliberately not stored, so the EB sync cannot adopt these accounts by accident).
- Card account link to its parent current account (`Account.parentAccountId` is documented for Revolut pockets only, and the dashboard skips any account with a parent, which would drop the card liability).
- Livret A and LDDS ceiling, remaining deposit capacity, filling ratio and the current account overdraft: carried by the port, not persisted. Each account keeps its own ceiling from the bank; nothing is shared between the two.
- PER contributions and historical unit counts, and other insurance contracts. Current PER quantities are never used to reconstruct past ownership; portfolio history uses actual balance observations, not a backfilled series multiplied by today's quantities. Investment P&L is unavailable when acquisition cost is unknown. Scheduled snapshots can update today's PER observation using current prices.
- Best-effort bank logout on delete.
- Sync status feeds (`SyncStatusService`, `MemberSyncService`, MCP `SyncTools`) do not know this connector.

## Verification boundaries

Loan regressions cover acceptance at 600 months and rejection at 601 months in both parsers. The home-loan boundary cases use a subscription date early enough that the date-order check cannot reject 601 months first. Separate home-loan cases cover calendar underflow at 600 months. Fetch tests verify that both an oversized duration and calendar underflow leave only the affected loan unavailable, with every other account imported.

Covered offline: sidecar login and read path against canned pages and responses, adapter against canned responses, session state machine, service mapping and atomicity (rollback with a real `TransactionTemplate`), controller rate limits, login service/adapter, the sync tab and login panel (vitest, including recovery and timeout after a network error, 502 or 504, recovery with slow or stalled status responses, and rejection of stale responses), the Caisse d'Epargne migrations on real PostgreSQL (Testcontainers) and the entity/schema validation. `SyncServiceCurrencyMatchTest` imports synthetic EUR and GBP accounts sharing an IBAN, then persists a Caisse d'Epargne loan and its debt through real H2 repositories. It checks that the sync succeeds and links the debt to the EUR account. Not covered: any call to the real sidecar or bank, the real `/accounts` payload beyond the contract in the slice spec, and card `accountIds=<cardPfmId>` (marked UNVERIFIED in the sidecar).
