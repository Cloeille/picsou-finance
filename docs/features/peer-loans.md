# Feature: Peer loans (receivables between individuals)

> Last updated: 2026-11-05
> Status: ✅ v1 implemented. See
> [the ADR](../decisions/2026-11-05-peer-loans-receivable.md).

## Context

Money lent to family or friends is not a bank loan: the installments are irregular, the
rate is usually zero, there is no contract, no provider to sync from, and the borrower may
never repay. Picsou's `LOAN` account models the opposite direction — a liability with a
fixed amortization schedule — so these loans had no honest place in the app.

A peer loan is tracked as a **receivable**: an asset whose value is the nominal capital
still owed, reduced by real repayments and, at closure, by a dated write-off. The app also
keeps a factual history per borrower, so a second loan can be decided on what actually
happened rather than on a guessed risk score.

## How it works

### Account type

A peer loan is an `Account` of type `PEER_LOAN`. The account is always `isManual = true`
(no connector) and carries the receivable into net worth, snapshots, history, the accounts
list, visibility and exports.

`PEER_LOAN` stays **positive** in `AccountService.signedLiveBalanceEur` and is **not** part
of `AccountType.isLiability()`. `isLedger()` returns false for it, because its balance
derives from the principal and the repayment ledger, not from transactions — a manual
transaction must never rewrite it. A symmetric `PEER_DEBT` (money borrowed from a person)
is a follow-up and would be the only peer type negated in the balance summation.

The account is created by the generic account form; the terms are entered on the detail
page, the way a `SCPI` position is.

### Data model

```sql
-- V122: ALTER TYPE account_type ADD VALUE 'PEER_LOAN' BEFORE 'OTHER';
-- V123: tables below.

peer_counterparty (id, member_id, name, note)
    UNIQUE (member_id, lower(name))

peer_loan_terms (
    id, account_id UNIQUE, counterparty_id,
    principal, lent_at, expected_back, annual_rate,
    planned_monthly_amount, planned_months,   -- forecast only, both or neither
    linked_account_id, note, closed_at
)

peer_loan_repayment (
    id, account_id, paid_on, amount,
    kind,                                     -- PRINCIPAL | INTEREST | WRITE_OFF
    note
)
```

Only facts are stored. Remaining capital, accrued interest, delays and the borrower
history are derived on read, following
[ADR 2026-04-26](../decisions/2026-04-26-loan-amortization-on-the-fly.md).

### Derivation

`PeerLoanCalculator.compute(terms, repayments, asOf)` replays the timeline:

1. Start at `lent_at` with `outstanding = principal` and `accrued = 0`.
2. For each event in date order:
   - accrue simple interest on `outstanding` from the previous event date,
     `outstanding × annual_rate × days / 365`, `actual/365`;
   - `PRINCIPAL` reduces the capital;
   - `INTEREST` pays accrued interest; any excess is applied to capital rather than
     trapped;
   - `WRITE_OFF` reduces the capital only — it is a decision, not a payment.
3. Accrue to `asOf` and return the computed figures.

Payments are **not split implicitly**. One cash movement carries one budget category, so
a mixed payment is recorded as two rows (interest, then capital). The repayment dialog
shows the accrued interest to make that split a two-line entry rather than a guess.

Edge cases:

- a payment smaller than the accrued interest amortises nothing; the dialog shows the
  accrued figure so the user sees why;
- an overpayment is clamped to zero capital for the balance, but the amount stays recorded;
- a zero rate short-circuits the interest branch and the whole payment amortises.

### Closure

Closing writes a `WRITE_OFF` row for the residual (if any) and stamps `closed_at`:

- `AccountService.valuation()` returns zero from that date, so the daily snapshot records
  the loss on the closure date — the past is never rewritten;
- the account stays visible with a "closed" badge so the history remains readable;
- the written-off amount appears in the summary as a realised loss, outside the monthly
  budget (there is no cash movement and it is not consumption);
- unpaid accrued interest is dropped: it was never received, so it was never counted.

Deleting the closing write-off reopens the loan automatically: the marker is cleared when
the recomputed capital is positive again. No separate "reopen" action can drift from the
ledger.

### Interest and cashflow

| Movement | `kind` | Budget category | Effect |
|---|---|---|---|
| Money lent (cash out) | — | `TRANSFER` | none |
| Capital repaid (cash in) | `PRINCIPAL` | `TRANSFER` | none |
| Interest received (cash in) | `INTEREST` | `INCOME` | counts as income |
| Write-off | `WRITE_OFF` | none | net worth only |

`CategoryKind.TRANSFER` is already excluded from cashflow, and an **uncategorised**
transaction counts as an expense — which is why the loan detail shows a hint to categorise
the matching bank transfer when no linked account is set.

### Cash-flow neutrality (v1: manual)

The receivable only balances the cash outflow if the cash movement exists exactly once.
v1 stores the optional `linked_account_id` and displays the hint; it does not create the
cash row. Automating it is deferred: on a synced account the provider already imported the
movement, and writing a second row would double it in both the cashflow and the allocation
view. The v2 rule is: write the cash leg only when the linked account is manual, never when
it is synced.

### Forecast (editable, non-binding)

`planned_monthly_amount` / `planned_months` are a simulation, editable at any time (the
terms dialog is also the plan editor). They never generate repayments, never mark anything
as paid and never drive the balance.

`POST /api/peer-loans/simulate` answers both directions:

- duration → monthly payment: `M = P·i / (1 − (1+i)^−n)`, `i = rate/12`, `P/n` at zero rate;
- monthly payment → duration: `n = −ln(1 − P·i/M) / ln(1+i)`, refused when `M ≤ P·i`
  (the payment never amortises).

The maths lives in `PeerLoanCalculator` and mirrors `LoanAmortizationService`'s annuity, so
the forecast and the bank-loan schedule cannot disagree. The detail card compares the
scheduled remaining with the actual capital and reports the drift in installments. This is
information, not a verdict: no automatic "late" state, no scoring.

### Borrower history

Facts only, aggregated per `peer_counterparty`: number of loans (open / closed), total
lent, total recovered (capital + interest), total written off, total interest received,
average delay at closure against `expected_back`, current open exposure, and the list of
loans. No score, no percentage, no badge that predicts anything.

### REST endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/accounts/{id}/peer-loan` | detail: terms, summary, ledger, borrower history |
| `PUT` | `/api/accounts/{id}/peer-loan` | upsert the terms (and the forecast plan) |
| `POST` | `/api/accounts/{id}/peer-loan/repayments` | add a repayment (201) |
| `PUT` | `/api/accounts/{id}/peer-loan/repayments/{repaymentId}` | edit |
| `DELETE` | `/api/accounts/{id}/peer-loan/repayments/{repaymentId}` | delete (204) |
| `POST` | `/api/accounts/{id}/peer-loan/close?date=` | write off the residual and close |
| `POST` | `/api/peer-loans/simulate` | duration ↔ monthly payment preview |

Every account-scoped route resolves the account with `findByIdAndMemberId` before touching
a child row: the ledger is scoped through its account, never through an unvalidated id.

### Frontend

- `components/peer-loan/PeerLoanDetailSection.tsx` renders in `AccountDetailPage` where
  `LoanDetailSection` does for `LOAN`:
  - summary: lent / repaid (%) / remaining / accrued interest / written off;
  - forecast card with the scheduled-vs-actual comparison and the drift;
  - repayment ledger with the kind badge, add / edit / delete;
  - "close the loan" action with the residual amount in the confirmation;
  - the borrower history.
- `PeerLoanTermsDialog.tsx` — terms plus the simulator (a duration fills the monthly
  amount, or a monthly amount fills the duration).
- `PeerLoanRepaymentDialog.tsx` — one dated row, one kind, one budget category.
- `AccountsPage` gains a `RECEIVABLES` filter.
- i18n keys in all four locales (`fr`, `en`, `de`, `es`).

### Key files

Backend:

- `backend/.../model/AccountType.java` — `PEER_LOAN`, `isLedger()` exclusion
- `backend/.../model/PeerCounterparty.java`, `PeerLoanTerms.java`, `PeerLoanRepayment.java`,
  `PeerLoanRepaymentKind.java`
- `backend/.../repository/PeerCounterpartyRepository.java`, `PeerLoanTermsRepository.java`,
  `PeerLoanRepaymentRepository.java`
- `backend/.../service/PeerLoanCalculator.java` — pure arithmetic (remaining, interest,
  plan, simulation)
- `backend/.../service/PeerLoanService.java` — terms, ledger, closure, history
- `backend/.../controller/PeerLoanController.java`
- `backend/.../dto/PeerLoan*.java`
- `backend/.../service/AccountService.java` — `valuation()` branch
- `backend/.../service/HistoryService.java` — receivable excluded from invested/PnL
- `backend/.../service/WealthPyramidService.java` — receivable skipped
- `backend/.../model/AssetClass.java`, `WealthTier.java` — exhaustive switches
- `backend/.../db/migration/V122__account_type_peer_loan.sql`,
  `V123__peer_loans.sql`

Frontend:

- `frontend/src/types/api.ts`, `frontend/src/lib/constants.ts`
- `frontend/src/features/peer-loans/{api,hooks}.ts`
- `frontend/src/components/peer-loan/*`
- `frontend/src/pages/accounts/AccountDetailPage.tsx`,
  `frontend/src/pages/accounts/AccountsPage.tsx`
- `frontend/src/components/shared/AccountForm.tsx`
- `frontend/src/demo/index.ts` (route coverage placeholder)

### Flow

```
Create a peer loan
   │
   ├─ Account(type=PEER_LOAN, isManual=true) created
   └─ terms entered on the detail page (principal, borrower, rate, optional plan)

Add a repayment
   │
   ├─ peer_loan_repayment row (PRINCIPAL | INTEREST | WRITE_OFF)
   └─ account.current_balance = remaining; today's snapshot upserted

Daily snapshot
   │
   └─ AccountService.valuation() → PeerLoanCalculator.principalRemaining(...)
        → balance_snapshot stores the step-down (0 once closed)

Close with a residual
   │
   ├─ WRITE_OFF row for the residual, closed_at = today
   ├─ valuation → 0 from today on (loss realised on this snapshot)
   └─ analysis shows the loss; cashflow and budget unchanged
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| New `PEER_LOAN` account type | The receivable reuses snapshots, history, exports and sharing for free | Reusing `LOAN` (wrong sign, wrong semantics) or `OTHER` (no semantics) |
| Dedicated ledger table | The repayment kind is a fact; a bank-feed table cannot express `WRITE_OFF` | `transaction` rows (fights `ManualTransactionService`'s balance recomputation) |
| No risk score, no provision | A guessed percentage puts a false number in net worth | Expected-loss haircut, scored counterparty |
| Dated `WRITE_OFF` at closure | The loss is an event; history is not rewritten | Retroactive revaluation, automatic aging rule |
| Explicit repayment kinds | One cash movement, one budget category | Implicit interest-first split (a synced row cannot be half income) |
| Simple interest, `actual/365` | Matches how a private loan is actually computed | Compounded interest, 30/360 |
| Derived schedule, never persisted | Same principle as the bank-loan ADR | Persisted installments, paid counter |
| Plan stored as a forecast | Editing it must not alter the balance | Treating the plan as the contract |
| `peer_counterparty` table | Stable borrower history; free text splits one person into three | Free-text counterparty |
| Cash leg manual in v1 | Writing it around a synced provider row would double the movement | Always creating a transfer row |

## Gotchas / Pitfalls

- **The forecast must never write into the ledger.** `planned_*` only feeds the
  scheduled-vs-actual figures. Touching it must not create, delete or backdate a repayment.
- **A payment below the accrued interest amortises nothing.** The dialog shows the accrued
  figure; without it the user sees a payment and a non-moving balance.
- **`PEER_LOAN` must stay out of `isLedger()`**, or a manual transaction would recompute
  `current_balance` from the transaction sum and destroy the derived value. It is mirrored
  in `NON_LEDGER_ACCOUNT_TYPES`.
- **`AssetClass.of()` and `WealthTier.of()` are exhaustive switches.** The compiler forces
  the mapping: `OTHER` for the allocation donut, and an explicit skip in
  `WealthPyramidService` so a receivable does not inflate the alternative tier.
- **`HistoryService` excludes receivables explicitly** from the invested/PnL series, next
  to its liability branches. Without that branch the amount leaks into the P&L.
- **Snapshot after the write, never before.** The daily point must reflect the new balance.
- **Overpayment is clamped for the balance but not erased from the ledger.** The amount is
  a fact.
- **Closing is not deleting.** The account stays with a badge; deleting it would also
  delete the loss history through `ON DELETE CASCADE`.
- **Deleting the closing write-off reopens the loan.** The marker is cleared when the
  recomputed capital is positive.
- **The GDPR export and the xlsx workbook do not include the repayments yet.** This is a
  known v1 gap; the account export lists the account, not its peer-loan ledger.
- **Demo mode has no peer-loan account yet.** A placeholder handler keeps the route
  coverage test green, but the feature is invisible in the demo build until a fixture is
  added.
- **The cash leg is manual in v1.** The detail shows the "Internal transfer" hint; the
  member categorises the bank movement. See the ADR.
- **The counterparty is not a `FamilyMember`.** `FamilyMember` is a Picsou household user;
  the borrower is an external person.

## Not implemented yet (v2+)

- Automatic cash leg (`TRANSFER` on a linked manual account, category only on a synced one).
- A projected-vs-actual **chart** (v1 compares the figures textually).
- A demo peer-loan account and its fixtures.
- MCP tools (read the detail, add a repayment).
- The borrowed direction (`PEER_DEBT`).
- Ownership shares on a peer-loan account (`AccountAccessResolver` scope).
- GDPR/xlsx ledger rows.

## Tests

- `PeerLoanCalculatorTest` — remaining capital, simple interest, payment below accrued
  interest, zero rate, write-off, overpayment clamp, forecast drift, both simulation
  directions.
- `PeerLoanServiceTest` — unconfigured detail, member scoping, wrong type, closed-loan
  guard, counterparty creation, closure write-off and zeroed balance, reopen on deleting
  the write-off, simulation input guard.
- `constants.test.ts` — the new account type has a label, is not a liability and is not a
  holdings type.

## Links

- ADR: [Peer loans as a receivable account with a dated repayment ledger](../decisions/2026-11-05-peer-loans-receivable.md)
- Related: [Loan accounts (LOAN type)](./loans.md) — the bank-debt counterpart
- Related: [Compute loan amortization schedules on the fly](../decisions/2026-04-26-loan-amortization-on-the-fly.md)
- Related: [Budget cycle, categorization engine, and transfer kind](../decisions/2026-06-02-budget-cycle-and-categorization.md)
- Related: [Manual transactions](./manual-transactions.md) — manual vs synced account ownership
