# ADR: Peer loans as a receivable account with a dated repayment ledger

> Date: 2026-11-05
> Status: ✅ Active (v1)

## Context

Picsou models bank debt as a `LOAN` account paired with a `Debt` row, and derives an
amortization schedule from the borrowed amount, the rate and the term
([ADR 2026-04-26](./2026-04-26-loan-amortization-on-the-fly.md)). That model assumes a
contract: a fixed installment, a fixed term, and payments that happen on schedule.

Money lent to family or friends has none of those properties. It is irregular
(three payments this winter, nothing for six months), often interest-free, sometimes
abandoned, and there is no lender, no contract and no provider to sync it from. It is also
the **opposite** direction: lending money creates an asset (a receivable), while `LOAN`
models a liability.

Users asked to track these loans: how much is still owed, what has been repaid, what has
been given up, and whether this person repaid the previous loans before deciding to lend
again.

## Decision

### 1. A peer loan is an account of a new type, `PEER_LOAN`, and it is an asset

The account carries the receivable into everything the app already does with accounts:
net worth, balance snapshots and history, the accounts list, visibility, GDPR export and
the xlsx export. The remaining capital is derived, never typed.

`PEER_LOAN` is **not** a liability: it stays positive in `AccountService.signedLiveBalanceEur`.
A future `PEER_DEBT` (money borrowed from a person) is the symmetric type, and is the only
one that would be negated there.

**One account is one loan, and the account's name is the borrower.** There is no second
"counterparty" field to enter, and an account page never aggregates another loan's
figures. Two loans to the same person are two accounts, each with its own ledger.

### 2. Facts are persisted; everything else is derived

Two tables, and no derived column:

```sql
peer_counterparty (id, member_id, name, note)
peer_loan_terms (
    account_id, counterparty_id, principal, lent_at, expected_back,
    annual_rate,                          -- 0 = interest-free (the common case)
    rate_period,                          -- ANNUAL | MONTHLY, display only
    planned_monthly_amount, planned_months, -- forecast, nullable
    linked_account_id, note, closed_at,
    lending_transaction_id                -- the cash movement we own or linked
)
peer_loan_repayment (account_id, paid_on, amount, kind, note, cash_transaction_id,
    cash_leg_owned)                       -- false when linked from an existing row
-- kind: PRINCIPAL | INTEREST | WRITE_OFF
```

Remaining capital, accrued interest and repayment percentages are recomputed on read, the
way the bank-loan schedule already is. The ledger is the only source of truth;
`account.current_balance` is a cache refreshed on every write.

### 3. Risk is observed, never predicted

There is no `risk_score` and no provision percentage. A human cannot estimate the
probability that a relative repays, and an invented percentage would put a false number in
net worth.

What the app can show is what happened: per counterparty, the number of loans, the total
lent, the total recovered, the total written off, the average delay against
`expected_back`, and the current open exposure. Facts to inform the next decision, not a
verdict.

### 4. An unrecovered balance is a dated event, not a valuation

While the loan is open, the receivable is carried at its nominal remaining capital. When
the user concludes that the rest will not come back, they **close** the loan: the residual
is written off as a `WRITE_OFF` row and the account is valued at zero from that date on.

The loss therefore appears in the net-worth history on the closure date — never as a
retroactive rewrite of the past. It is not a cash transaction and does not enter the budget
monthly cashflow; it is surfaced in the analysis as a realised loss so it does not silently
vanish.

A write-off can also be partial: forgiving 100 € while still tracking the remaining 100 €
needs no special case, because a write-off only reduces the nominal.

### 5. Interest is simple, accrued, and computed on the fly

Interest accrues on the outstanding capital at `actual/365`:

```
accrued = Σ (outstanding capital × annual rate × days / 365)
```

A ledger row states one amount. `PRINCIPAL` and `INTEREST` are payments: the accrued
interest is paid first, then the remainder reduces the capital, and the split is returned
per row so the UI can show it. `WRITE_OFF` reduces capital only. Because the cash leg is
one movement with one budget category, the interest part is tracked in the loan ledger but
not booked as budget income — the deliberate trade-off for a single repayment entry.

The rate can be stated **monthly or annually**. `annual_rate` always holds the normalized
annual fraction the calculator uses; `rate_period` only remembers the unit the member
typed, so the form reopens as it was filled. There is no annual cap: a short-term loan may
legitimately carry a high nominal rate, and the column is sized for it.

Accrued but unpaid interest is **displayed, never counted in net worth**. Counting money
that has not been received would reintroduce the optimism this ADR rejects. At closure,
unpaid accrued interest simply disappears.

A repayment's cash leg is `CategoryKind.TRANSFER` when it is linked from an existing
transaction (an asset swap, excluded from the budget). A manual repayment leg leaves an
explicit interest row uncategorised so the cashflow counts it by sign as income; the
interest part of any other repayment lives in the ledger but is not booked as income:
one cash movement carries one category.

### 6. The plan is a forecast, explicitly non-binding

`planned_monthly_amount` / `planned_months` let the user simulate "500 € over 12 months at
2 % → 42.08 €/month, 5 € of interest" and compare the theoretical curve with the real one.
The plan is **editable at any time** and recomputed on read.

It never generates repayments, never marks anything as paid, and never drives the balance.
Only the ledger does. This is the deliberate difference with the bank-loan schedule, where
`paidInstallments` is derived from today's date because the contract says payments happen.

The annuity maths is shared with `LoanAmortizationService` rather than duplicated: the
private `computeMonthlyPayment` becomes reusable, and the reverse direction
(monthly → duration) is added beside it.

### 7. One cash leg, never two

The cash movement must exist for the asset swap to hold, but exactly once. The linked cash
account is required, and the service applies one rule:

- **linked manual cash account** → the service writes the cash leg
  (`WITHDRAWAL` on lending, `DEPOSIT` on each principal repayment), both under a
  `TRANSFER` category. Net worth is neutral immediately.
- **linked synced cash account** → the service writes nothing. The provider already
  imported the row; writing a second one would double the movement.
- **no linked account** → the terms are refused, because a repayment would otherwise
  reduce net worth with no cash counterpart.

An interest repayment is a `DEPOSIT` left uncategorised, so the cashflow counts it by
sign as income. Creating a loan from an existing transaction links that row as the lending
leg instead of copying it, which is the same rule applied to money that already moved. In
the other direction, an incoming transaction can be linked as a repayment: it carries
`cash_leg_owned = false`, so deleting the repayment only unlinks it and the member's or
provider's row is never deleted or duplicated.

Once `lending_transaction_id` exists, the principal, the lending date and the linked
account are frozen: the cash movement is a fact, and changing it silently would rewrite
history.

## Alternatives considered

### Reuse `LOAN`

- **Pros**: no new type, the detail page and dashboard already exist.
- **Cons**: `LOAN` is a liability. Its balance is negated in `signedLiveBalanceEur`, its
  `Debt` row is read as a bank contract by the dashboard, the GDPR debt exporter and the
  xlsx schedule, and the amortization service would compute an instalment plan that does
  not exist. Using it would mean teaching every one of those call sites about a second
  meaning.

### Reuse the `Debt` table for the terms

- **Pros**: one loan-parameters table, and `Debt` already holds a principal and dates.
- **Cons**: `DebtRepository` feeds the liabilities card, `DebtsExporter` and the xlsx
  amortization sheet. A peer loan would be exported and rendered as a bank loan, and
  `hasSchedule` would return true, letting `computeRemainingBalance` answer an annuity.

### Reuse `transaction` instead of a dedicated ledger

- **Pros**: repayments would appear in the global transaction view for free.
- **Cons**: `ManualTransactionService` recomputes a cash account's balance from the sum of
  its transactions for ledger accounts. That would fight the `principal − repayments`
  formula. An explicit ledger also keeps the write-off kind and the per-row interest split
  out of a table designed for bank feeds.

### A provision percentage, or an expected-loss haircut

- **Pros**: net worth would look prudent before the loss is confirmed.
- **Cons**: the percentage is pure invention, it moves the patrimoine on no event, and it
  must be maintained by hand as the capital shrinks. Rejected: a dated write-off is a fact,
  a provision is a guess.

### A predicted risk score per counterparty

- **Pros**: a single number to decide.
- **Cons**: no data supports it, and a score built from three loans would be noise
  presented as insight. The factual history gives the same help without pretending.

### A standalone module outside accounts

- **Pros**: no `AccountType` change, no compiler-guided sweep.
- **Cons**: snapshots, history, net worth, exports, visibility, sharing and the MCP tools
  would all have to be re-implemented for a second kind of financial object.

### Free-text counterparty

- **Pros**: no table, no form.
- **Cons**: "Marie", "marie" and "Marie D." become three histories, which defeats the only
  feature that helps the next decision.

## Reasoning

Two principles drove the design.

The first is that **only facts belong in the database**. The bank-loan ADR already made the
case for deriving a schedule rather than persisting it; the same argument holds more
strongly here, because a peer loan has no contract to derive from. Persisting a provision,
a risk score or a paid-installment counter would each create a value that the real world
can contradict without any event recording it. A dated ledger can always be replayed, and
the closure is the one event that changes the valuation.

The second is that **a receivable is a real asset, but not an investment**. It belongs in
net worth (the cash left, and something replaced it) but not in the allocation donut, the
wealth pyramid or the PnL, which all answer "what is this money doing for me?". A loan to
a cousin does nothing.

## Trade-offs accepted

- **Net worth is optimistic while the loan is open.** The nominal is counted until the
  closure decides otherwise. Accepted: the alternative is a guessed haircut.
- **No multi-currency.** The principal and the payments are EUR; FX conversion would add a
  rate source for an amount that is usually small and fixed.
- **No automatic schedule enforcement.** A payment is never "late" in the balance; the
  delay is only displayed against `expected_back`.
- **The counterparty roster is per member.** A loan shared with the household still
  belongs to one member; extending it to co-ownership is a follow-up, not an oversight.
- **Interest compounds nowhere.** A loan that capitalises unpaid interest is not
  representable; the user records the capitalisation as a new principal instead.

## Consequences

- New `AccountType.PEER_LOAN`; migration `V135` adds the enum value alone (PostgreSQL
  refuses to use a new enum value in the transaction that added it, as `V69`, `V79`,
  `V100` and `V112` already document), `V136` creates the three tables, `V137` adds the
  two cash-leg links, `V138` the `cash_leg_owned` flag, `V139` the `rate_period` and
  `V140` widens `annual_rate` for high short-term rates and `V141` drops the
  100 % check constraint so the widened column is actually usable.
- `AccountType.isLedger()` must return **false** for `PEER_LOAN`: its balance derives from
  the principal and the ledger, not from transactions. The frontend mirror
  `NON_LEDGER_ACCOUNT_TYPES` follows.
- `AccountService.valuation()` gains a `PEER_LOAN` branch returning the remaining capital
  (zero when closed). The daily snapshot job then records the step-down — and the closure
  step — with no extra plumbing.
- `AssetClass.of()` and `WealthTier.of()` are exhaustive switches: they will fail to
  compile until `PEER_LOAN` is mapped. It maps to `OTHER` (outside the allocation donut)
  and must be skipped explicitly in `WealthPyramidService`, which today only skips
  liabilities.
- `HistoryService` gains a receivable branch next to its liability branches, so the
  receivable never leaks into the invested/PnL series.
- New `PeerLoanService`, `PeerLoanController` routes under
  `/api/accounts/{id}/peer-loan`, `POST /api/peer-loans/from-transaction`, and a
  `peer-loan` feature slice in the frontend.
- The GDPR export and the xlsx workbook must include the repayments, or financial data
  leaves the instance without a trace.
