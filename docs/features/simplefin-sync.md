# Feature: SimpleFIN sync

> Last updated: 2026-11-07

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

A new account is created as `CHECKING`, like Enable Banking's, unless the bridge reports at least
one non-cash position for it: then it is created as `BROKERAGE` (see "Institutions and
positions"). The member sets savings, credit card, or any other type in the account form; a
resync never changes the type, so that choice is kept. The reported balance is stored as sent and
snapshotted in EUR through the existing FX path.

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
- `backend/src/main/java/com/picsou/adapter/SimplefinJson.java` — account-set parsing, pending rows dropped, error text capped, holdings rows and their completeness
- `backend/src/main/java/com/picsou/service/SimplefinHoldings.java` — positions, sweep lines and the completeness flag
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
| A new account that reports a non-cash position is created as `BROKERAGE` | The protocol has no account type, but `holdings` is structural, not a guess: a cash account answers `[]`. A cash-management account whose only line is a sweep fund has no position, so it stays `CHECKING`. The type is set at creation only: an existing account keeps whatever type it has, because a sync that retyped it would override the member's choice (and `CHECKING` is a choice too: it is what every account was before this existed). | Name, brand or balance-sign detection (see the last row); upgrading a `CHECKING` account when positions appear |
| One generic `BROKERAGE` type | The French wrappers (PEA, PER...) carry French rules. US accounts (Roth IRA, HSA, 401(k), taxable) and every other country's brokers need a home without one enum value per wrapper; the bank's own account name keeps the wrapper visible. | One type per country or wrapper |
| `cost_basis` of 0 means unknown | Fidelity sends `cost_basis` 0.00 on every line, stocks included. The buy-in is `cost_basis / shares` when positive, else `purchase_price`, else none. Zero as a cost would show an infinite gain. | Trusting the field |
| A money-market sweep is cash | A symbol ending in `XX`, or a symbol-less line, priced at about one dollar (SPAXX, FDRXX) goes to the account's `cashBalance`, as a PEA's cash is, instead of appearing as a stock with a fabricated gain. | Importing it as a holding |
| Cash the bridge leaves out of `holdings` | The balance can exceed the positions by cash that is not listed. The sync works it out in EUR, at its own rates, so another currency in the balance, a position or the sweep cannot drop it. When every position has a market value, cash is `max(0, balance - positions)` in EUR, which already contains the sweep whatever its currency. When a position has none, cash is the sweep alone and the gap is logged: the remainder may be that position's own worth, which the price feed values separately. | Cash from the sweep alone, which showed a balance of 1100 with positions of 1000 as 1000; putting the remainder into cash when a position has no value (counted twice once the feed prices it); working the residual out per currency in the mapping, which dropped it as soon as one figure was in another currency |
| An empty `holdings` list never wipes positions | The bridge answers `[]` for cash accounts and for a partial failure alike. A stale line is corrected by the next good sync; a wrongly erased portfolio is not. | Replacing on every sync |
| A list read in part never replaces the stored one | The parser flags a list as incomplete when it drops a row (no share count, no symbol and no description) or the bridge sends more than 500. The mapping flags it when a line that holds something cannot be stored. Either way holdings, cash and type stay as they were, and the reason is logged. | Importing the readable rows and deleting the rest |
| One rule for a missing EUR rate | If any currency the replacement needs (a position, the sweep, or the account's own currency when cash is the balance beyond the positions) has no EUR rate, or a converted figure does not fit `numeric(20,8)`, holdings and cash are left exactly as they were. A figure is never stored unconverted. | Converting what has a rate and skipping the rest |
| Buy-in, value and cash stored in EUR, price in its own currency | `account_holding.average_buy_in`, `provider_value_eur` and `cash_balance` are read as EUR by the valuation. `current_price` keeps `quote_currency`. `provider_value_eur` is the position's `market_value` converted at the sync's rate, dated that day in `provider_price_date`. | Writing USD into the EUR columns |
| Positions that Yahoo cannot price use `provider_value_eur` | A 401(k) fund known only by CUSIP has no quote. `AccountService.valuation` already falls back per line to `provider_value_eur`, and it counts the cost basis of that line, so the account reads the bridge's figure instead of a false loss. SimpleFIN is **not** in `AccountService.PROVIDER_VALUED`: that path reads the account balance as EUR, which is wrong for a USD account. | Adding SimpleFIN to `PROVIDER_VALUED` |
| Positions are updated in place by ticker | A buy-in the member entered by hand is kept when the bridge sends none (Fidelity sends `cost_basis` 0.00 on every line). Tickers no longer reported are deleted; new ones are inserted. | Delete-and-recreate on every sync, which erased the member's buy-ins |
| Lots of one ticker are merged by market value | Quantities add; the buy-in is weighted by quantity when both lots know it; the market value is the sum when both are known, and `current_price` is that value divided by the quantity. | Keeping the first lot's price |
| Accounts without a position created as `CHECKING`, typed by the member | The protocol has no account type. Every guess has a known failure: card names like "Sapphire Reserve" contain no keyword, brand lists turned Discover checking and Amex savings into cards in Sure, `available-balance` arrives as `0.00` on checking accounts and with either sign on cards, and a negative balance also means an overdraft or a mortgage. Enable Banking creates every account as `CHECKING` too | Name, brand, or balance-sign detection |

## Institutions and positions

The bridge's `connections[]` names the bank behind each account (`org_id` such as `mx.chase`, `org_name`, `org_url`). The sync stores `org_name` and the host of `org_url` on the account, on creation and again on every sync, so accounts synced before this existed are backfilled. A field the bridge stops sending is kept, not cleared.

`holdings[]` (`symbol, description, shares, market_value, cost_basis, purchase_price, currency`, figures as strings) is parsed by `SimplefinJson` and mapped to positions by `SimplefinHoldings`, once per account and per sync. The mapping decides the type of a *new* account (`BROKERAGE` when it has a non-cash position) and, for an account whose type `isInvestment()`, the positions are then written before the day's snapshot is taken, so the snapshot's invested amount derives from them.

A sync writes the positions only when all of these hold; otherwise holdings, cash and type stay as they were and the reason is logged:

- the list is not empty, and something usable survives the mapping (a payload of zero-share rows changes nothing);
- the list is complete: no row dropped by the parser, at most 500 rows, and no line the mapping cannot store. A zero-share row is "nothing held" and does not count against it. A short position (negative shares), lots of one ticker in two currencies, a non-cash line with no symbol, a currency that is not ISO 4217, and any figure (read or derived: price, buy-in, merged quantity, converted EUR value, cash) that does not fit `numeric(20,8)` do;
- every currency involved has a EUR rate.

Known limits:

- The bridge's coverage of holdings varies by institution (only Chase and Fidelity were checked against live data).
- A cash line that is neither an `XX` fund nor a symbol-less $1 line is treated as a stock.
- A position with no market value: cash the bridge left out of the list is not counted, because the remainder cannot be split between that position and cash. Only the sweep is cash until the bridge sends a value.
- With positions or a sweep in another currency, the cash beyond the positions uses Picsou's rates, not the bridge's, so it can differ from the bank's figure by the FX spread.
- Short positions are not modelled: a portfolio that has one is not imported until it is closed.
- Transactions on an investment account are history only, and BUY/SELL rows are never created from them, so positions are not counted twice.
- Rows on an investment account are imported budget-neutral (they stay out of budgets and cashflow), on every import path. Rows imported while the account was still `CHECKING` keep their budget status after a retype: they are not backfilled.
- A bridge that answers an empty list for an account that had positions leaves them in place, so a portfolio the member closed stays until they delete it by hand.
- A line Yahoo cannot price is valued at the bridge's `market_value` as of the last sync, converted at that day's rate, until the next sync. A line the bridge sent without a `market_value` that Yahoo cannot price either is valued at nothing; while other lines have no buy-in, the invested amount is the whole account balance, so such an account can read below its cost.

## Gotchas / Pitfalls

- A SimpleFIN account synced before positions were imported is typed `CHECKING`, and a sync never
  retypes an existing account. A member who holds positions there retypes it to Brokerage once in
  the account form; the next sync then imports the positions. Until then its positions are not
  imported and its balance is shown as the bridge reports it.
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
  cut at 300 characters without splitting a surrogate pair; holdings: a dropped row or more than
  500 rows makes the list incomplete, and a symbol or description is never cut inside a surrogate pair
- `SimplefinHoldingsTest` — buy-in fallbacks, sweep detection, lots merged by market value, the
  incomplete cases (short, mixed currencies, no symbol, overflow of a read or derived figure),
  zero-share rows staying complete, sweep lines summed per currency and never mixed with positions
- `SimplefinSyncServiceTest` — connect (unknown member never claims, a failed claim or encryption
  keeps the stored access URL, reconnect replaces in place); sync (created as `CHECKING`, a
  member-set type kept, a card in credit kept positive, soft-delete skip, currencies with no minor unit skipped, the 89-day UTC
  clamp); positions (a new account with a position is `BROKERAGE`, a sweep-only one `CHECKING`, an
  existing account is never retyped, the value stored in EUR, one rule for a missing rate or an
  overflowing converted figure, an incomplete list keeping the stored positions and cash, cash
  as the EUR balance beyond the positions (sweep included, kept across currencies, never negative,
  nothing written when the account currency has no rate) or as the sweep alone when a position has
  no market value, a hand-entered buy-in surviving a sync, dropped tickers deleted before the others are written); `resyncReporting` (`SESSION_EXPIRED` gives `NEEDS_REAUTH`, other failures `FAILED`,
  no credentials in logs); the reconnect and dedup round-trips
- `SimplefinControllerTest` — each endpoint follows the principal, validation returns 400 or 422
  without echoing the token, connect and sync share the per-IP limit
- `SimplefinStatusWriterTest` — the `ERROR` status survives the caller's rollback (H2 slice)
- `SimplefinConnectionTest` — JSON serialization never includes the access URL
- `AccountServiceTest` — a SimpleFIN position the price feed cannot resolve is valued at its
  stored EUR figure, with the cost baseline covering the same lines (no false loss)
- `BankTransactionImportServiceTest` — rows on an investment account budget-neutral on both
  import paths; clipping on a character boundary, long ids hashed with
  `fp:`, oversized amounts skipped, repeated ids stored once, the fingerprint rounding to 8
  decimals
- `SourceSyncResultTest`, `SyncStatusServiceTest`, `SyncToolsTest` — the `simplefin` reauth
  mapping, its `get_sync_status` line, and its place in `trigger_bank_sync`
- `MemberSyncServiceTest` — SimpleFIN runs after IBKR in the scheduled order
- `DataExportServiceTest` — the export ZIP carries no access URL
- `AccountConnectionServiceTest` — the connection is removed only with its last account

Frontend (`make test-frontend`, `make test-e2e`):
- `AccountDetailPage.duplicateSections.test.tsx` — upcoming dividends mount once on a brokerage account
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
