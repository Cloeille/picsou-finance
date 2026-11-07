# Feature: Wise jars

> Last updated: 2026-11-03

## Context

Enable Banking links Wise through PSD2 and returns only the current balances. Money in Wise jars
(Wise's `SAVINGS` balances) was missing from Picsou. This connector reads the jars with the
member's Wise personal API token and keeps them as savings accounts. Enable Banking is unchanged
and still brings the Wise current balances with their transactions.

## How it works

The member creates a read-only personal token on wise.com (Your account → Integrations and tools
→ API tokens) and pastes it in the Wise Jars tab. `POST /api/wise/connect` reads the jars once
with it. A refused token stores nothing. A working token is encrypted with `CryptoEncryption`
and stored on `wise_connection`, one row per member, and the jars are imported in the same
request, which returns them.

A sync calls `GET /v2/profiles`, then `GET /v4/profiles/{id}/balances?types=SAVINGS` for each
profile, personal and business. Each jar becomes an account:

| Field | Value |
|-------|-------|
| `name` | the jar's name, with ` (Business)` on a business profile; `Jar {currency}` when the jar has no name. Cards show `Wise Jars` beneath it |
| `type` | `SAVINGS` on creation; a resync keeps whatever the member chose |
| `provider` | `Wise Jars` |
| `external_account_id` | `wise_jar_{balanceId}` |
| `current_balance` | `totalWorth.value`, else `amount.value` |

A resync only updates the balance, currency and sync time, then writes the day's snapshot
through the existing FX path. A jar the member deleted is not recreated. A currency with no
minor unit, or a balance that does not fit the ledger, skips that jar and keeps the others.

There are no transactions. Wise answers balance statements for EEA and UK personal tokens with
HTTP 403 and an SCA challenge, and its activity feed has display-only amounts.

HTTP 401 or 403 from Wise carries code `SESSION_EXPIRED`. A sync that gets it stores
`REAUTH_REQUIRED` on the connection instead of `ERROR`: only a new token fixes it. The Wise Jars
tab then shows "token refused" with the token form, where pasting a new token replaces the stored
one in place; the Sync All row opens that tab instead of retrying; `get_sync_status` and the MCP
trigger summary report `NEEDS_REAUTH`. Any other failure stores `ERROR` and keeps its retry
button. A malformed token carries `INVALID_TOKEN` and HTTP 429 carries `RATE_LIMITED`; the panel
translates these three codes and shows any other server message as sent. Redirects are
refused. Response bodies are capped while they are read (256 KB for profiles, 1 MB for
balances) through `BoundedUtf8Body`, shared with SimpleFIN.

The two Wise connections are told apart in the UI. The Wise Jars tab opens by saying it is only
for jars and that the current account stays on the Banks tab. On the Banks tab, an Enable Banking
connection whose institution name contains the word `Wise` gets a line beneath it: the jars do not
come through this connection, with a button that opens the Wise Jars tab. Once a token is stored,
the line says the jars sync through Wise Jars, or, after a failed sync (`ERROR` or
`REAUTH_REQUIRED`), that the last jar sync failed, with the same button.

Daily refresh is the `wise` source in `MemberSyncService`, right after SimpleFIN. Manual sync and
connect share a limit of 6 requests per minute per IP. Disconnect deletes the token and keeps
the accounts. Deleting the last jar account also deletes the connection. The table is created
by `V131__wise_connection.sql`.

### Key files

**Backend**
- `backend/src/main/java/com/picsou/port/WisePort.java` — the jar record and token limit
- `backend/src/main/java/com/picsou/adapter/WiseClient.java` — fixed host, token check, status handling
- `backend/src/main/java/com/picsou/adapter/WiseJson.java` — profile and balance parsing, jars only
- `backend/src/main/java/com/picsou/adapter/WiseTransport.java` — HTTP seam, faked in tests
- `backend/src/main/java/com/picsou/adapter/BoundedUtf8Body.java` — capped body reader, shared with SimpleFIN
- `backend/src/main/java/com/picsou/service/WiseSyncService.java` — connect, sync, jar upsert, status
- `backend/src/main/java/com/picsou/service/WiseStatusWriter.java` — `ERROR` status in its own transaction
- `backend/src/main/java/com/picsou/controller/WiseController.java` — `/api/wise`
- `backend/src/main/java/com/picsou/model/WiseConnection.java`, `repository/WiseConnectionRepository.java`
- `backend/src/main/resources/db/migration/V131__wise_connection.sql`
- Wiring: `RateLimitConfig` (`wiseRequestBuckets`), `MemberSyncService`, `SyncStatusService`,
  `mcp/tools/SyncTools.java`, `service/sync/SourceSyncResult.java`, `AccountConnectionService`

**Frontend**
- `frontend/src/components/sync/WisePanel.tsx` — token form, sync, disconnect
- `frontend/src/pages/sync/BankSyncTab.tsx` — `WiseJarsHint` under a Wise bank connection
- `frontend/src/pages/sync/WiseTab.tsx`, `pages/sync/SyncPage.tsx` — Sync page tab
- `frontend/src/components/shared/AddAccountModal.tsx` — add-account entry
- `frontend/src/components/sync/SyncAllModal.tsx` — Sync All row
- `frontend/src/features/sync/api.ts`, `hooks.ts`, `keys.ts`, `types/api.ts`
- `frontend/src/demo/index.ts` — stateful demo handlers
- `frontend/src/i18n/locales/{en,fr,de,es}.json` — `sync.wise.*`, `addAccount.desc.wise`

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Jars only | Enable Banking already brings the current balances with transactions. A token copy would duplicate them without history, and no field matches the two | Importing every balance |
| `provider = "Wise Jars"` | Enable Banking stamps the institution name, `Wise`. A shared string would merge both connections in Sync All | `Wise` |
| Created as `SAVINGS` | A jar is money set aside, and Wise types it `SAVINGS` | `CHECKING`, as SimpleFIN does when the protocol has no type |
| `totalWorth` as the balance | It includes an invested jar's market value, which is what the member owns | `amount`, the uninvested cash only |
| Connect imports at once | The token is reusable, so validating it and importing are one request; a refused token stores nothing | SimpleFIN's connect-then-sync, needed there because its setup token is single-use |
| Token limited to letters, digits and dashes | It goes into a header; anything else could inject a header line | Passing the pasted text as is |
| `@DynamicUpdate` on `WiseConnection` | A sync loads the row before calling Wise and saves its status after. A full-row update would put back the token it loaded over one a concurrent connect stored meanwhile | Optimistic locking, which needs a version column and turns the overlap into a failed sync |
| Jars-only connector, pointed to from the Wise bank connection | Wise refuses statements to personal tokens in the EEA and the UK, and no longer accepts signed requests from personal accounts, so only Enable Banking brings the current account's transactions. The hint keeps the split visible instead of making the member guess | Moving the whole Wise account to the token, which loses its transactions |
| `REAUTH_REQUIRED` stored for a refused token | Sync All and the panel can send the member to the token form instead of retrying a dead token; DEGIRO uses the same value | One `ERROR` for every failure, as SimpleFIN does, which hides that only the member can fix it |
| Sync status refreshes on failure too | A failed sync stores `ERROR`; refreshing only on success left the badge green beside the error | Refreshing on success only, as SimpleFIN does |

## Gotchas / Pitfalls

- Wise documents personal tokens for business accounts. A personal account's read-only token
  worked against the live API on 2026-11-03.
- A jar closed in Wise keeps its last balance in Picsou until the member deletes it.
- Moves between the Wise current account and a jar show on the Enable Banking account as
  ordinary transactions. Picsou does not mark them as internal transfers.
- A connection in `ERROR` failed for a reason other than a refused token (Wise unreachable, rate
  limited, unreadable answer). `get_sync_status` shows it as `FAILED` with `reauth=false`.

## Tests

Backend (`mvn test`):
- `WiseClientTest` — profiles then jars per profile, bearer header on every request and never in
  the URI, `totalWorth` then `amount`, unreadable rows skipped, 401/403 give `SESSION_EXPIRED`,
  429 and other statuses fail, malformed tokens send nothing
- `WiseSyncServiceTest` — connect stores the encrypted token only after Wise accepts it; sync
  creates `SAVINGS` jars, keeps a member's name and type, never resurrects a deleted jar, skips
  currencies with no minor unit; a refused token marks `REAUTH_REQUIRED`, other failures `ERROR`; `resyncReporting` maps a refused token
  to `NEEDS_REAUTH`; status masks the token
- `WiseControllerTest` — member from `UserContext`, validation, shared per-IP limit, errors
  without the token
- `WiseConnectionTest` — JSON serialization never includes the token
- `WiseConnectionPersistenceTest` (H2 slice) — a sync's status write keeps a token a concurrent
  connect stored meanwhile; `markError` and `markReauthRequired` survive the sync's rollback
- `BoundedUtf8BodyTest` — the capped reader moved out of `SimplefinClient`
- `AccountConnectionServiceTest`, `SyncStatusServiceTest`, `MemberSyncServiceTest`,
  `SourceSyncResultTest`, `SyncToolsTest` — the wiring above

Frontend (`bunx vitest run`, `bun run test:e2e`):
- `WisePanel.test.tsx` — including the badge turning to the failure state when a healthy
  connection's sync fails, the refused-token state and its replace form, and the translated
  error codes; `WiseTab.test.tsx`
- `SyncAllModal.test.tsx`, `AddAccountModal.test.tsx` — the Wise Jars row (a refused token opens
  the tab instead of syncing) and the add-account entry
- `BankSyncTab.test.tsx` — the hint under a Wise connection, its button, the connected and failed
  wordings, and no Wise status request when no Wise connection exists
- `e2e/wise.spec.ts` — demo-mode connect and disconnect, and the add-account entry; `e2e/sync.spec.ts`
  lists the Wise Jars tab

## Links

- ADR: [Wise jars beside Enable Banking](../decisions/2026-11-03-wise-jars-beside-enable-banking.md)
- API: [`/api/wise`](../../backend/docs/API.md#16d-wise-jars--apiwise)
- Related: [SimpleFIN sync](./simplefin-sync.md), [Bank sync](./bank-sync.md),
  [Account deletion removes its connection](../decisions/2026-08-11-account-deletion-removes-its-connection.md)
