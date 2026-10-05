# Feature: Loan accounts (LOAN type)

> Last updated: 2026-07-08

## Sign convention

**LOAN balances are stored POSITIVE** (`account.current_balance`, `balance_snapshot.balance`)
and negated only at aggregation time:

- `AccountService.liveBalanceEur(account)` returns the remaining capital as a **positive**
  value for LOAN accounts (`LoanAmortizationService.computeRemainingBalance` returns positive;
  the fallback to the stored balance is positive too).
- `AccountService.signedLiveBalanceEur(account)` is the single helper for net-worth-style
  summations: it negates LOAN balances and passes every other type through. Any new code that
  sums balances across account types MUST use it — reviewers should reject new unsigned
  `liveBalanceEur` sums.
- Negation sites: `DashboardService` and `HistoryService` (inline LOAN branches, kept until
  plan 005), `FamilyViewService` and `GoalService` (via `signedLiveBalanceEur`).
- The Finary API sync (`FinaryApiSyncService.toLoanAccountDto`) stores the outstanding amount
  positive. It briefly stored it negative — migration `V52__fix_negative_loan_balances.sql`
  repaired the corrupted rows and snapshots (existing users saw their net worth *drop to the
  correct value*: the data was wrong before, not after).

## Context

`LOAN` accounts represent debts (mortgage, consumer loan, etc.). Until this feature, a LOAN
account only exposed a static balance and a lender name. Users wanted a Finary-style detail
view: monthly payment broken down into capital / interest / insurance, paid vs remaining
installments, end date, total cost summary, an amortization curve, and a balance that decreases
month after month automatically.

All required values can be derived from a small set of persisted fields (the `Debt` table) using
the standard French amortization formula. The schedule itself is **never persisted** — it is
recomputed on the fly from the borrowed amount, interest rate, optional monthly payment, and
start/end dates. See ADR
[2026-04-26-loan-amortization-on-the-fly.md](../decisions/2026-04-26-loan-amortization-on-the-fly.md).

## How it works

A `LOAN` account is paired 1:1 with a `Debt` row that holds the loan parameters. Two endpoints
are involved:

1. `PUT /api/accounts/{id}/debt` (already existed) — saves the loan parameters
2. `GET /api/accounts/{id}/loan-summary` (new) — returns the full computed schedule + summary

The daily snapshot job (`SchedulerService.dailySnapshots`) calls
`AccountService.liveBalanceEur(account)`, which for LOAN accounts is now branched to compute the
remaining capital from the formula. As a result, the historical balance chart shows the natural
"décote" — the balance steps down each month as installments are paid.

### Key files

- `backend/src/main/java/com/picsou/model/Debt.java` — entity (now includes `insuranceMonthly`, `fileFees`)
- `backend/src/main/java/com/picsou/service/LoanAmortizationService.java` — formula + records
  (`LoanInstallment`, `LoanSummary`, `LoanScheduleResponse`)
- `backend/src/main/java/com/picsou/service/AccountService.java`
  - `liveBalanceEur` — branched for LOAN to return the (positive) remaining capital
  - `signedLiveBalanceEur` — applies the liability sign (LOAN → negative) for net-worth sums
  - `getLoanSummary(id, memberId)` — loads the Debt, validates type, delegates to amortization service
- `backend/src/main/java/com/picsou/controller/AccountController.java` — `GET /accounts/{id}/loan-summary`
- `backend/src/main/resources/db/migration/V27__loan_extra_fields.sql` — adds `insurance_monthly`, `file_fees`
- `frontend/src/components/loan/LoanDetailSection.tsx` — orchestrator
  (monthly breakdown, progress, cost summary, amortization chart)
- `frontend/src/components/shared/AccountForm.tsx` — extended form for LOAN
- `frontend/src/pages/accounts/AccountDetailPage.tsx` — replaces holdings/transactions with the loan section

### Flow

```
User opens /accounts/{id}  (account.type === 'LOAN')
   │
   ▼
useLoanSummary(id)  ─▶  GET /api/accounts/{id}/loan-summary
                              │
                              ▼
                       AccountService.getLoanSummary
                              │
                              ▼
                  LoanAmortizationService.compute(debt, today)
                              │
                              ▼
              { summary, schedule[]: 60 monthly entries }
                              │
                              ▼
   LoanDetailSection ─▶ MonthlyBreakdown · Progress · Summary · AmortizationChart


Daily 08:05 cron
   │
   ▼
SchedulerService.dailySnapshots
   │
   ▼
AccountService.liveBalanceEur(account=LOAN)
   │
   ▼
LoanAmortizationService.computeRemainingBalance(debt, today)  → positive BigDecimal
   │
   ▼
BalanceSnapshot persisted  →  historical balance chart steps down monthly
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Compute schedule on demand | Source of truth = a few `Debt` fields; the schedule itself is large and rarely viewed | Persist the schedule as `loan_installment` rows |
| Branch `liveBalanceEur` for LOAN | Reuses the existing daily snapshot pipeline → free monthly "décote" | New scheduler dedicated to loan recomputation |
| Pass the user-entered interest rate as a percentage from the UI, decimal in DB | Mirrors how Finary asks for it ("1.5 %") | Decimal everywhere — confusing in the form |
| BigDecimal w/ `MathContext.DECIMAL64` + scale 2 | Matches the rest of the monetary code (no IEEE drift) | `double` — accumulates rounding error over 240+ installments |

## Gotchas / Pitfalls

- **A computed monthly payment includes the insurance.** `monthlyPayment` is the whole installment; the schedule subtracts `insuranceMonthly` from it before amortising. A user-entered payment already includes the insurance, a computed annuity does not, so `LoanAmortizationService` adds it when it computes the payment itself. Before that, every installment under-amortised the capital by the insurance and the last installment absorbed the shortfall.
- **A `Debt` without a start and an end date has no schedule.** `computeRemainingBalance` then answers the borrowed amount, not what is owed, so `AccountService.valuation` and `RealEstateSummaryService` check `LoanAmortizationService.hasSchedule` first and fall back to the balance stored on the loan account.
- **The last installment absorbs the rounding residue.** With BigDecimal precision and scale 2,
  240 monthly capitals do not sum exactly to the borrowed amount; the service forces the
  last installment to bring `remainingBalance` to zero.
- **`liveBalanceEur` returns POSITIVE for LOAN; only aggregation negates.** See the
  "Sign convention" section above — the dashboard "Total liabilities" card adds the positive
  value to liabilities and subtracts liabilities from assets.
- **`paidInstallments` is computed from "today", not from a count of payments.** No transaction
  is required against the LOAN account — the formula assumes payments occur on schedule. If a
  user pays late or makes prepayments, the model does not capture that (out of scope).
- **`monthlyPayment` is optional** in the form. If null, the service computes it from the
  standard formula `M = P · r / (1 − (1 + r)^-n)`.
- **Holdings, transactions, and the manual snapshot history dialog are hidden** for LOAN
  accounts in `AccountDetailPage` — the loan section is the canonical view.
- **Finary-imported loans have no `Debt` row.** The Finary API sync imports loan/mortgage
  accounts from the dedicated `/loans` endpoint (see [finary-import.md](finary-import.md), issue #11)
  as `LOAN` accounts with the outstanding amount stored as a positive `currentBalance`. The
  loans payload does not expose the original principal or interest rate, so no `Debt` is
  created — `liveBalanceEur` gracefully falls back to the stored balance when no `Debt` exists,
  and the rich amortization view only appears once the user fills in the loan parameters via
  `PUT /api/accounts/{id}/debt`.

## Tests

- `LoanAmortizationServiceTest` — zero rate, computed monthly payment, paid installments from
  asOf date, capitalRepaidPct, insurance split, totalCost includes fileFees, finished loan,
  not-yet-started loan, `computeRemainingBalance`
- `AccountControllerLoanTest` — endpoint delegates correctly, propagates 404 (no Debt) and 400
  (account is not LOAN)
