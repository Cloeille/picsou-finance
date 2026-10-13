# Feature: Caisse d'Epargne sync (backend, slice 2)

> Last updated: 2026-10-13
> Status: backend integration offline-tested only (read path and login endpoints). No frontend, no compose wiring yet. The login has never run against the real bank, and the sidecar keypad table is still empty until it is captured live.

## Context

The `caisse-epargne-auth` sidecar replays a stored bank session to a bearer token and reads accounts and transactions (`POST /token-check`, `POST /accounts`). This backend slice stores that session encrypted, calls the sidecar on demand and writes the result. It mirrors the BoursoBank connector with the differences below.

## How it works

- `POST /api/caisse-epargne/sync` queues one sync (202). It is **on demand only**: no scheduler, no startup sync, no auto-resync hook. It is rate-limited like the other sync endpoints (10 requests/min per IP, shared `syncBuckets`).
- `GET /api/caisse-epargne/status` returns `isActive`, `syncStatus`, timestamps, `lastSyncError`, `unsupportedCount` and the `unsupported` list (`externalId`, `familyCode`).
- `DELETE /api/caisse-epargne/session` deletes the stored row and answers `{removed, bankSessionRevoked: false, message}`. **It does not revoke the bank session**, which may stay active until it expires. The UI must say so and must not claim a logout.
- The sync fetches outside any transaction, validates the whole snapshot, then writes every account and its transactions in one transaction. A failure on any account rolls all of them back.

### Storage (decision D5: cookies only)

Table `caisse_epargne_session` (migration `V109`) is `bourso_session` without `encrypted_credentials`. The password and the Secur'Pass are never stored. The session state is encrypted with `CryptoEncryption`. A session the bank rejects (`SESSION_EXPIRED`) or Picsou cannot read (`INVALID_SESSION_STATE`) becomes inactive and the user must reconnect: it cannot be renewed silently. `last_sync_error` is a CHECK listing exactly `CaisseEpargneErrorCode`. `unsupported_contracts` holds the last list of contracts the bank reported but Picsou does not import.

### Supported products

- **Current account** (`CURRENT_ACCOUNT` → `CHECKING`), with its transactions.
- **Livret A** (`LIVRET_A` → `LIVRET_A`), with its transactions.
- **LDDS** (`LDDS` → `LDDS`), with its transactions. It is a product of its own: it shares the Livret A's regulated rate, never its ceiling. The bank sends each account's own ceiling (`ceiling` in the sidecar contract); nothing hardcodes an LDDS ceiling or reuses the Livret A's.
- **PER** (`PER` → `PER`): balance only, zero or more. No transactions are read, no IBAN. A negative balance refuses the sync.
- **Personal loan** (`LOAN` → `LOAN`): the bank shows the outstanding capital as a figure <= 0; Picsou stores it positive (like any `LOAN`) and applies the liability sign itself. A positive outstanding refuses the sync. No transactions are read on the loan itself. See "Personal loan" below.
- **Card** (`CARD` → `CREDIT_CARD`): liability; balance = outstanding, stored <= 0 like the Amex card; a positive outstanding refuses the sync.
- **Not supported:** home insurance and any other contract family or sub code (PEA, life insurance, other savings). The sidecar lists them in `unsupported` by id and family code only; they never become accounts and never fail the sync.

### Personal loan

- **Second token.** The loan detail is refused to the main token. The sidecar mints a second OAuth token from the same stored SSO session (another client and scope), cached apart from the main one and only when the synthesis holds a loan. Nothing new is stored by the backend: the same encrypted session serves both.
- **Loan → Debt.** When the sidecar could read the detail (`loan` not null and every field usable), the sync creates or updates the loan account's `Debt`: borrowed amount, nominal rate, monthly payment (insurance included), monthly insurance, lender `Caisse d'Epargne`, `startDate = firstDueDate − 1 month` (the amortization schedule dates installment *i* at `startDate + i months`, so the first lands on the first due date), `endDate = lastDueDate`. Only these fields are written: file fees and anything else the user typed stay. A missing or odd detail never fails the sync; the account is still imported and an existing `Debt` is left as it was.
- **Debited account.** `Debt.linkedAccount` is the member's account with the loan's debit IBAN: the one in this import when the bank shows that IBAN exactly once, else an account already stored with it, else none. The IBAN itself is still never stored on a Caisse d'Epargne account.

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
- The IBAN is **not stored** and the card is **not linked** to its current account (see gaps).

### Error codes

`SESSION_EXPIRED`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_FORMAT_CHANGED`, `INVALID_SESSION_STATE`, `INTERNAL_ERROR`, plus the login codes `INVALID_CREDENTIALS`, `KEYPAD_CHANGED`, `APP_VALIDATION_TIMEOUT`, `AUTH_ATTEMPT_EXPIRED`. Same RFC 7807 `detail` scheme as Bourso.

### Configuration

`app.caisse-epargne-auth.url` (env `CAISSE_EPARGNE_AUTH_URL`, default `http://caisse-epargne-auth:8001`). The shared sidecar key and URL validation come from `SidecarWebClientFactory`.

## Authentication

Login is two backend calls that drive the sidecar's browser login (`POST /initiate`, `POST /complete`). The human approves the sign-in in the Sécur'Pass app: nothing automates that step.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/caisse-epargne/auth/initiate` | `{customerId, password}` | `{processId, mfaRequired: true, mfaType: "SECURPASS", expiresInSeconds}` |
| `POST /api/caisse-epargne/auth/complete` | `{processId}` | `{connected: true}` |

- `customerId` is 1 to 20 digits and `password` 4 to 20 digits; anything else is a 4xx before the sidecar is called and before an attempt is consumed. The password is read once and handed to the sidecar.
- **The password is never stored** (decision D5): not in the database, not in a service field, not in the pending-login map. The request DTO redacts it in `toString`, the adapter's request body does the same, and no error message or log line carries it.
- **No retry anywhere.** A wrong password consumes a bank attempt and can lock the account. The adapter has no retry filter (asserted against a real HTTP server), the service calls the sidecar once per request, and a process id is single use: it is removed before `/complete` is called, so a failure cannot be replayed from the backend.
- `/auth/initiate` is limited to **3 attempts per 15 minutes per IP** (`caisseEpargneAuthBuckets`, stricter than Bourso's 5). A refused call answers 429 with `Retry-After`. `/auth/complete` takes no password, so it does not draw from that bucket.
- The service keeps the pending login (`processId`, member, deadline) in memory only. `/complete` refuses an unknown, expired or other member's process id with `AUTH_ATTEMPT_EXPIRED` without contacting the sidecar. A restart drops the attempt and the user starts again.
- On success `completeAuth` stores the encrypted session through `storeSession` and does **not** queue a sync (sync stays on demand). A failed login never deactivates or deletes an existing session; only a sync that ends `SESSION_EXPIRED` or `INVALID_SESSION_STATE` does.
- Timeouts: `/initiate` 90 s, `/complete` 170 s (above the sidecar's 150 s wait for the phone).

### Sidecar to backend error mapping

| Sidecar | Backend code |
|---|---|
| 401 `INVALID_CREDENTIALS` | `INVALID_CREDENTIALS` |
| 409 `KEYPAD_CHANGED` | `KEYPAD_CHANGED` |
| 408 `APP_VALIDATION_TIMEOUT` | `APP_VALIDATION_TIMEOUT` |
| 410 `AUTH_ATTEMPT_EXPIRED` | `AUTH_ATTEMPT_EXPIRED` |
| 429 `TOO_MANY_PENDING` | `UPSTREAM_UNAVAILABLE`, "too many pending" message |
| 502 / 5xx / timeout | `UPSTREAM_UNAVAILABLE` (or `UPSTREAM_FORMAT_CHANGED` when the sidecar says so) |

The four login codes are in `CaisseEpargneErrorCode` and in the `last_sync_error` CHECK of `V109`, which was edited in place because it is not applied anywhere yet.

## Not in this slice

- A live login: the sidecar keypad table is empty until captured, so every login answers `KEYPAD_CHANGED` for now.
- Preview LINK / CREATE / SKIP at first sync.
- Matching with Enable Banking accounts by IBAN (the IBAN is deliberately not stored, so the EB sync cannot adopt these accounts by accident).
- Monthly card debit as an internal transfer excluded from the budget (needed to avoid double counting with the card account).
- Card link to its parent current account (`Account.parentAccountId` is documented for Revolut pockets only).
- Card nature (`CREDIT` / `DEFERRED_DEBIT` / `IMMEDIATE_DEBIT`): `Account` has no suitable field and no column is added in this slice. The adapter carries it; the service drops it.
- Livret A and LDDS ceiling, remaining deposit capacity, filling ratio and the current account overdraft: carried by the port, not persisted.
- PER holdings and contributions (balance only), and any insurance contract.
- Best-effort bank logout on delete.
- Frontend and docker-compose wiring.
- Sync status feeds (`SyncStatusService`, `MemberSyncService`, MCP `SyncTools`) do not know this connector.

## Verification boundaries

Covered offline: adapter against canned responses, session state machine, service mapping and atomicity (rollback with a real `TransactionTemplate`), controller rate limits, login service/adapter, V109 on real PostgreSQL (Testcontainers) and the entity/schema validation. Not covered: any call to the real sidecar or bank, the real `/accounts` payload beyond the contract in the slice spec, and card `accountIds=<cardPfmId>` (marked UNVERIFIED in the sidecar).
