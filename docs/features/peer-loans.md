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
-- V135: ALTER TYPE account_type ADD VALUE 'PEER_LOAN' BEFORE 'OTHER';
-- V136: tables below.  V137: the two cash-leg links.  V138: cash_leg_owned.
-- V139: rate_period.

peer_counterparty (id, member_id, name, note)
    UNIQUE (member_id, lower(name))

peer_loan_terms (
    id, account_id UNIQUE, counterparty_id,
    principal, lent_at, expected_back, annual_rate,
    rate_period,                              -- ANNUAL | MONTHLY, display only
    planned_monthly_amount, planned_months,   -- forecast only, both or neither
    linked_account_id, note, closed_at,
    lending_transaction_id                    -- the outflow we own or linked
)

peer_loan_repayment (
    id, account_id, paid_on, amount,
    kind,                                     -- PRINCIPAL | INTEREST | WRITE_OFF
    note, cash_transaction_id,
    cash_leg_owned                            -- false when linked from an existing row
)
```

Only facts are stored. Remaining capital and accrued interest are derived on read,
following [ADR 2026-04-26](../decisions/2026-04-26-loan-amortization-on-the-fly.md).

### One account, one loan

The account's own name **is** the borrower. There is no second "counterparty" field:
the `peer_counterparty` row is created from the account name so the future borrower
grouping has a stable key, but the account page never aggregates another loan's figures.
Two loans to the same person are two accounts ("Jean — voiture", "Jean — caution"), each
with its own summary and ledger.

### Derivation

`PeerLoanCalculator.compute(terms, repayments, asOf)` replays the timeline:

1. Start at `lent_at` with `outstanding = principal` and `accrued = 0`.
2. For each event in date order:
   - accrue simple interest on `outstanding` from the previous event date,
     `outstanding × annual_rate × days / 365`, `actual/365`;
   - `PRINCIPAL` and `INTEREST` are payments: the accrued interest is paid first, then
     the remainder reduces the capital (the banking convention);
   - `WRITE_OFF` reduces the capital only — it is a decision, not a payment.
3. Accrue to `asOf` and return the computed figures.

A payment is **one amount**. The split is derived, not entered: the calculator returns
`interestPart` and `capitalPart` for each row, so the ledger can show "dont X € d'intérêts"
while the dialog asks for a single amount. `kind` now only distinguishes a write-off (and
an explicit interest-only entry remains possible through the API).

Because the cash leg is one movement with one budget category, the interest part is tracked
in the loan ledger but not booked as budget income. That is the deliberate trade-off for a
single repayment entry: the budget stays a transfer, the loan knows how much of it was
interest.

Edge cases:

- a payment smaller than the accrued interest amortises nothing; the dialog shows the
  accrued figure so the user sees why;
- an overpayment is clamped to zero capital for the balance, but the amount stays recorded;
- an explicit `INTEREST` repayment cannot exceed the interest accrued at its own date: the
  excess would be labelled as income while the ledger has nothing to pay;
- a zero rate short-circuits the interest branch and the whole payment amortises;
- the linked cash account must be in EUR, because the principal and the ledger are EUR
  figures that are never converted a second time;
- a repayment date cannot be in the future: the ledger records completed payments, and the
  forecast belongs to `planned_*`;
- the lending date cannot be in the future either: the receivable would count before the
  money left, and it cannot predate 1900 (a backdated plan would draw tens of thousands of
  curve points).

### Closure

Closing writes a `WRITE_OFF` row for the residual (if any) and stamps `closed_at`:

- `AccountService.valuation()` returns zero from that date, so the daily snapshot records
  the loss on the closure date — the past is never rewritten;
- the account stays visible with a "closed" badge so the history remains readable;
- the written-off amount appears in the summary as a realised loss, outside the monthly
  budget (there is no cash movement and it is not consumption);
- unpaid accrued interest is dropped: it was never received, so it was never counted.

The closure date cannot precede the lending date or be in the future: a future write-off
would claim a loss before it happened.

Deleting the closing write-off reopens the loan automatically: the marker is cleared when
the recomputed capital is positive again. No separate "reopen" action can drift from the
ledger.

### Interest and cashflow

| Movement | `kind` | Budget category | Effect |
|---|---|---|---|
| Money lent (cash out) | — | `TRANSFER` | none |
| Payment (cash in) | `PRINCIPAL` | `TRANSFER` | none; the interest part is in the ledger, not the budget |
| Explicit interest-only entry (API) | `INTEREST` | uncategorised | counts as income by sign |
| Write-off | `WRITE_OFF` | none | net worth only |

`CategoryKind.TRANSFER` is already excluded from cashflow. An **uncategorised** `DEPOSIT`
counts as income by its sign, which is why an explicit interest payment is deliberately left
uncategorised: interest is real income, not a transfer.

### Cash-flow neutrality

The receivable only balances the cash movement if that movement exists exactly once.
The linked cash account is **required** when the terms are saved, and the service applies
one rule:

- **manual linked account** → the service writes the leg: `WITHDRAWAL` of the principal
  when the terms are saved, `DEPOSIT` for each capital repayment, both under the
  `virement-interne` TRANSFER category. Net worth is neutral immediately.
- **synced linked account** → the service writes nothing. The provider already imported
  the row; the member categorises it once as "Internal transfer".
- **no linked account** → the request is refused, because a repayment would otherwise
  reduce net worth with no cash counterpart.

An interest repayment is a `DEPOSIT` left uncategorised: a positive amount counts as
income in the cashflow, which is correct — interest is real income, not a transfer.

An incoming transaction can be **assigned** to an open loan instead of typing a
repayment. It is linked (`cash_transaction_id`, `cash_leg_owned = false`) and never
copied, so a synced account never receives a second row. Deleting that repayment only
unlinks the transaction; the member's or provider's row stays. Editing a linked
repayment is refused for the same reason: delete it and assign the transaction again.

The principal and the linked account cannot change once `lending_transaction_id` exists:
the cash movement is a fact. Deleting and recreating the loan is the honest path.

### Forecast (editable, non-binding)

`planned_monthly_amount` / `planned_months` are a simulation, editable at any time (the
terms dialog is also the plan editor). They never generate repayments, never mark anything
as paid and never drive the balance.

`POST /api/peer-loans/simulate` answers both directions:

- duration → monthly payment: `M = P·i / (1 − (1+i)^−n)`, `i = rate/12`, `P/n` at zero rate;
- monthly payment → duration: `n = −ln(1 − P·i/M) / ln(1+i)`, refused when `M ≤ P·i`
  (the payment never amortises).

Both answers compute `totalCost` and `totalInterest` from the schedule they describe: the
last instalment clears the remaining balance (plus its interest) instead of repeating a
full monthly payment, so a payment-driven simulation does not overstate the cost.

`planned_months` is capped at **1 200** (a century): the forecast curve draws one point per
month, and an unbounded count would let one request build an arbitrary array.

The maths lives in `PeerLoanCalculator` and mirrors `LoanAmortizationService`'s annuity, so
the forecast and the bank-loan schedule cannot disagree. The detail draws the actual
capital as a solid step line and the plan as a dashed line, and reports the drift in
installments. The same card compares the total interest the plan implies
(`plannedInterest`) with the interest actually produced so far
(`interestAccrued + interestReceived`). This is information, not a verdict: no automatic
"late" state, no scoring.

The rate can be entered **per year or per month**. `annual_rate` always stores the
normalized annual fraction the calculator uses; `rate_period` only remembers how the
member stated it, so a loan agreed at 0.5 %/month reopens as 0.5 %/month rather than 6 %/year.
There is **no low cap**: a short-term loan may carry a high nominal rate (10 %/month is
120 %/year), and the column stores it with six decimals. The API still bounds the input
(rate ≤ 1 000, six integer digits, and a loose 30-fraction-digit guard so doubles like
`0.011000000000000001` pass while tiny exponents like `1E-100000000` are rejected
before they can pin the calculator, then rounded to six decimals by the backend;
amounts ≤ 12 integer digits) so the annuity cannot be exponentiated into a CPU sink
or overflow the `NUMERIC` columns.

### REST endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/accounts/{id}/peer-loan` | detail: terms, summary, ledger |
| `PUT` | `/api/accounts/{id}/peer-loan` | upsert the terms (and the forecast plan) |
| `POST` | `/api/accounts/{id}/peer-loan/repayments` | add a repayment (201) |
| `POST` | `/api/accounts/{id}/peer-loan/repayments/from-transaction` | link an incoming transaction |
| `PUT` | `/api/accounts/{id}/peer-loan/repayments/{repaymentId}` | edit |
| `DELETE` | `/api/accounts/{id}/peer-loan/repayments/{repaymentId}` | delete (204) |
| `POST` | `/api/accounts/{id}/peer-loan/close?date=` | write off the residual and close |
| `POST` | `/api/peer-loans/simulate` | duration ↔ monthly payment preview |
| `GET` | `/api/peer-loans/open` | open loans, for the repayment picker |
| `GET` | `/api/peer-loans/overview?months=12` | every configured loan, the totals and the outstanding curve |
| `POST` | `/api/peer-loans/from-transaction` | create a loan from an existing outflow |

Every account-scoped route resolves the account with `findByIdAndMemberId` before touching
a child row: the ledger is scoped through its account, never through an unvalidated id.

The overview reads every configured loan of the member, including closed ones, and derives
the same figures as the detail one loan at a time. Its curve reuses `HistoryService` on the
same accounts, so it cannot disagree with the net-worth chart about a loan's balance.
Unconfigured accounts are left out: they have no terms to describe.

### MCP tools

`get_peer_loan` (`accounts:read`) returns the same detail as the REST `GET`, and
`add_peer_loan_repayment` (`accounts:write`) records one repayment through the same service,
including the cash leg on a manual linked account; `withCashLeg=false` records a compensation
with no bank movement. `get_peer_loans_overview`
(`accounts:read`) returns every configured loan, **closed ones included**, with its last
payment and plan drift: the full history an advisor needs. `close_peer_loan`
(`accounts:write`) writes off the residual on a date and closes the loan. A loan is a
`PEER_LOAN` account, so the account scopes cover all four and the consent surface does not
change. The repayment tool refuses `WRITE_OFF`: closing a loan stays an explicit action,
not a side effect of a payment.

### Frontend

- `components/peer-loan/PeerLoanDetailSection.tsx` renders in `AccountDetailPage` where
  `LoanDetailSection` does for `LOAN`:
  - summary: lent / repaid (%) / remaining / accrued interest / written off;
  - forecast card with the scheduled-vs-actual figures, the drift, and the
    actual-vs-scheduled chart (`PeerLoanForecastChart`);
  - repayment ledger with the kind badge, the "dont X € d'intérêts" split, add / edit /
    delete; capital and interest amounts are green and prefixed `+`, a write-off is red
    and prefixed `−`. Editing is hidden once the loan is closed, but the write-off stays
    deletable: removing it is how the loan is reopened;
  - "close the loan" action with the residual amount in the confirmation.
- `PeerLoanTermsDialog.tsx` — terms plus the simulator (a duration fills the monthly
  amount, or a monthly amount fills the duration). The rate is entered per year or per
  month; switching the period converts the visible value. The linked cash account is
  required; the borrower is the account name. **On the first configuration of a manual
  cash account**, a checkbox can skip the lending outflow when the typed balance already
  includes it (a loan recorded after the fact).
- `PeerLoanRepaymentDialog.tsx` — one date and one amount; the calculator applies the
  accrued interest first and the ledger row shows the split. **"Create the cash movement"
  is on by default**; turning it off records a compensation (the receivable falls, no bank
  deposit) and the ledger marks the row "No cash movement".
- `CreatePeerLoanDialog.tsx` — opened from an outgoing transaction's detail sheet;
  prefills the borrower from the merchant label and links the transaction as the lending
  leg.
- `AssignRepaymentDialog.tsx` — opened from an incoming transaction; picks an open loan,
  then links the transaction as the repayment's cash leg (interest first, capital next).
- `AccountForm` hides the balance, currency, bank, ticker and manual fields for a peer
  loan and labels the name "Borrower".
- `AccountsPage` gains a `RECEIVABLES` filter and a **"Create a loan"** header action:
  it opens the manual form with the peer-loan type already selected, then lands on the
  account detail where **"Configure the loan"** is waiting. A loan recorded after the fact
  can skip the lending outflow there.
- `components/peer-loan/PeerLoansOverviewCard.tsx` is the member-wide view: total
  outstanding, open loans and accrued interest, the outstanding curve over 12 months, and
  one row per borrower with the remaining capital, repayment percentage, plan drift, last
  payment and expected return. It is shown above the cards in **Accounts → Créances**; a
  row opens the loan. Closed loans stay in the table with a "Closed" badge and a muted row,
  so their ledger and their deletion remain reachable. It is facts only: a late badge
  counts the instalments the plan says are missing, it does not score the borrower.
- i18n keys in all four locales (`fr`, `en`, `de`, `es`).

### Key files

Backend:

- `backend/.../model/AccountType.java` — `PEER_LOAN`, `isLedger()` exclusion
- `backend/.../model/PeerCounterparty.java`, `PeerLoanTerms.java`, `PeerLoanRepayment.java`,
  `PeerLoanRepaymentKind.java`, `PeerLoanRatePeriod.java`
- `backend/.../repository/PeerCounterpartyRepository.java`, `PeerLoanTermsRepository.java`,
  `PeerLoanRepaymentRepository.java`
- `backend/.../service/PeerLoanCalculator.java` — pure arithmetic (remaining, interest,
  plan, simulation)
- `backend/.../service/PeerLoanService.java` — terms, ledger, closure, cash legs,
  creation from a transaction
- `backend/.../controller/PeerLoanController.java`
- `backend/.../dto/PeerLoan*.java`
- `backend/.../service/AccountService.java` — `valuation()` branch
- `backend/.../service/HistoryService.java` — receivable excluded from invested/PnL
- `backend/.../service/WealthPyramidService.java` - receivable skipped
- `backend/.../service/DashboardService.java` - receivable excluded from the allocation
  distribution; the response also carries an `assets` list (receivable included) that the
  wealth modes, history and gross figure use
- `backend/.../model/AssetClass.java`, `WealthTier.java` — exhaustive switches
- `backend/.../db/migration/V135__account_type_peer_loan.sql`,
  `V136__peer_loans.sql`, `V137__peer_loan_cash_leg.sql`,
  `V138__peer_loan_linked_repayment.sql`, `V139__peer_loan_rate_period.sql`,
  `V140__widen_peer_loan_rate.sql`, `V141__peer_loan_rate_no_upper_bound.sql`

Frontend:

- `frontend/src/types/api.ts`, `frontend/src/lib/constants.ts`
- `frontend/src/features/peer-loans/{api,hooks}.ts`
- `frontend/src/components/peer-loan/*` (detail, terms, repayment, create/assign dialogs,
  forecast chart)
- `frontend/src/components/peer-loan/PeerLoansOverviewCard.tsx` (the member-wide overview)
- `frontend/src/pages/accounts/AccountDetailPage.tsx`,
  `frontend/src/pages/accounts/AccountsPage.tsx`
- `frontend/src/components/shared/AccountForm.tsx`
- `frontend/src/demo/index.ts` (the two demo loans and the overview handler)

### Flow

```
Create a peer loan
   │
   ├─ Account(type=PEER_LOAN, isManual=true) created, its name is the borrower
   ├─ terms entered on the detail page (principal, dates, rate, linked cash account)
   ├─ withCashLeg=false → no lending outflow (the cash balance already reflects it)
   └─ manual linked account → WITHDRAWAL of the principal (TRANSFER category)

Create from a transaction
   │
   ├─ the outgoing transaction is linked, not copied
   └─ it is categorised as TRANSFER; the loan adds the matching receivable

Add a repayment
   │
   ├─ peer_loan_repayment row (PRINCIPAL | INTEREST | WRITE_OFF)
   ├─ withCashLeg=false → no cash row, the receivable steps down (compensation)
   ├─ manual linked account → DEPOSIT (TRANSFER for capital, uncategorised income
   │  for interest)
   ├─ account.current_balance = remaining; a snapshot is upserted on the event date
   └─ every persisted snapshot at or after that date is recomputed (a backdated
      repayment changes the later balances, not only its own day)

Daily snapshot
   │
   └─ AccountService.valuation() → PeerLoanCalculator.principalRemaining(...)
        → balance_snapshot stores the step-down (0 once closed)

Overview in Accounts → Créances
   │
   └─ PeerLoanService.overview() → every configured loan + totals
        └─ HistoryService on the same account ids → outstanding curve

Close with a residual
   │
   ├─ WRITE_OFF row for the residual, closed_at = today
   ├─ valuation → 0 from today on (loss realised on this snapshot)
   └─ analysis shows the loss; cashflow and budget unchanged
```

## Testing it locally (isolated stack)

`docker/docker-compose.peer-loans-test.yml` starts a disposable instance that shares
nothing with production: its own Compose project (`picsou-peer-loans-test`), its own image
tag, volumes, database and host port (`127.0.0.1:18080`). It never reads `docker/.env`, so
the production secrets are out of reach.

```bash
docker compose -f docker/docker-compose.peer-loans-test.yml up -d --build
# open http://localhost:18080
```

The image is built from the working tree, so it includes uncommitted changes. First launch
runs the setup wizard: create the admin and skip the integration steps — no sidecar runs in
this stack. Then:

1. **Create the cash account first.** Add a manual "Compte courant" and record an
   **opening deposit** for the cash it holds: a manual account's balance is the sum of its
   transactions, so the "Solde" typed at creation is only a snapshot and is replaced by the
   first ledger row. The loan draws from that ledger.
2. **Accounts → Add account**, pick "Peer loan": the name is the borrower ("Jean"), save.
3. Open the account and click **Set up the loan**: amount, date, **linked cash account**
   (required), optional rate. On the manual cash account the outflow is written immediately.
4. Add a **repayment** with one amount: the accrued interest is applied first, then the
   rest reduces the capital. The remaining capital, the progress and the forecast card
   move, and the cash account receives the matching deposit (green, prefixed `+`). A
   write-off shows in red, prefixed `−`.
5. **Close the loan**: the residual is written off and the account is worth zero from
   today; the loss is in the history.
6. To test the transaction flow, open a cash account's **Transactions**, click an outgoing
   row, then **Create a peer loan**. The transaction is linked, not copied.
7. Click an incoming row instead, then **Assign to a repayment** and pick the loan. The
   amount is applied interest first, then capital, with no kind to choose. The transaction
   is linked, not copied, so the cash account gets no second line.

```bash
docker compose -f docker/docker-compose.peer-loans-test.yml logs -f app
docker compose -f docker/docker-compose.peer-loans-test.yml down      # keep the test data
docker compose -f docker/docker-compose.peer-loans-test.yml down -v   # wipe it
```

The `db` container has no host port: the database is only reachable by the test app.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| New `PEER_LOAN` account type | The receivable reuses snapshots, history, exports and sharing for free | Reusing `LOAN` (wrong sign, wrong semantics) or `OTHER` (no semantics) |
| Dedicated ledger table | The repayment kind is a fact; a bank-feed table cannot express `WRITE_OFF` | `transaction` rows (fights `ManualTransactionService`'s balance recomputation) |
| No risk score, no provision | A guessed percentage puts a false number in net worth | Expected-loss haircut, scored counterparty |
| Dated `WRITE_OFF` at closure | The loss is an event; history is not rewritten | Retroactive revaluation, automatic aging rule |
| Single payment, interest first | One amount to enter; the split is derived and shown on the row | Explicit Capital/Intérêts rows (two entries for one movement) |
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
- **Reaching zero closes the loan automatically.** The last repayment that clears the
  capital stamps `closed_at` on its own date, so a 0 EUR receivable never lingers on the
  dashboard. The explicit close action remains for the other case: abandoning a residual
  that will not be repaid.
- **A closed loan leaves the current totals, not the record.** It is absent from the
  dashboard's asset distribution, but the Créances overview always shows it with a "Closed"
  badge (so its ledger and its deletion remain reachable), and the net-worth history keeps
  the write-off. In Accounts, the "Show closed loans" header toggle only controls the
  cards: the overview table keeps every loan either way.
- **Deleting the closing write-off reopens the loan.** The marker is cleared when the
  recomputed capital is positive.
- **The borrower is the account name, not a `FamilyMember`.** `FamilyMember` is a Picsou
  household user; the account page shows only that loan.
- **A linked cash account is required.** Without one, a repayment would reduce net worth
  with no cash counterpart. The terms request enforces it, and the dialog marks it.
- **The principal and the linked account are frozen once the cash leg exists.** Changing
  either would silently rewrite a real movement; deleting and recreating the loan is the
  honest path.
- **A loan recorded after the fact can skip the lending leg.** `withCashLeg=false` on the
  first configuration writes no withdrawal: the typed cash balance already includes the
  money that left. The option is only offered for a manual linked account, and only before
  a leg exists; writing it twice would double the outflow in net worth.
- **A synced account's row is not categorised automatically.** The provider owns it, so
  the member classifies the **lending** row once as "Internal transfer"; the detail keeps
  the hint while no link is set. A repayment assigned from an incoming transaction is
  categorised as a transfer for both capital and interest: the interest is tracked in the
  loan ledger, not booked as income twice.
- **A repayment can skip the cash leg.** `withCashLeg=false` records a compensation (a
  netting, or a payment already booked elsewhere): the receivable falls and no deposit is
  written. The row stays editable and deletable, and the ledger badges it "No cash
  movement". Use it only when the cash account genuinely must not move; otherwise the
  default keeps net worth honest.
- **A linked row is never duplicated or deleted.** A repayment added from an incoming
  transaction has `cash_leg_owned = false`: deleting the repayment only unlinks it, and
  editing it is refused (delete and re-assign). On a synced account the service writes no
  cash row at all, so the provider's movement is never doubled.

## Not implemented yet (v2+)

- Automatic categorisation of the provider row on a synced linked account (today the
  member classifies it once).
- The borrowed direction (`PEER_DEBT`).
- Ownership shares on a peer-loan account (`AccountAccessResolver` scope).

## Tests

- `PeerLoanCalculatorTest` — remaining capital, simple interest, payment below accrued
  interest, interest-first allocation of a payment, zero rate, write-off, overpayment
  clamp, forecast drift, total planned interest, both simulation directions, high
  short-term rate.
- `PeerLoanServiceTest` — unconfigured detail, member scoping, wrong type, closed-loan
  guard, account name as the borrower, no leg on a synced account, the outflow written on
  a manual one, a first configuration that deliberately skips the lending leg, a repayment
  with no cash leg, removing a cash leg on edit, closure write-off and zeroed balance, the
  final repayment closing the loan by itself, reopen on deleting the write-off, loan
  creation from an existing transaction, a repayment linked from an incoming transaction
  (no copy, not deleted), simulation input guard, and the member-wide overview (open/closed
  counts, totals, ordering, last payment, curve).
- `DataExportServiceTest` — the three peer-loan CSV/JSON sections, including the borrower,
  the plan and the journal's `cash_leg_owned` flag.
- `AccountsWorkbookServiceTest` — the peer-loan block and its repayment journal, with the
  rate expressed in the stated period and the planned interest.
- `PeerLoanToolsTest` — all four MCP tools delegate to `PeerLoanService`, validate their
  arguments, refuse a standalone write-off and require the `accounts:*` scope.
- `PeerLoansOverviewCard.test.tsx` — totals and one row per borrower, the
  late/on-time/closed badges, and opening a loan by click or keyboard.
- `constants.test.ts` — the new account type has a label, is not a liability and is not a
  holdings type.

## Links

- ADR: [Peer loans as a receivable account with a dated repayment ledger](../decisions/2026-11-05-peer-loans-receivable.md)
- Related: [Loan accounts (LOAN type)](./loans.md) — the bank-debt counterpart
- Related: [Compute loan amortization schedules on the fly](../decisions/2026-04-26-loan-amortization-on-the-fly.md)
- Related: [Budget cycle, categorization engine, and transfer kind](../decisions/2026-06-02-budget-cycle-and-categorization.md)
- Related: [Manual transactions](./manual-transactions.md) — manual vs synced account ownership
