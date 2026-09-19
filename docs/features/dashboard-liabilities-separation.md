# Feature: Dashboard — Liabilities separated from portfolio performance

> Last updated: 2026-07-12

## Context

With LOAN accounts present, the dashboard used to read debt drag as poor
portfolio performance: `pnl = total − invested` where loans contribute
`−balance` to `total` and `0` to `invested`, so the full outstanding debt
appeared as an investment loss (GitHub issue #18). Loans stay liabilities — no
data-model change — but the dashboard now separates four readings: **assets**,
**liabilities**, **net worth**, and **portfolio performance**.

## How it works

**The four readings and their semantics:**

- `total` (history points, PnL response) remains **net worth**: loans are
  negated, so the curve is honest about debt.
- `pnl` (every aggregation path: historical points, the live point, `buildPnl`,
  and the MCP `get_profit_and_loss` tool which delegates to `buildPnl`) is
  **debt-neutral**: the sum over non-LOAN accounts of `(value − invested)`.
  Loans contribute exactly 0 — never `−balance`. A loan-only selection has
  `pnl = 0`.
- `rangePnl` is **pure portfolio performance**: computed only over holdings
  whose price is known on BOTH sides of the range (live via
  `PriceService.getPriceEur` and at `fromDate` via `price_snapshot`). Cash,
  loans, and unmatched holdings are excluded from both sides, so
  `rangePnl = liveMatchedValue − valueAtFrom`.
- Allocation percentages divide by their own side of the balance sheet:
  asset items by `totalAssets`, liability items by `totalLiabilities`
  (≤ 0 divisor → 0.0). Percentages can no longer exceed 100 % when debt exists.

**UI:**

- The dashboard chart card is titled by wealth mode
  (`dashboard.evolution.net|gross|financial` — "Net worth evolution" etc.), not
  "Gain / Loss": the curve plots wealth, not performance.
- The chart tooltip's gain/loss row reads the backend `pnl` field of each
  `NetWorthPoint` instead of recomputing `total − invested` client-side. Intraday
  points carry no `pnl` → the row is omitted on the 24H range.
- `LiabilitiesCard` renders `data.liabilities` — each loan's name, outstanding
  amount in red, and its **repayment progress**: a `percentPaid` bar plus the
  monthly payment when the loan's `Debt` row supplies them, or an "unconfigured"
  hint otherwise. The header sums `totalMonthlyPayment`. Rendered when
  `data.liabilities.length > 0`. Full card spec:
  [dashboard-liabilities-card.md](./dashboard-liabilities-card.md).

### Key files

- `backend/src/main/java/com/picsou/service/HistoryService.java` —
  `buildHistory` (per-date `aggPnl` accumulator + live point `livePnl`),
  `buildPnl` (`liveNonLoanValue`, matched-holdings `rangePnl`)
- `backend/src/main/java/com/picsou/service/DashboardService.java` —
  `buildDistribution(accounts, divisor, …)` with per-side divisors; loan rows
  are then enriched into `LiabilityEntry(monthlyPayment, percentPaid)` via
  `DebtRepository` + `LoanAmortizationService`, and `totalMonthlyPayment` is summed
- `frontend/src/pages/dashboard/DashboardPage.tsx` — mode-dependent chart
  title, renders `LiabilitiesCard`
- `frontend/src/components/shared/NetWorthChart.tsx` — tooltip reads row `pnl`
- `frontend/src/components/shared/LiabilitiesCard.tsx` — liabilities list
- `frontend/src/demo/data/dashboard.ts` — demo mock ships one loan so demo
  mode exercises the card

### Flow

```text
balance_snapshot ──► buildHistory ──► NetWorthPoint(total=net worth,
        │                                pnl=Σ non-loan (value−invested))
        │                                      │
live balances ────► buildPnl ──► PnlResponse(total, pnl debt-neutral,
        │                          rangePnl over matched holdings only)
        │                                      │
DashboardService ─► distribution %  = balance / totalAssets
                    liabilities %   = balance / totalLiabilities
                    liabilities enriched → LiabilityEntry(monthlyPayment, percentPaid)
                                               │
DashboardPage ─► chart (title per wealth mode, tooltip = point.pnl)
              ─► LiabilitiesCard (when liabilities present)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Loans contribute 0 to pnl everywhere | Debt is a liability, not an investment loss | Changing `AccountType` / data model (explicitly rejected by issue author) |
| `rangePnl` over holdings priced on both sides | Comparing a net-worth live side against a holdings-only baseline mixed cash/debt into "performance" (audit BE-04) | Keeping `liveTotal − valueAtFrom` |
| Per-side percentage divisors | Net-worth divisor produced >100 % shares (or all-zero when net worth ≤ 0) | Keeping net-worth divisor |
| Tooltip reads backend `pnl` | One source of truth; hero PnL and tooltip agree by construction | Client-side `total − invested` recomputation |

## Gotchas / Pitfalls

- Any surviving `subtract` of a loan value inside a pnl computation is a bug —
  "loans contribute 0 to pnl" is the invariant across ALL aggregation paths.
- `total`/`invested` in `PnlResponse` keep their old meaning (net worth /
  invested); only `pnl` and `rangePnl` changed semantics.
- Intraday points (`buildIntradayHistory`) carry no pnl field — deliberately
  untouched (its timezone bugs are a separate concern, audit BE-11).
- Per-liability repayment "progress" (`percentPaid`, monthly payment) IS shown
  on the card — a display-only enrichment derived from each loan's `Debt` row.
  It is never re-mixed into `pnl`; the debt-neutral invariant above still holds.
- The demo `GET /history` handler ships per-account net-worth series
  (`DEMO_NW_BALANCES`) plus `/history/pnl` and `/history/net-worth/intraday`, so
  the demo dashboard chart is fully exercised (the old FE-06 gap is closed).

## Tests

- `HistoryServiceTest` — debt-neutral pnl in `buildHistory` (aggregate +
  per-account split), `buildPnl` (loan + brokerage, loan-only → pnl 0),
  matched-holdings `rangePnl` (incl. a holding without a snapshot at
  `fromDate` excluded from both sides)
- `DashboardServiceTest` — percentages divide by own-side totals; asset split
  25 / 75 with debt present

## Links

- Issue: https://github.com/Cloeille/picsou-finance/issues/18
- Related: [dashboard-liabilities-card.md](./dashboard-liabilities-card.md) (the
  liabilities card's full spec — repayment progress, monthly payment),
  [loans.md](./loans.md) (loan balance sign convention),
  [accounts-overview.md](./accounts-overview.md) (PnL chart consumes the same
  history points)
