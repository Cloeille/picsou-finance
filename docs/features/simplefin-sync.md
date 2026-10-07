# Feature: SimpleFIN sync

> Last updated: 2026-10-06

## Context

Enable Banking covers European open-banking institutions. SimpleFIN is a separate, token-based protocol used mostly for US banks through [SimpleFIN Bridge](https://bridge.simplefin.org/simplefin/create). The member links banks on that server, pastes one setup token into Picsou, and Picsou imports balances and posted transactions. Enable Banking is unchanged.

## How it works

A setup token is a Base64-encoded claim URL. Picsou POSTs it once and receives an access URL with HTTP Basic credentials embedded (`https://user:pass@host/simplefin`). That URL is encrypted with `CryptoEncryption` and stored on `simplefin_connection`, one row per member. The setup token is not kept.

Sync calls `GET /accounts?version=2&start-date=` with the credentials in an `Authorization` header, not in the request URI. Each account becomes a Picsou account with provider `SimpleFIN` and external id `sfin_{connId}_{accountId}`. The institution's `org_name` is prefixed onto the account name (`Chase — Checking`). The connection `name` often includes the member and is only used when `org_name` is absent. A new account is created as `CHECKING`, like Enable Banking's. The member sets savings, credit card, or any other type in the account form; a resync only updates the balance, currency, and sync time, so that choice is kept. The reported balance is stored as sent and snapshotted in EUR through the existing FX path. Posted transactions from the same response go through `BankTransactionImportService.importProvided`, which dedups on the provider id and cuts a description longer than 255 characters to fit the ledger column. The download always asks for the shared history window, clamped to 89 days on the UTC date because the bridge rejects an inclusive 90-day span and `start-date` is sent as midnight UTC. Every account shares that one response; rows already stored are dropped.

The claim URL and the access URL must be `https://beta-bridge.simplefin.org`. Any other scheme or host is refused before a request is sent, so a pasted token cannot make Picsou call an internal address, and a claim response cannot redirect the daily credentialed request elsewhere. Redirects are refused, because the next URL would be chosen by the remote server. A literal `+` in the access username or password stays a plus. Response bodies are capped while they are read (8 KiB for a claim, 2 MiB for accounts).

Connect stores the access URL even when the first download fails, because the setup token cannot be claimed twice. A later Sync retries it. HTTP 403 on sync means the access was revoked. Disconnect deletes the connection row and leaves the accounts. Deleting the last SimpleFIN account also deletes the connection, same rule as the other connectors.

Daily refresh is the `simplefin` source in `MemberSyncService`, after IBKR, so the 08:00 job and the MCP full sync both run `SimplefinSyncService.resyncReporting`. Manual sync is rate-limited with connect, 6 requests per minute per IP. The table is created by `V109__simplefin_connection.sql`, which also widens `account.external_account_id` and `transaction.external_transaction_id` to 255 characters.

### Key files

- `backend/src/main/java/com/picsou/adapter/SimplefinClient.java` — claim, accounts fetch, URL guards
- `backend/src/main/java/com/picsou/service/SimplefinSyncService.java` — connect, sync, account upsert
- `backend/src/main/java/com/picsou/controller/SimplefinController.java` — `/api/simplefin`
- `frontend/src/components/sync/SimplefinPanel.tsx` — token form, sync, disconnect
- `docs/decisions/2026-10-04-simplefin-beside-enable-banking.md` — why this is not a `BankConnectorPort`

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
| Provider constant `SimpleFIN` | Sync All and account deletion match one connection. The bank name lives in the account name. | Stamping each account with its bank as `provider`, which hides the connection once the token is removed |
| 89-day window on every sync | The bridge rejects an inclusive 90-day span and caps it. 89 days is the longest request it accepts. A newly linked bank still shares that window with the others | Per-account `start-date`, which needs one HTTP call per account |
| Only `beta-bridge.simplefin.org` | It is the only SimpleFIN server members use. A fixed host makes the outbound destination a constant, so no address filtering or DNS pinning is needed | Resolving the host and refusing private ranges, which still had to defeat DNS rebinding; a configurable allowlist with nothing else to put in it |
| Accounts created as `CHECKING`, typed by the member | The protocol has no account type. Every guess has a known failure: card names like "Sapphire Reserve" contain no keyword, brand lists turned Discover checking and Amex savings into cards in Sure, `available-balance` arrives as `0.00` on checking accounts and with either sign on cards, and a negative balance also means an overdraft or a mortgage. Enable Banking creates every account as `CHECKING` too | Name, brand, or balance-sign detection |

## Gotchas / Pitfalls

- The setup token is single-use. A failed claim does not store anything. A successful claim followed by a failed sync does: retry Sync, do not paste the same token.
- A credit card arrives as `CHECKING` with the negative balance Bridge sends. Changing its type to Credit card moves it under debts without changing net worth, because Picsou stores card debt as a negative number. The account form asks for the amount owed and refuses a negative number without saying why, so the member types the debt without its minus sign; the next sync writes Bridge's signed balance back. A bank that reported card debt as a positive number would show as a credit after the change; none has been seen on Bridge.
- Reward points and other custom currencies (a URL instead of an ISO code) are skipped. The cash accounts in the same response still import.
- No bank logos. The Enable Banking catalog is not consulted.
- One token per member. Connecting again replaces the stored access URL.
- The access URL is absent from logs, the status payload (a masked username hint only), and the data export.
- A live Bridge sync reached the ledger and failed when a posted description exceeded 255 characters. The importer now clips that field on a character boundary. An amount that does not fit `numeric(20,8)`, or a posted timestamp that is not a real date, is skipped so the other accounts in the same response still import. An account id longer than 255 characters is hashed with the `sfin_` prefix kept, because account deletion recognises the connection by that prefix.

## Tests

- `SimplefinClientTest` — a non-https claim, a token for another host, and a claim that hands back another host are refused without storing anything; a literal `+` kept in the access password, Basic auth without userinfo in the URI, pending rows dropped, an unusable posted date dropped, a hashed account id keeping `sfin_`, partial errors, 402, 403 and redirects
- `SimplefinSyncServiceTest` — upsert as `CHECKING`, a resync keeping a member-set `CREDIT_CARD`, the 89-day clamp on the UTC date, the daily job reporting instead of throwing, non-ISO skip, balance that does not fit the ledger, soft-delete skip, error status, name cut on a character boundary
- `MemberSyncServiceTest` — SimpleFIN runs after IBKR in the scheduled order
- `BankTransactionImportServiceTest` — a description longer than the ledger column is stored clipped, a repeated id and an oversized amount are dropped, a second import of the same id inserts nothing
- `AccountConnectionServiceTest` — the connection is removed only with its last account
- `SimplefinTab.test.tsx` — connect-then-sync, connected controls, sync error
