# Feature: American Express France sidecar

> Last updated: 2026-09-29

## Context

Picsou imports French American Express credit-card balances and transactions through an isolated read-only sidecar. It also surfaces the remaining statement amount, debit date, and miles earned during the current AMEX statement period when responses provide them; this is not the Flying Blue balance.

## How it works

The user selects SMS (default) or e-mail for AMEX's one-time code. The browser completes login and OTP, then captures value-free JSON response diagnostics from American Express domains while the dashboard loads. Account discovery falls back to the servicing balances API; missing enrichments remain null and never fail a sync.

The current card balance is inferred as statement balance + total debits - payments/credits. Re-verify this formula after a real payment. Posted and pending transaction lists are merged, with posted transactions taking precedence when an identifier overlaps.

`/accounts` has one account contract: `amountDue` (positive number or null), `dueDate` (ISO date or null), and `rewardPoints` (integer or null). `amountDue` comes from `remaining_statement_balance_amount`; `dueDate` comes from `GET /api/servicing/v1/financials/payments?status=scheduled` (`direct_debit_date`, falling back to `payment_due_date`), then captured date enrichment. `rewardPoints` is the `EARNED` total from `ReadLoyaltyTransactions.v3` for period index 0: miles earned during the current AMEX statement period. It is not the Flying Blue balance; that balance is unavailable from the current sidecar. AMEX returns this only in the logged-in browser; it is captured during SMS login and refreshed on each SMS login. The backend preserves the latest non-null value. Logs contain URL paths without queries, key/type shape, and numeric non-zero/zero flags only—never response values.

Backend sync persists these optional fields on `account`, exposes them through account responses (`paymentDueAmount`, `paymentDueDate`, `rewardPoints`), and produces a one-off negative budget occurrence when amount is positive and due date is in range. Missing values preserve the latest known enrichment.

### History recovery

`POST /api/amex/history-recovery` (Sync page → AMEX tab, secondary action) reuses the current session — no new credential prompt — and asks the sidecar for up to 1,000 posted and pending transactions. The result is merged into existing transactions without deleting manual ones; transactions without an AMEX id get a deterministic `amex_tx_` identity, so re-running recovery is idempotent. This is an upstream request bound, not a guarantee: AMEX only returns what its transaction API exposes, and the UI says older transactions may be unavailable.

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
|| Connect runs the provider-maximum history import once | A fresh connection is already the heavy operation (browser login + OTP); routine syncs stay cheap and the daily scheduler keeps the session warm | Fetching full history on **every** sync |
|| Pinned Camoufox 152.0.4-beta.30 | 156.0.1-beta.33 breaks login (UnknownProperty); verified build works | Pin deliberately after real login test |

## Gotchas / Pitfalls

- Regular sync replaces the trailing 90-day window. The sidecar may return duplicate rows; backend deduplication uses the prepared transaction external id before persistence, and history recovery uses that same id when matching existing rows.
- Pending transaction retrieval is best effort; a failure does not invalidate a posted snapshot.
- The balance formula is inferred and must be re-checked after a payment/credit appears.
- Existing AMEX sessions may not contain captured enrichment; a new login is needed to observe dashboard responses.
- Debt is shown signed (negative) like every other liability in Picsou; the amount to pay is shown as a positive amount to settle.

## Tests

- `AmexSyncServiceTest`, `AmexAdapterTest`, `AmexControllerTest`
- `RecurringSeriesServiceTest`
- `TransactionsList.test.tsx` (header actions), `money-axis.test.tsx` (chart axis masking)
- Sidecar `python /app/main.py` self-check inside the built container

## Links

- [Budget & Cashflow](./budget.md)
- [Encryption at rest](./encryption-at-rest.md)
