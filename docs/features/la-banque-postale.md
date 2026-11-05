# Feature: La Banque Postale sync (sidecar, replaces Enable Banking)

> Last updated: 2026-11-05
> Status: 🟡 **Proposed** — the backend contract, storage, adapter, sync service
> and REST endpoints are in place, and `services/lbp-auth` exposes the same
> contract with a stub bank layer. **No live bank call has been made yet:** the
> login flow, the data endpoints and the anti-bot posture are unverified. Do not
> present this connector as usable until the recon described below is done.

## Context

Enable Banking is PSD2-scoped: it reaches La Banque Postale payment accounts but
not the regulated savings products (Livret A, LDDS, LEP, Compte sur Livret,
Livret Jeune, PEL/CEL). Those products are visible in the bank's own web client
*Comptes & Contrats → Épargne et placements*, so a sidecar can read them where
PSD2 cannot.

This connector is a **full replacement** for Enable Banking on La Banque Postale
accounts, not a savings-only add-on: one source of ids, one sync history, no
duplicate current account. See
[the ADR](../decisions/2026-11-05-la-banque-postale-sidecar.md).

## Scope

**Target scope**

- Current account and its transactions.
- Livret A, LDDS, LEP, Compte sur Livret, Livret Jeune (balances and, when the
  bank exposes them, transactions).
- PEL / CEL as savings accounts, with their balances.
- The deferred-debit card as a `CREDIT_CARD` liability (later slice).
- Personal and home loans as `LOAN` accounts with a `Debt` (later slice).

**Out of scope (first slice)**

- Life insurance and PER (separate insurer flows, like Caisse d'Epargne's).
- PEA and securities accounts.
- Automatic/scheduled sync.
- Bank logout on disconnect.
- IBAN-based matching with Enable Banking accounts.

## How it works

```
Sync page (La Banque Postale tab)
  │  user connects: identifier → keypad → mobile approval → session
  ▼
POST /api/lbp/auth/initiate ──► lbp-auth /initiate ──► bank login page
POST /api/lbp/auth/keypad   ──► lbp-auth /keypad   ──► clicked positions only
POST /api/lbp/auth/complete ──► lbp-auth /complete ──► wait for the mobile approval
                                     │
                                     ▼
                              encrypted session cookies
                                     │
POST /api/lbp/sync ───────────────► /accounts ──► normalized snapshot
                                     │
                                     ▼
                    LbpSyncService: one DB transaction
                    (accounts + transactions + snapshots)
```

The sidecar's backend-facing contract is **ours** and does not depend on how the
bank exposes its data. Only the transport below `/initiate` and `/accounts` is
bank-specific.

### Normalized snapshot

`POST /accounts` answers `{accounts, unsupported}`. Each account carries:

| Field | Meaning |
|---|---|
| `externalId` | Bank id, prefixed `lbp_` by the backend |
| `kind` | `CURRENT_ACCOUNT`, `LIVRET_A`, `LDDS`, `LEP`, `CSL`, `LIVRET_JEUNE`, `PEL`, `CEL`, `CARD`, `LOAN` |
| `name` | As the bank shows it |
| `balance` | Signed; a card's is its outstanding (≤ 0), a loan's is the bank's signed capital |
| `currency` | `EUR` only |
| `iban` | Never persisted by the backend |
| `ceiling`, `remainingDepositCapacity`, `fillingRatio` | Carried when the bank exposes them |
| `cardNature`, `parentExternalId`, `nextDueDate` | Cards only |
| `transactions` | `{externalId, date, dueDate, amount, currency, label, typeCode}` |
| `snapshotComplete` | The sidecar read the whole account, not a partial page |

`unsupported` lists products the bank reported but the sidecar does not import
(`{externalId, familyCode}`). They never become accounts and never fail a sync.

### Login (provisional — to be confirmed by recon)

The flow is the Caisse d'Epargne shape: a virtual keypad for the password, then
the human approves the sign-in in the bank's **mobile app** (a push, not a code
to type). It will be adjusted to the observed bank page; nothing in the backend
depends on the keypad specifically. Certicode is no longer the flow for a
modern account, but a bank may still fall back to an SMS code for some
customers — if the recon shows one, an additive `/otp` step can be added
without changing the rest of the contract.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /initiate` | `{customerId}` | `{processId, keypad: {images[10], columns}, expiresInSeconds}` |
| `POST /keypad` | `{processId, positions}` | `{processId, status: "APPROVAL_PENDING"}` |
| `POST /complete` | `{processId}` | `{sessionState}` |
| `POST /token-check` | `{sessionState}` | `{ok, expiresIn}` |
| `POST /accounts` | `{sessionState}` | `{accounts, unsupported}` |

- A password never reaches Picsou: only clicked key positions.
- The positions are never logged, echoed or stored; the approval carries no
  secret at all.
- No login step is retried: a wrong password can lock the bank account.
- An unknown, used, expired or other-member `processId` is refused before the
  sidecar is called.
- Pending logins live in memory only; a restart drops the attempt.

### Storage

Table `lbp_session`, modelled on `caisse_epargne_session`: one row per member,
`session_state` encrypted with `CryptoEncryption`, `last_validated_at`,
`is_active`, `sync_status`, `last_sync_started_at`, `last_sync_completed_at`,
`last_sync_error` (CHECK on the error codes), `unsupported_contracts`.
Deliberately no credentials column.

A session the bank rejects (`SESSION_EXPIRED`) or that cannot be decrypted
(`INVALID_SESSION_STATE`) becomes inactive and must be renewed by the user. A
transient failure leaves it usable.

### Sync semantics

- `POST /api/lbp/sync` queues one sync (202). On demand only; rate-limited like
  the other sync endpoints.
- The sync fetches outside any transaction, validates the whole snapshot, then
  writes every account and its transactions in **one** transaction. One
  doubtful account refuses the whole import: a partial list never overwrites a
  correct one.
- Transactions are upserted by `(account, externalId)`; rows are never deleted
  and manual rows are never touched.
- A soft-deleted account is skipped, not recreated.
- The IBAN is not stored, so an Enable Banking sync cannot adopt these accounts
  by accident.
- Account types are set directly from `kind`; the savings config and its
  interest projection then work without the name-based detector.

### Configuration

`app.lbp-auth.url` (env `LBP_AUTH_URL`, default `http://lbp-auth:8001`). The
shared sidecar key and URL validation come from `SidecarWebClientFactory`.

### Error codes

`SESSION_EXPIRED`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_FORMAT_CHANGED`,
`INVALID_SESSION_STATE`, `INTERNAL_ERROR`, plus the login codes
`INVALID_CREDENTIALS`, `KEYPAD_CHANGED`, `KEYPAD_EXPIRED`, `INVALID_POSITIONS`,
`APP_VALIDATION_TIMEOUT`, `AUTH_ATTEMPT_EXPIRED`.
The `last_sync_error` CHECK enumerates them; adding one needs a migration.

## Recon checklist (blocking)

Capture one real session, with `Cookie`, `Authorization` and any token redacted,
and note:

1. **Password entry** — virtual keypad or text field? Are key images shuffled?
   Is the keypad value computed by page JavaScript?
2. **Mobile approval** — requested on every login, only on a new device, or
   only for sensitive operations? Is there a trusted-device cookie? Does the
   bank still fall back to an SMS code (Certicode) for some accounts?
3. **Account data** — JSON XHR or server-rendered HTML? Which calls return the
   account list, a livret's balance and its transactions?
4. **Anti-bot** — TLS fingerprint, JavaScript challenge, CAPTCHA, rate limiting?
5. **Session lifetime** — how long a replayed cookie jar stays usable.
6. **Transaction history** — does the bank expose it for livrets, or only
   balances?

The answers choose between an `httpx` sidecar (light, replay only) and a
Camoufox sidecar (drives the login itself). The rest of the design does not
change.

## State of the implementation (2026-11-05)

**Backend**

- `port/LbpPort.java` — normalized snapshot and the keypad + mobile-approval login.
- `port/LbpErrorCode.java`, `model/LbpSession.java`, `model/LbpSyncStatus.java`,
  `repository/LbpSessionRepository.java`, `config/LbpSyncConfig.java`,
  `config/LbpSyncRecovery.java`, migration `V122__lbp_session.sql`.
- `adapter/LbpAdapter.java` — sidecar client, no retry, redacted request bodies.
- `service/LbpSyncService.java` — validates the whole snapshot, then writes every
  account and its transactions in one transaction; maps `kind` to
  `AccountType` directly.
- `controller/LbpController.java` — `/api/lbp/auth/{initiate,keypad,complete}`,
  `/sync`, `/status`, `/session`, with strict bodies and per-IP buckets.
- Tests: `LbpSessionTest`, `LbpAdapterTest`, `LbpControllerTest`,
  `V122LbpSessionMigrationTest`.

**Sidecar**

- `services/lbp-auth/main.py` implements the contract and its validation.
- `services/lbp-auth/bank.py` is a stub: every bank call answers
  `503 BANK_FLOW_UNVERIFIED` until the recon. Replacing `UnverifiedBankClient`
  is the only code change the live connector needs.
- Tests: the shared key contract and the request/response contract (with a fake
  client), run in the published image like every other sidecar.
- Wired into Compose (`lbp` profile, `sidecar-python` base) and the Helm chart.

**Verification** — 21 backend tests and 12 sidecar tests pass offline. The full
backend suite has one unrelated, date-dependent failure in
`DividendControllerAuthorizationTest` (its dividend events are all before the
current date).

## Not in this slice

- Any live bank call: the connector has never authenticated against La Banque
  Postale.
- A frontend tab and login panel.
- Loan, life-insurance and PER parsing.
- Scheduled sync and automatic re-authentication.
- Enable Banking adoption/deduplication by IBAN.
- Health check and sync-status feeds (`SyncStatusService`, `MemberSyncService`,
  MCP `SyncTools`) until the connector is live.

## Links

- ADR: [`docs/decisions/2026-11-05-la-banque-postale-sidecar.md`](../decisions/2026-11-05-la-banque-postale-sidecar.md)
- Reference implementation: [`docs/features/caisse-epargne.md`](caisse-epargne.md)
- PSD2 scope limit: [`docs/decisions/2026-03-01-dual-bank-providers.md`](../decisions/2026-03-01-dual-bank-providers.md)
- Savings classification and projection: [`docs/features/savings-livrets.md`](savings-livrets.md)
