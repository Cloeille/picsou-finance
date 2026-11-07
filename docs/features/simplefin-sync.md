# Feature: SimpleFIN sync

> Last updated: 2026-10-07

## Context

Enable Banking covers European open-banking institutions. SimpleFIN is a separate, token-based
protocol used mostly for US banks through
[SimpleFIN Bridge](https://bridge.simplefin.org/simplefin/create). The member links banks on that
server, pastes one setup token into Picsou, and Picsou imports balances and posted transactions.
Enable Banking is unchanged.

## How it works

A setup token is a Base64-encoded claim URL. Picsou POSTs it once and receives an access URL with
HTTP Basic credentials embedded (`https://user:pass@host/simplefin`). That URL is encrypted with
`CryptoEncryption` and stored on `simplefin_connection`, one row per member. The setup token is
not kept. The member is looked up before the claim, so a request for an unknown member never
spends the token.

Sync calls `GET /accounts?version=2&start-date=` with the credentials in an `Authorization`
header, not in the request URI. Each account becomes a Picsou account with provider `SimpleFIN`
and external id `sfin_{connId}_{accountId}`. The institution's `org_name` is prefixed onto the
account name (`Chase — Checking`). The connection `name` often includes the member and is only
used when `org_name` is absent.

A new account is created as `CHECKING`, like Enable Banking's. The member sets savings, credit
card, or any other type in the account form; a resync only updates the balance, currency, and
sync time, so that choice is kept. The reported balance is stored as sent and snapshotted in EUR
through the existing FX path.

Posted transactions from the same response go through `BankTransactionImportService.importProvided`,
which dedups on the provider id and cuts a description longer than 255 characters to fit the
ledger column. The download always asks for the shared history window, clamped to 89 days on the
UTC date: the bridge rejects an inclusive 90-day span, and `start-date` is sent as midnight UTC.
Every account shares that one response; rows already stored are dropped.

The claim URL and the access URL must be `https://beta-bridge.simplefin.org`, on the default port
or 443. Any other scheme, host or port is refused before a request is sent, so a pasted token
cannot make Picsou call an internal address, and a claim response cannot redirect the daily
credentialed request elsewhere. Redirects are refused, because the next URL would be chosen by the
remote server. A literal `+` in the access username or password stays a plus. Response bodies are
capped while they are read (8 KiB for a claim, 2 MiB for accounts). Error messages from Bridge are
collapsed to one line and cut at 300 characters each before they reach the UI or a log.

Connect stores the access URL even when the first download fails, because the setup token cannot
be claimed twice. A later Sync retries it. HTTP 403 on sync means the access was revoked: the
error carries code `SESSION_EXPIRED`, so Sync All and the MCP trigger summary report
`NEEDS_REAUTH`. Disconnect deletes the connection row and leaves the accounts. Deleting the last
SimpleFIN account also deletes the connection, same rule as the other connectors.

Daily refresh is the `simplefin` source in `MemberSyncService`, after IBKR, so the 08:00 job and
the MCP full sync both run `SimplefinSyncService.resyncReporting`. `trigger_bank_sync` runs it with
Enable Banking, and `get_sync_status` shows its stored status. Manual sync is rate-limited with
connect, 6 requests per minute per IP. The table is created by `V113__simplefin_connection.sql`,
which also widens `account.external_account_id` and `transaction.external_transaction_id` to 255
characters.

### Key files

**Backend**
- `backend/src/main/java/com/picsou/port/SimplefinPort.java` — claim and fetch contract, account-set records, the 4096-character token limit
- `backend/src/main/java/com/picsou/adapter/SimplefinClient.java` — claim and accounts fetch, status handling, body caps
- `backend/src/main/java/com/picsou/adapter/SimplefinUrls.java` — token decoding, host and port allowlist, Basic auth header
- `backend/src/main/java/com/picsou/adapter/SimplefinJson.java` — account-set parsing, pending rows dropped, error text capped
- `backend/src/main/java/com/picsou/adapter/SimplefinTransport.java` — HTTP seam, faked in tests
- `backend/src/main/java/com/picsou/service/SimplefinSyncService.java` — connect, sync, account upsert, status
- `backend/src/main/java/com/picsou/service/SimplefinStatusWriter.java` — `ERROR` status in its own transaction
- `backend/src/main/java/com/picsou/service/BankTransactionImportService.java` — `importProvided`, shared with Enable Banking
- `backend/src/main/java/com/picsou/controller/SimplefinController.java` — `/api/simplefin`
- `backend/src/main/java/com/picsou/dto/SimplefinConnectRequest.java`, `SimplefinConnectionStatusResponse.java`
- `backend/src/main/java/com/picsou/model/SimplefinConnection.java`, `repository/SimplefinConnectionRepository.java`
- `backend/src/main/java/com/picsou/config/RateLimitConfig.java` — `simplefinRequestBuckets`
- `backend/src/main/java/com/picsou/service/MemberSyncService.java`, `service/sync/SourceSyncResult.java` — scheduled order, `NEEDS_REAUTH` mapping
- `backend/src/main/java/com/picsou/service/SyncStatusService.java`, `mcp/tools/SyncTools.java` — MCP status and bank trigger
- `backend/src/main/java/com/picsou/service/AccountConnectionService.java` — `sfin_` prefix for deletion cleanup
- `backend/src/main/resources/db/migration/V113__simplefin_connection.sql`

**Frontend**
- `frontend/src/components/sync/SimplefinPanel.tsx` — token form, sync, disconnect
- `frontend/src/pages/sync/SimplefinTab.tsx`, `pages/sync/SyncPage.tsx` — Sync page tab
- `frontend/src/components/shared/AddAccountModal.tsx` — add-account entry
- `frontend/src/components/sync/SyncAllModal.tsx` — Sync All row
- `frontend/src/features/sync/api.ts`, `features/sync/hooks.ts`, `types/api.ts`
- `frontend/src/demo/index.ts` — stateful demo handlers
- `frontend/src/i18n/locales/{en,fr,de,es}.json` — `sync.simplefin.*`

### Flow

```
Paste setup token
        |
        v
POST claim URL  -->  access URL (encrypted)
        |
        v
GET /accounts?start-date=  -->  accounts + posted transactions
        |
        v
upsert Account + EUR snapshot + ledger rows
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Separate connector, not `BankConnectorPort` | Spring injects one bank connector. Implementing the port would replace Enable Banking. SimpleFIN has no institution search and one token covers every linked bank. | A second `@Primary` adapter |
| One request for balances and transactions | The protocol returns both. A second call only spends the bridge quota. | `balances-only=1`, then another fetch per account |
| Pending transactions omitted | Their ids change when they post, which would duplicate them | `pending=1` |
| Provider constant `SimpleFIN`, bank in `institution_name` / `institution_domain` | Sync All and account deletion match one connection through `provider`. The bank (`connections[].org_name`, `org_url`) gets its own columns, so Chase and Fidelity stay distinguishable and the card reads "Fidelity Investments · via SimpleFIN". | Stamping each account with its bank as `provider`, which hides the connection once the token is removed |
| Bank logo through an opt-in proxy | `GET /api/accounts/{id}/institution-logo` reuses the merchant-logo gates (per-IP bucket, then the member's `logoFetchEnabled`) and `MerchantLogoPort`. The domain comes from the bridge, so it is reduced to a bare public host (https, no userinfo or port, no IP literal) before it is stored, and the port only sends it as a path segment to its own fixed host. With logos off the card keeps its colour. | A bundled logo per bank, which cannot cover the banks the bridge adds; letting the browser call the icon service, which tells it which banks the member uses |
| 89-day window on every sync | The bridge rejects an inclusive 90-day span. 89 days is the longest request it accepts. A newly linked bank still shares that window with the others | Per-account `start-date`, which needs one HTTP call per account |
| Only `beta-bridge.simplefin.org`, port 443 | It is the only SimpleFIN server members use. A fixed host and port make the outbound destination a constant, so no address filtering or DNS pinning is needed | Resolving the host and refusing private ranges, which still had to defeat DNS rebinding; a configurable allowlist with nothing else to put in it |
| A 403 is `SESSION_EXPIRED` | Revoked access needs a new setup token, the same signal Fortuneo and Amex already give for an expired login | Reporting it as a plain `FAILED`, which hides that only the member can fix it |
| Bridge error text single-line and capped | It is remote text shown in a 422 body and written to logs; a newline could forge a log line | Passing it through as sent |
| An account that reports positions is created as `BROKERAGE` | The protocol has no account type, but `holdings` is structural, not a guess: a cash account answers `[]`. A `CHECKING` account never holds positions, so one synced before this existed that now reports some is upgraded to `BROKERAGE` on its next sync. Any other type was the member's choice and stays; later syncs keep it. | Name, brand or balance-sign detection (see the next row) |
| One generic `BROKERAGE` type | The French wrappers (PEA, PER...) carry French rules. US accounts (Roth IRA, HSA, 401(k), taxable) and every other country's brokers need a home without one enum value per wrapper; the bank's own account name keeps the wrapper visible. | One type per country or wrapper |
| `cost_basis` of 0 means unknown | Fidelity sends `cost_basis` 0.00 on every line, stocks included. The buy-in is `cost_basis / shares` when positive, else `purchase_price`, else none. Zero as a cost would show an infinite gain. | Trusting the field |
| A money-market sweep is cash | A symbol ending in `XX` priced at about one dollar (SPAXX, FDRXX) is added to the account's `cashBalance`, as a PEA's cash is, instead of appearing as a stock with a fabricated gain. | Importing it as a holding |
| An empty `holdings` list never wipes positions | The bridge answers `[]` for cash accounts and for a partial failure alike. A stale line is corrected by the next good sync; a wrongly erased portfolio is not. | Replacing on every sync |
| Buy-in and cash stored in EUR, price in its own currency | `account_holding.average_buy_in` and `cash_balance` are read as EUR by the valuation. `current_price` keeps `quote_currency`. `provider_value_eur` stays null for non-EUR rows so Yahoo prices the ticker. | Writing USD into the EUR columns |
| Accounts created as `CHECKING`, typed by the member | The protocol has no account type. Every guess has a known failure: card names like "Sapphire Reserve" contain no keyword, brand lists turned Discover checking and Amex savings into cards in Sure, `available-balance` arrives as `0.00` on checking accounts and with either sign on cards, and a negative balance also means an overdraft or a mortgage. Enable Banking creates every account as `CHECKING` too | Name, brand, or balance-sign detection |

## Institutions and positions

The bridge's `connections[]` names the bank behind each account (`org_id` such as `mx.chase`, `org_name`, `org_url`). The sync stores `org_name` and the host of `org_url` on the account, on creation and again on every sync, so accounts synced before this existed are backfilled. A field the bridge stops sending is kept, not cleared.

`holdings[]` (`symbol, description, shares, market_value, cost_basis, purchase_price, currency`, figures as strings) is parsed into positions by `SimplefinHoldings`, then replaced on the account before the day's snapshot is taken, so the snapshot's invested amount derives from them. Positions are written only when the account's type is an investment type, and only when something usable survives the mapping (a payload of zero-share rows changes nothing).

Known limits: a member cannot keep a SimpleFIN account that reports positions typed `CHECKING` (retype it to any other type to opt out of the upgrade); when no EUR rate is available, the positions of that account are left as they were until the next sync, rather than storing USD as EUR; a holding Yahoo cannot price is left out of the live value, so a brokerage account holding such funds shows less than the bridge's balance (the provider-valued path in `AccountService` reads the balance as EUR, so it is not enabled for USD accounts); the bridge's coverage of holdings varies by institution (only Chase and Fidelity were checked against live data); a cash line that is neither an `XX` fund nor a symbol-less $1 line is treated as a stock; a holding Yahoo cannot price (a 401(k) fund by CUSIP, say) is left out of the live value like any unpriced holding; transactions on an investment account are history only, and BUY/SELL rows are never created from them, so positions are not counted twice.

## Gotchas / Pitfalls

- The setup token is single-use. A failed claim does not store anything. A successful claim
  followed by a failed sync does: retry Sync, do not paste the same token.
- A credit card arrives as `CHECKING` with the negative balance Bridge sends. Changing its type to
  Credit card moves it under debts without changing net worth, because Picsou stores card debt as
  a negative number. The account form refuses a negative number without saying why, so the member
  types the debt without its minus sign. The amount itself is ignored: a synced account's balance
  only comes from Bridge. A bank that reported card debt as a positive number would show as a
  credit after the change; none has been seen on Bridge.
- A card paid beyond its debt arrives with a positive balance and is stored as sent. As a
  credit card it lowers total liabilities and raises net worth by that amount, and the dashboard
  lists it under debts as a negative figure. This is the AMEX sign rule too.
- Reward points and other custom currencies (a URL instead of an ISO code) are skipped, and so are
  ISO codes with no minor unit (`XAU`, `XDR`, `XXX`). The cash accounts in the same response still
  import.
- No bank logos. The Enable Banking catalog is not consulted.
- One token per member. Connecting again replaces the stored access URL.
- The access URL is absent from logs, the status payload (a masked username hint only), JSON
  serialization of the entity (`@JsonIgnore`), and the data export.
- `get_sync_status` shows a connection in `ERROR` as `FAILED` with `reauth=false`: the stored
  status does not keep the reason. The trigger summary of the sync that failed does say
  `NEEDS_REAUTH`.
- A live Bridge sync reached the ledger and failed when a posted description exceeded 255
  characters. The importer now clips that field on a character boundary. An amount that does not
  fit `numeric(20,8)`, or a posted timestamp that is not a real date, is skipped so the other
  accounts in the same response still import. An account id longer than 255 characters is hashed
  with the `sfin_` prefix kept, because account deletion recognises the connection by that prefix.

## Tests

Backend (`make test-backend`):
- `SimplefinUrlsTest` — the single host, scheme, port and token matrix: only the Bridge host on
  the default port or 443 is accepted, for the claim URL and the access URL; refusals never echo
  the token or credentials; Basic auth leaves the request URI free of userinfo; a literal `+`
  stays a plus
- `SimplefinClientTest` — one wiring test per path, through the fake transport: a refused host
  sends nothing, a claim answer for another host is refused after one request, 403 on fetch
  carries `SESSION_EXPIRED`, 402, other statuses and redirects; the body caps live in
  `BoundedUtf8BodyTest`, since `BoundedUtf8Body` is shared with the Wise connector
- `SimplefinJsonTest` — currency, balance, id, transaction and posted-date parsing; pending rows
  dropped; external ids hashed past 255 characters; Bridge error text collapsed to one line and
  cut at 300 characters without splitting a surrogate pair
- `SimplefinSyncServiceTest` — connect (unknown member never claims, a failed claim or encryption
  keeps the stored access URL, reconnect replaces in place); sync (created as `CHECKING`, a
  member-set type kept, a card in credit kept positive, soft-delete skip, currencies with no minor unit skipped, the 89-day UTC
  clamp); `resyncReporting` (`SESSION_EXPIRED` gives `NEEDS_REAUTH`, other failures `FAILED`,
  no credentials in logs); the reconnect and dedup round-trips
- `SimplefinControllerTest` — each endpoint follows the principal, validation returns 400 or 422
  without echoing the token, connect and sync share the per-IP limit
- `SimplefinStatusWriterTest` — the `ERROR` status survives the caller's rollback (H2 slice)
- `SimplefinConnectionTest` — JSON serialization never includes the access URL
- `BankTransactionImportServiceTest` — clipping on a character boundary, long ids hashed with
  `fp:`, oversized amounts skipped, repeated ids stored once, the fingerprint rounding to 8
  decimals
- `SourceSyncResultTest`, `SyncStatusServiceTest`, `SyncToolsTest` — the `simplefin` reauth
  mapping, its `get_sync_status` line, and its place in `trigger_bank_sync`
- `MemberSyncServiceTest` — SimpleFIN runs after IBKR in the scheduled order
- `DataExportServiceTest` — the export ZIP carries no access URL
- `AccountConnectionServiceTest` — the connection is removed only with its last account

Frontend (`make test-frontend`, `make test-e2e`):
- `SimplefinPanel.test.tsx` — token form, connect failures, connected controls, sync, disconnect
  through the confirm dialog
- `SimplefinTab.test.tsx` — smoke test
- `SyncAllModal.test.tsx`, `AddAccountModal.test.tsx` — the SimpleFIN row and the add-account entry
- `e2e/simplefin.spec.ts` — demo-mode connect and disconnect, and the add-account entry

## Links

- ADR: [SimpleFIN beside Enable Banking](../decisions/2026-10-04-simplefin-beside-enable-banking.md)
- API: [`/api/simplefin`](../../backend/docs/API.md#16b-simplefin--apisimplefin)
- Related: [Bank sync](./bank-sync.md), [MCP server](./mcp-server.md),
  [Add account modal](./add-account-modal.md),
  [Account deletion removes its connection](../decisions/2026-08-11-account-deletion-removes-its-connection.md)
