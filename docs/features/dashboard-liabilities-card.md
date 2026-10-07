# Feature: Dashboard Liabilities Card

> Last updated: 2026-10-05

## Context

Since `LOAN` accounts were introduced as first-class liabilities, the dashboard hero card displayed a "net worth change" label next to the portfolio PnL indicator. Because the PnL is computed from investment accounts only (those with `hasHoldings: true`), the label was misleading — it implied the figure reflected net worth evolution including debt, when it actually represented investment performance.

Additionally, there was no dedicated view of the user's debts on the dashboard. Users had to navigate to individual loan account pages to see amortization progress.

This feature addresses both issues (GitHub issue #18):
1. **Relabel** the hero card PnL indicator to "Portfolio performance"
2. **Add a Liabilities card** to the dashboard with per-loan breakdown

## Design decisions

- The PnL indicator already excludes loans — only the label was wrong. No logic change needed on the hero card.
- A dedicated Liabilities card was preferred over expanding the hero card KPIs, to avoid mixing portfolio metrics with debt metrics in a single zone.
- Dashboard data is enriched server-side (Approach 2) rather than N+1 per-loan calls, to keep the dashboard a single round-trip.
- The Liabilities card is shown only when at least one liability (`LOAN` or `CREDIT_CARD`) exists.
- Loans without a `Debt` row (e.g., Finary-imported loans that have only a balance) display their balance with a subtle "parameters not configured" hint — no CTA button.
- A credit card has no amortisation, so it never shows the repayment line or the hint. It shows its next statement instead (amount due, payment date) when the provider reports it, and nothing extra otherwise (issue #197).

## Changes

### 1. Hero card — label only

In `frontend/src/i18n/locales/fr.json` and `en.json`:
- Replace key `dashboard.netWorthChange` with `dashboard.portfolioPerformance`

No logic change in `DashboardPage.tsx`.

### 2. Backend — `DashboardSummaryDto` enrichment

**New fields on the root DTO:**
```java
BigDecimal totalMonthlyPayment  // null if no loan has a Debt row
```

**New fields on each `LiabilityEntryDto`** (extends existing liability list entry):
```java
BigDecimal monthlyPayment  // null if no Debt row
Double percentPaid         // null if no Debt row — (borrowedAmount - abs(balance)) / borrowedAmount * 100
BigDecimal paymentDueAmountEur  // CREDIT_CARD only: account.paymentDueAmount in EUR, weighted by the member's share
LocalDate paymentDueDate        // CREDIT_CARD only: account.paymentDueDate
```

**`DashboardService` changes:**
1. After building the liabilities list, collect `accountId`s.
2. Fetch in one query: `debtRepository.findByAccountIdIn(liabilityIds)` → `Map<Long, Debt>`.
3. For each liability entry, look up the Debt:
   - If present: compute `monthlyPayment` (stored or formula `M = P·r / (1−(1+r)^-n)`), compute `percentPaid`.
   - If absent: both fields stay null.
4. Sum configured `monthlyPayment` values into `totalMonthlyPayment`.

No `LoanAmortizationService.compute()` call — full schedule computation is too expensive for a dashboard load.

### 3. Frontend — `LiabilitiesCard` component

**File:** `frontend/src/components/shared/LiabilitiesCard.tsx`

**Props:** `liabilities: DashboardLiability[]`, `totalMonthlyPayment?: number | null`

**Layout (shadcn `Card`):**
```
<Card>
  <CardHeader>
    <CardTitle>Liabilities</CardTitle>
    <CardDescription>
      Total: −X € · Y €/month   ← totalMonthlyPayment shown only if non-null
    </CardDescription>
  </CardHeader>
  <CardContent>
    <ItemGroup>
      {liabilities.map(loan => <LoanRow loan={loan} />)}
    </ItemGroup>
  </CardContent>
</Card>
```

**Row detail, chosen by `accountType`:**
- `CREDIT_CARD` → `CardStatement`:
  ```
  [dot] Name                          −X €
  Amount due 912,40 € · Due 5 November 2026
  ```
  Each half renders only when known; with neither, the row is the name and balance alone.
- Anything else → `LoanProgress`.
- Configured loan (`percentPaid != null`):
  ```
  [dot] Name                          −X €
  [====------] 32% · 1 050 €/month
  ```
  - Progress bar: shadcn `<Progress value={percentPaid} />`
  - Monthly + percent on the same row as the bar
- Unconfigured loan:
  ```
  [dot] Name                          −X €
  ⓘ Parameters not configured
  ```
  - The `ⓘ` is a 13px circle with `border: 1px solid muted`, text `text-muted-foreground/40`, italic — not a button, not a link.

**Placement in `DashboardPage.tsx`:**
```tsx
{data.liabilities.length > 0 && (
  <LiabilitiesCard
    liabilities={data.liabilities}
    totalMonthlyPayment={data.totalMonthlyPayment ?? null}
  />
)}
```
Inserted between the Goals card and `<HoldingsCard />`.

### 4. TypeScript types

In `frontend/src/types/api.ts`, `DashboardData.liabilities` is `DashboardLiability[]`. Every
field the backend can leave null is typed optional and nullable (`?: number | null`), because
`spring.jackson.default-property-inclusion: non_null` drops it from the JSON:
`totalMonthlyPayment`, `monthlyPayment`, `percentPaid`, `paymentDueAmountEur`, `paymentDueDate`.

## Data flow

```
DashboardPage mounts
  ↓
useDashboard() → GET /api/dashboard
  ↓
DashboardService.buildDashboard()
  ├─ existing: distribution[], liabilities[], totalNetWorth, totalLiabilities, goals
  └─ new: debtRepository.findByAccountIdIn(liabilityIds)
          → enrich each liability entry with monthlyPayment + percentPaid
          → sum into totalMonthlyPayment
  ↓
DashboardData arrives with enriched liabilities
  ↓
LiabilitiesCard renders if liabilities.length > 0
  └─ per entry:
       CREDIT_CARD          → amount due / due date when known, else nothing
       percentPaid != null  → Progress + monthly
       percentPaid == null  → balance + hint
```

## Key files

| File | Change |
|------|--------|
| `frontend/src/i18n/locales/fr.json` | Rename key `netWorthChange` → `portfolioPerformance` |
| `frontend/src/i18n/locales/en.json` | Same |
| `frontend/src/pages/dashboard/DashboardPage.tsx` | Add `<LiabilitiesCard>` between Goals and Holdings |
| `frontend/src/components/shared/LiabilitiesCard.tsx` | New component |
| `frontend/src/types/api.ts` | Extend `DashboardData` and liabilities entry |
| `backend/.../dto/DashboardSummaryDto.java` | Add `totalMonthlyPayment`, extend liability DTO |
| `backend/.../service/DashboardService.java` | Fetch debts, enrich liability entries |
| `backend/.../controller/DashboardController.java` | No change expected |

## Out of scope

- Debt integration in the Budget feature (separate issue, flagged during design)
- Editing loan parameters from the dashboard (link to account detail page only)
- Finary loans automatically acquiring a Debt row on import

## Gotchas / Pitfalls

- **`totalLiabilities` is already negative** in the existing DTO (loan balances are stored negative). `totalMonthlyPayment` is a positive absolute value (payment amount).
- **`percentPaid` formula uses stored balance**, not the amortization schedule. For Finary loans with manually-updated balances this is accurate enough. For loans with a Debt row, the stored balance is the computed remaining capital (updated daily by the snapshot job), so the formula is consistent.
- **Monthly payment may be stored or computed.** Check `debt.monthlyPayment != null` first; if null, apply `M = P·r / (1−(1+r)^-n)`. This mirrors `LoanAmortizationService` — do not duplicate, extract to a shared method or call the service's helper.
- **Check nullable fields with `!= null`, never `!== null`.** The API omits null fields, so they arrive as `undefined`. A strict `!== null` once sent a credit card down the configured-loan branch and rendered `NaN% · NaN €/mo` (issue #197).
- **`LiabilitiesCard` is a pure presentational component** — it receives data from `DashboardPage`, no API call of its own.
