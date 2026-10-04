# Feature: SimpleFIN sync

> Last updated: 2026-10-04

## Context

Enable Banking covers European open-banking institutions. SimpleFIN is a separate, token-based protocol used mostly for US banks through [SimpleFIN Bridge](https://bridge.simplefin.org/simplefin/create). The member links banks on that server, pastes one setup token into Picsou, and Picsou imports balances and posted transactions. Enable Banking is unchanged.

## How it works

A setup token is a Base64-encoded claim URL. Picsou POSTs it once and receives an access URL with HTTP Basic credentials embedded (`https://user:pass@host/simplefin`). That URL is encrypted with `CryptoEncryption` and stored on `simplefin_connection`, one row per member. The setup token is not kept.

Sync calls `GET /accounts?version=2&start-date=` with the credentials in an `Authorization` header, not in the request URI. Each account becomes a Picsou account with provider `SimpleFIN` and external id `sfin_{connId}_{accountId}`. The institution's `org_name` is prefixed onto the account name (`Chase — Checking`). The connection `name` often includes the member and is only used when `org_name` is absent. A name containing "saving" or "épargne" is stored as `SAVINGS`; everything else is `CHECKING`. The reported balance is snapshotted in EUR through the existing FX path. Posted transactions from the same response go through `BankTransactionImportService.importProvided`, which dedups on the provider id and cuts a description longer than 255 characters to fit the ledger column. The download always asks for the shared history window, clamped to 89 days because the bridge rejects an inclusive 90-day span. Every account shares that one response; rows already stored are dropped.

The claim URL and the access URL must be https. Picsou fetches the host the member pasted and does not inspect whether that host is private. Redirects are refused, because the next URL would be chosen by the remote server. A literal `+` in the access username or password stays a plus. Response bodies are capped while they are read (8 KiB for a claim, 2 MiB for accounts).

Connect stores the access URL even when the first download fails, because the setup token cannot be claimed twice. A later Sync retries it. HTTP 403 on sync means the access was revoked. Disconnect deletes the connection row and leaves the accounts. Deleting the last SimpleFIN account also deletes the connection, same rule as the other connectors.

Daily refresh is `SimplefinSyncService.resyncIfConnected` inside the 08:00 bank job. Manual sync is rate-limited with connect, 6 requests per minute per IP.

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

## Gotchas / Pitfalls

- The setup token is single-use. A failed claim does not store anything. A successful claim followed by a failed sync does: retry Sync, do not paste the same token.
- Credit cards are stored at the balance the server reports. SimpleFIN does not mark an account as a liability, so a positive card balance is not turned into a debt.
- Reward points and other custom currencies (a URL instead of an ISO code) are skipped. The cash accounts in the same response still import.
- No bank logos. The Enable Banking catalog is not consulted.
- One token per member. Connecting again replaces the stored access URL.
- The access URL is absent from logs, the status payload (a masked username hint only), and the data export.
- A live Bridge sync reached the ledger and failed when a posted description exceeded 255 characters. The importer now clips that field on a character boundary. An amount that does not fit `numeric(20,8)`, or a posted timestamp that is not a real date, is skipped so the other accounts in the same response still import. An account id longer than 255 characters is hashed with the `sfin_` prefix kept, because account deletion recognises the connection by that prefix.

## Tests

- `SimplefinClientTest` — a non-https claim is refused, a literal `+` kept in the access password, Basic auth without userinfo in the URI, pending rows dropped, an unusable posted date dropped, a hashed account id keeping `sfin_`, partial errors, 402, 403 and redirects
- `SimplefinSyncServiceTest` — upsert, savings vs checking, non-ISO skip, balance that does not fit the ledger, soft-delete skip, error status, name cut on a character boundary
- `BankTransactionImportServiceTest` — a description longer than the ledger column is stored clipped, a repeated id and an oversized amount are dropped, a second import of the same id inserts nothing
- `AccountConnectionServiceTest` — the connection is removed only with its last account
- `SimplefinTab.test.tsx` — connect-then-sync, connected controls, sync error
