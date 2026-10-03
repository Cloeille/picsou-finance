# Feature: American Express France sidecar

> Last updated: 2026-10-03

## Context

Picsou imports French American Express credit-card balances and transactions through an isolated read-only sidecar. It also surfaces the remaining statement amount, debit date, and miles earned during the current AMEX statement period when responses provide them; this is not the Flying Blue balance.

## How it works

The user selects SMS (default) or e-mail for AMEX's one-time code. The browser completes login and OTP, then captures value-free JSON response diagnostics from American Express domains while the dashboard loads. Account discovery falls back to the servicing balances API; missing enrichments remain null and never fail a sync.

The current card balance is inferred as statement balance + total debits - payments/credits. Re-verify this formula after a real payment. Posted and pending transaction lists are merged, with posted transactions taking precedence when an identifier overlaps.

`/accounts` has one account contract: `amountDue` (positive number or null), `dueDate` (ISO date or null), and `rewardPoints` (integer or null). `amountDue` comes from `remaining_statement_balance_amount`; `dueDate` comes from `GET /api/servicing/v1/financials/payments?status=scheduled` (`direct_debit_date`, falling back to `payment_due_date`), then captured date enrichment. `rewardPoints` is the `EARNED` total from `ReadLoyaltyTransactions.v3` for period index 0: miles earned during the current AMEX statement period. It is not the Flying Blue balance; that balance is unavailable from the current sidecar. AMEX returns this only in the logged-in browser; it is captured during SMS login and refreshed on each SMS login. The backend preserves the latest non-null value. Logs contain URL paths without queries, key/type shape, and numeric non-zero/zero flags only—never response values.

Backend sync persists these optional fields on `account`, exposes them through account responses (`paymentDueAmount`, `paymentDueDate`, `rewardPoints`), and produces a one-off negative budget occurrence when amount is positive and due date is in range. Missing values preserve the latest known enrichment.

### History recovery

`POST /api/amex/history-recovery` (Sync page → AMEX tab, secondary action) reuses the current session — no new credential prompt — and asks the sidecar for up to 1,000 posted and pending transactions. The result is merged into existing transactions without deleting manual ones; transactions without an AMEX id get a deterministic `amex_tx_` (posted) or `amex_txp_` (pending) identity, so re-running recovery is idempotent. This is an upstream request bound, not a guarantee: AMEX only returns what its transaction API exposes, and the UI says older transactions may be unavailable.

**Connect imports full history automatically.** The first sync right after login/OTP (and after a reconnect) already runs the provider-maximum history request — the same one the recovery action triggers — so a fresh connection backfills months of older transactions without pressing anything. Routine syncs (manual Sync button, daily scheduler) then keep only the trailing 90-day window refreshed, and the recovery action remains available for an on-demand full refresh.

### Account detail UI

A `CREDIT_CARD` account renders one summary card: name + type badge, then a responsive row (1 → 2 → 4 columns) with current debt, amount to pay, direct debit date and miles earned this cycle (not the Flying Blue balance). Each optional metric is omitted when null. Amounts go through `CurrencyDisplay`/`useMoney` (privacy mode applies), dates through `formatLocalDate`, miles through `Intl.NumberFormat`. Transactions render once through `TransactionsList`, whose card header carries the page's add/import actions. Credit cards are grouped under **Debts** on the accounts list.

Demo mode ships a fictional AMEX card (account 12, with history and transactions) so the credit-card UI can be reviewed without a real login.

### Key files

- `services/amex-auth/main.py` — login, OTP, API capture and accounts contract
- `backend/src/main/java/com/picsou/service/AmexSyncService.java` — account, transaction and history-recovery persistence
- `backend/src/main/java/com/picsou/service/budget/RecurringSeriesService.java` — budget calendar occurrence
- `backend/src/main/resources/db/migration/V102__amex_payment_details.sql` — nullable account fields
- `frontend/src/pages/accounts/AccountDetailPage.tsx` and `frontend/src/pages/budget/RecurringTab.tsx` — display
- `frontend/src/components/sync/AmexPanel.tsx` — connection, sync and history recovery
- `frontend/src/demo/data/accounts.ts` — demo AMEX card

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Missing enrichment remains null and latest non-null persisted value wins | Upstream discovery is not guaranteed on every response | Clearing a known value on a sparse sync |
| Account due amount is stored as plaintext NUMERIC(20,8), date and points are ordinary account data | These values are not credentials | Encrypting non-secret amounts |
| Connect runs the provider-maximum history import once | A fresh connection is already the heavy operation (browser login + OTP); routine syncs stay cheap and the daily scheduler keeps the session warm | Fetching full history on **every** sync |
| Pinned Camoufox 152.0.4-beta.30 | 156.0.1-beta.33 breaks login (UnknownProperty); verified build works | Pin deliberately after real login test |
| Sidecar requires `X-Picsou-Sidecar-Key` (= `APP_SIDECAR_API_KEY`) on every path except `/health`, refuses to start without it | Only the backend may drive bank logins; a 401 `WWW-Authenticate: Picsou-Sidecar-Key` is mapped to a key-mismatch error by `SidecarWebClientFactory` | Relying on network isolation alone (other sidecars: #169) |

## Gotchas / Pitfalls

- A regular sync only gets the latest 100 posted transactions, a page cut on the posting date while rows carry the charge date, so a missing posted row proves nothing: a routine sync upserts posted rows by external id inside the 90-day window and never deletes one. Only rows stored as pending can become obsolete. The sidecar labels each transaction with the feed it came from (`status`: `posted` or `pending`) and sends `pendingComplete: false` when the pending call failed (429, 5xx, rejected payload). When it is true, a stored pending row the response no longer returns (settled under its posted identity, or a cancelled hold) is deleted; when it is false, stored pending rows are kept. An empty transaction list touches nothing.
- Pending is persisted in the external id prefix rather than a column: `amex_txp_` for a pending charge, `amex_tx_` for a posted one. A charge that settles changes identity anyway (the posted form is a new row), so the prefix carries the only state the reconciliation needs without widening the shared `transaction` table for one provider.
- Without an AMEX id, a transaction's external id is the prefix + the first 128 bits of SHA-256 over `date|label|amount|occurrence` (amount as `stripTrailingZeros().toPlainString()`), numbered per repeat in the sidecar's order so two identical purchases on the same day stay two rows. Routine sync and history recovery derive the same ids.
- A settling charge keeps the user's edits. Before an obsolete pending row that carries a category or a recurring series is deleted, it is paired with a posted row of the account, either new in this sync or stored by an earlier one (the pending outlives its posted row when the pending feed failed in between, or when a history import stored the posted row): first by identical hash suffix (same date, label, amount and occurrence), otherwise by exact amount within 7 days, closest date first, each row paired at most once, ties broken by date then external id, so the result does not depend on row order. In the fallback, a posted row that already has a category (or, for a pending with only a series, a series) is not a candidate: it is another pending's settlement or the user's own choice. The category (with its manual flag) and the recurring series move onto the posted row only where the posted row has none. Seven days covers AMEX's usual settlement delay plus a weekend and bank holidays; the exact-amount rule keeps it from pairing unrelated charges, so a settlement that changes the amount (a tip) is not paired. An unpaired pending row is deleted as before, including when AMEX drops the pending one sync before it publishes the posted row: no partner exists yet, so those edits are lost.
- A history import (connect, reconnect, recovery) also purges and pairs stored pending rows the same way when its pending feed answered; otherwise it leaves them for the next routine sync.
- Rows stored by the first pushed version of this PR (`amex_tx_` + a base-36 32-bit `Objects.hash`, no pending state) would never match a current id: posted rows would be duplicated and old pending rows never purged. Each sync with a non-empty transaction list re-keys them in code before reconciling (no Flyway migration, since the identity is computed in Java): any non-manual id starting with `amex_tx` that does not match `^amex_txp?_[0-9a-f]{32}$` gets the current identity from its stored date, description and amount, repeats numbered in date then row order. The old format cannot tell a pending row from a posted one, so a legacy row is re-keyed as posted unless the response reports it as pending, or it is dated within the last 7 days and the response does not report it as posted: then it is re-keyed as pending and follows the pending lifecycle above (purged by the next answered pending feed, its edits carried to the posted row it became). A real posted charge that recent is on the latest 100-posting page unless over 100 charges posted since. A legacy row whose new id is already held is merged into the holder, which keeps its own edits and gains the missing ones. Re-keying is idempotent and logs once per account.
- A credit card is a liability everywhere: `AccountType.isLiability()` (the accounts page's Debts group) keeps it out of the dashboard's assets, allocation donut and wealth pyramid, and adds its debt (the balance negated) to the dashboard's liabilities.
- Pending transaction retrieval is best effort; a failure (HTTP error, rejected payload, timeout or connection error) does not invalidate a posted snapshot, it only sets `pendingComplete: false` so the backend keeps the pending rows it already has.
- A manual `CREDIT_CARD` stores its debt negative like the synced card: the account form asks for the amount owed (positive, like a loan's remaining capital) and `AccountService` stores `-abs(amount)` on create, update and the monthly history modal (which also shows the amount owed), and records the initial snapshot of a non-zero debt.
- History, P&L and the positions page use the same liability notion as the dashboard (`AccountType.isLiability()`, `LIABILITY_ACCOUNT_TYPES` in `frontend/src/lib/constants.ts`): a card counts in net worth but never in invested or P&L. A loan's stored balance is positive and negated; a card's is already signed.
- The balance formula is inferred and must be re-checked after a payment/credit appears.
- Existing AMEX sessions may not contain captured enrichment; a new login is needed to observe dashboard responses.
- Debt is shown signed (negative) like every other liability in Picsou; the amount to pay is shown as a positive amount to settle.

## Tests

- `AmexSyncServiceTest`, `AmexAdapterTest`, `AmexControllerTest`
- `RecurringSeriesServiceTest`, `HistoryServiceTest`, `AccountServiceTest` (card sign)
- `TransactionsList.test.tsx` (header actions), `AccountForm.test.tsx` and `MonthEndBalanceModal.test.tsx` (card amount owed), `features/accounts/hooks.test.tsx` (debts out of the cash line), `money-axis.test.tsx` (chart axis masking)
- Sidecar `python /app/main.py` self-check inside the built container

## Links

- [Budget & Cashflow](./budget.md)
- [Encryption at rest](./encryption-at-rest.md)
