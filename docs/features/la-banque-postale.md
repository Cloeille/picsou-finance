# Feature: La Banque Postale sync (sidecar, replaces Enable Banking)

> Last updated: 2026-11-06
> Status: 🟡 **Implemented offline, not live-validated.** The backend contract,
> storage, adapter, sync service and REST endpoints are in place. `services/lbp-auth`
> now has a real `httpx` client built from two live captures: login form,
> shuffled keypad (positions only), mobile-approval polling and XHTML parsers.
> The project has not run it against the bank yet; the only known risk is the
> bank's `iscdName` device fingerprint. Do not present this connector as usable
> until a live end-to-end run succeeds.

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
| `externalId` | SHA-1 of the bank's displayed account number, prefixed `lbp_` by the backend. The per-session `compte.numero` UUID is **not** stable and is used only for the statement URL. |
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

### Login (built from two live captures)

The keypad is **ten text buttons shuffled on every page load**. The bank's own
script writes the clicked **DOM positions**, not the digits, into the `password`
field; the server maps them back with the per-load keypad. The sidecar therefore
returns the labels to the UI, the user clicks his digits, and only the positions
travel onward: the password itself never reaches Picsou.

A trusted device skips the second factor. A new device gets *Votre appareil de
confiance* ("une notification a été envoyée vers …"), and the sidecar polls the
page's `URL_AF_POLLING` every 3 s until the bank redirects. No SMS path was
observed, but an additive `/otp` step could be added without changing the rest
of the contract.

| Endpoint | Body | Answer |
|---|---|---|
| `POST /initiate` | `{customerId}` | `{processId, keypad: {keys[10], columns}, expiresInSeconds}` |
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
- `iscdName` is a 52-character device fingerprint set by the bank's anti-fraud
  scripts. The client sends the form's value; if the server enforces it, only
  the login layer moves to Camoufox, with the same contract.

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
- The bank's `compte.numero` UUID changes at every login; accounts are keyed on
  a hash of the displayed account number so two sessions cannot create
  duplicates.
- The IBAN is not stored, so an Enable Banking sync cannot adopt these accounts
  by accident.
- Account types are set directly from `kind`; the savings config and its
  interest projection then work without the name-based detector.

### Configuration

`app.sidecars.lbp-auth.url` (env `LBP_AUTH_URL`, default
`http://lbp-auth:8001`), read by `SidecarProperties`. `LbpAdapter` asks
`SidecarWebClientFactory` for the `lbp-auth` service, which validates the URL and
adds the shared key.

### Error codes

`LbpAdapter` uses the shared `SidecarErrorCode`; the codes La Banque Postale may
store are declared once by `SidecarBank.LBP`:
`SESSION_EXPIRED`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_FORMAT_CHANGED`,
`INVALID_SESSION_STATE`, `INTERNAL_ERROR`, plus the login codes
`INVALID_CREDENTIALS`, `KEYPAD_CHANGED`, `KEYPAD_EXPIRED`, `INVALID_POSITIONS`,
`APP_VALIDATION_TIMEOUT`, `AUTH_ATTEMPT_EXPIRED`.
The `lbp_session.last_sync_error` CHECK enumerates exactly that set;
`SidecarBankCodesTest` fails if the two drift apart.

## What the captures showed

Two real sessions were captured (trusted device and new device):

1. **Password entry** — ten text buttons, **shuffled on every load**. The
   bank's script writes the clicked DOM positions into `password`, not the
   digits; the server decodes them. No JS-computed password.
2. **Mobile approval** — only on a non-trusted device. Trusted device skips it.
   No SMS path observed.
3. **Account data** — server-rendered XHTML, no account JSON. Synthesis pages
   plus `init-releve_ccp` / `init-releve_cne`.
4. **Anti-bot** — `fruprem.js` / `loreo.js` set a 52-character `iscdName`; no
   CAPTCHA observed.
5. **Session lifetime** — still not measured. `check` returns a conservative
   600 s and a dead session is detected on the next read.
6. **Transaction history** — `init-releve_ccp` returns about 90 days;
   `telechargementMouvements.ea` is the export path; the `relevePdf` history
   holds monthly statements.

The login layer is `httpx`; only if `iscdName` is enforced does it move to
Camoufox. The data layer stays cookie replay either way.

## State of the implementation (2026-11-06)

**Backend**

- `adapter/LbpAdapter.java` — concrete sidecar client (no port: one
  implementation, one caller), normalized snapshot and nested records, no retry,
  redacted request bodies.
- `adapter/sidecar/SidecarBank.java` — `LBP` declares the label, the `lbp-auth`
  service and the codes La Banque Postale may store.
- `model/LbpSession.java`, `model/LbpSyncStatus.java`,
  `repository/LbpSessionRepository.java`, `config/LbpSyncRecovery.java`,
  `config/SyncExecutorConfig.java` (`lbpSyncExecutor`), migration
  `V131__lbp_session.sql`.
- `service/LbpSyncService.java` — validates the whole snapshot, then writes every
  account and its transactions in one transaction; maps `kind` to
  `AccountType` directly.
- `controller/LbpController.java` — `/api/lbp/auth/{initiate,keypad,complete}`,
  `/sync`, `/status`, `/session`, with strict bodies and per-IP buckets.
- Tests: `LbpSessionTest`, `LbpAdapterTest`, `LbpControllerTest`, the shared
  `SidecarContractTest` rig, `SidecarBankCodesTest`, and the
  `V131LbpSessionMigrationTest`.

**Sidecar**

- `services/lbp-auth/main.py` implements the contract and its validation.
- `services/lbp-auth/bank.py` — `HttpxBankClient`: login form, keypad positions,
  mobile-approval polling, cookie serialization and page import. The pending
  store pins one HTTP client per login.
- `services/lbp-auth/parsers.py` — login form/keypad, account cards, operations
  table and polling URL, with typed `FormatError`.
- `services/lbp-auth/fixtures.py` — anonymized XHTML from the two captures,
  used by the tests only.
- Tests: the shared key contract, request validation, parsers and the full
  login/approval/import flow through `httpx.MockTransport` (29 cases), run in
  the published image like every other sidecar.
- Wired into Compose (`lbp` profile, `sidecar-python` base) and the Helm chart.

**Verification** — 36 LBP backend tests pass, plus the shared
`SidecarContractTest` (214 cases across every bank), `SidecarBankCodesTest`
(23 cases, including the `lbp_session` CHECK) and the `V131` migration test on
real PostgreSQL (5 cases). The 29 sidecar tests pass in the published image.
The previous date-dependent `DividendControllerAuthorizationTest` failure was
fixed upstream (relative ex-dates).

## Not in this slice

- A live end-to-end run: the client is built from two captures, but the project
  has not run it against the bank.
- Enforcement of `iscdName`: unknown. The Camoufox fallback is designed but not
  written.
- A frontend tab and login panel.
- Card, loan, life-insurance and PER parsing.
- History beyond roughly 90 days: the export and the monthly PDFs are not
  parsed.
- Scheduled sync and automatic re-authentication.
- Enable Banking adoption/deduplication by IBAN.
- Health check and sync-status feeds (`SyncStatusService`, `MemberSyncService`,
  MCP `SyncTools`) until the connector is live.

## Links

- ADR: [`docs/decisions/2026-11-05-la-banque-postale-sidecar.md`](../decisions/2026-11-05-la-banque-postale-sidecar.md)
- Reference implementation: [`docs/features/caisse-epargne.md`](caisse-epargne.md)
- PSD2 scope limit: [`docs/decisions/2026-03-01-dual-bank-providers.md`](../decisions/2026-03-01-dual-bank-providers.md)
- Savings classification and projection: [`docs/features/savings-livrets.md`](savings-livrets.md)
