# Dashboard Liabilities Card — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Liabilities card to the dashboard and fix the hero card PnL label, so investment performance and debt are never visually conflated.

**Architecture:** Enrich `DashboardResponse` with a new `LiabilityEntry` record (adds `monthlyPayment` + `percentPaid` to each loan entry), computed in `DashboardService` via a single `debtRepository.findByAccountIdIn()` call. A new frontend `LiabilitiesCard` component consumes those fields.

**Tech Stack:** Java 21 / Spring Boot 3.4.9 / Maven · React 19 / TypeScript / Vite / shadcn/ui · react-i18next

## Global Constraints

- Branch: `1.1.0` — commit directly, no feature branch
- Version bump on final commit: `backend/pom.xml` L17 and `frontend/package.json` (currently 1.1.3 → 1.1.4)
- Use `mvn` not `./mvnw`
- No `Co-Authored-By` trailer in commits
- All code and comments in English; translations in `fr.json`/`en.json`
- Frontend must be mobile-responsive (Tailwind breakpoints, flex-wrap)
- Final typecheck: `cd frontend && bun install && ./node_modules/.bin/tsc -b` (no system `tsc`)
- Conventional commits: `feat(scope):`, `fix(scope):`, `refactor(scope):`, `test(scope):`, `docs:`

---

### Task 1: Backend — LiabilityEntry DTO + DebtRepository + DashboardService enrichment

**Files:**
- Modify: `backend/src/main/java/com/picsou/dto/DashboardResponse.java`
- Modify: `backend/src/main/java/com/picsou/repository/DebtRepository.java`
- Modify: `backend/src/main/java/com/picsou/service/LoanAmortizationService.java`
- Modify: `backend/src/main/java/com/picsou/service/DashboardService.java`
- Create: `backend/src/test/java/com/picsou/service/DashboardServiceLiabilityTest.java`

**Interfaces:**
- Produces: `DashboardResponse.liabilities` typed as `List<LiabilityEntry>` where `LiabilityEntry` adds `monthlyPayment: BigDecimal` (nullable) and `percentPaid: Double` (nullable)
- Produces: `DashboardResponse.totalMonthlyPayment: BigDecimal` (nullable)

- [ ] **Step 1: Add `LiabilityEntry` record and `totalMonthlyPayment` to `DashboardResponse`**

In `DashboardResponse.java`, add inside the `DashboardResponse` class body:

```java
public record LiabilityEntry(
    Long accountId,
    String name,
    String color,
    BigDecimal balanceEur,
    double percentage,
    String accountType,
    boolean hasHoldings,
    BigDecimal monthlyPayment,
    Double percentPaid
) {}
```

Change the root record signature: replace `List<DistributionItem> liabilities` with `List<LiabilityEntry> liabilities` and add `BigDecimal totalMonthlyPayment` after `totalLiabilities`:

```java
public record DashboardResponse(
    BigDecimal totalNetWorth,
    BigDecimal totalLiabilities,
    BigDecimal totalMonthlyPayment,      // null if no loan has a Debt row
    List<NetWorthPoint> netWorthHistory,
    List<DistributionItem> distribution,
    List<LiabilityEntry> liabilities,
    List<GoalProgressResponse> goalSummaries
) {
```

- [ ] **Step 2: Add `findByAccountIdIn` to `DebtRepository`**

In `DebtRepository.java`:

```java
List<Debt> findByAccountIdIn(Collection<Long> accountIds);
```

Add `import java.util.Collection;` if not already present.

- [ ] **Step 3: Add `resolveMonthlyPayment` to `LoanAmortizationService`**

In `LoanAmortizationService.java`, add this public method (it can call the existing private `computeMonthlyPayment`):

```java
/**
 * Returns the stored monthly payment or derives it from the standard
 * amortization formula. Does NOT run the full schedule computation.
 */
public BigDecimal resolveMonthlyPayment(Debt debt) {
    if (debt.getMonthlyPayment() != null) {
        return debt.getMonthlyPayment().setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal principal = nz(debt.getBorrowedAmount());
    BigDecimal monthlyRate = nz(debt.getInterestRate()).divide(TWELVE, MC);
    int n = computeTotalInstallments(debt.getStartDate(), debt.getEndDate());
    return computeMonthlyPayment(principal, monthlyRate, n).setScale(2, RoundingMode.HALF_UP);
}
```

- [ ] **Step 4: Write the failing test for loan enrichment**

Create `DashboardServiceLiabilityTest.java`. This test verifies that `getDashboard` enriches liabilities correctly when a `Debt` row exists, and returns nulls when it doesn't.

```java
package com.picsou.service;

import com.picsou.dto.DashboardResponse;
import com.picsou.model.*;
import com.picsou.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceLiabilityTest {

    @Mock AccountRepository accountRepository;
    @Mock GoalService goalService;
    @Mock GoalRepository goalRepository;
    @Mock PriceService priceService;
    @Mock AccountHoldingRepository holdingRepository;
    @Mock HistoryService historyService;
    @Mock DebtRepository debtRepository;
    @Mock LoanAmortizationService loanAmortizationService;

    DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
            accountRepository, goalService, goalRepository,
            priceService, holdingRepository, historyService,
            debtRepository, loanAmortizationService
        );
    }

    @Test
    void liability_with_debt_row_gets_monthlyPayment_and_percentPaid() {
        FamilyMember member = new FamilyMember();
        member.setId(1L);

        Account loan = new Account();
        loan.setId(10L);
        loan.setName("Mortgage");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-80000"));
        loan.setCurrency("EUR");
        loan.setColor("#6366f1");

        Debt debt = new Debt();
        debt.setBorrowedAmount(new BigDecimal("100000"));
        debt.setMonthlyPayment(new BigDecimal("800"));
        debt.setInterestRate(new BigDecimal("0.015"));
        debt.setStartDate(LocalDate.of(2022, 1, 1));
        debt.setEndDate(LocalDate.of(2037, 1, 1));

        when(accountRepository.findByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(10L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(10L))).thenReturn(List.of(debt));
        when(loanAmortizationService.resolveMonthlyPayment(debt)).thenReturn(new BigDecimal("800.00"));
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.monthlyPayment()).isEqualByComparingTo("800.00");
        assertThat(entry.percentPaid()).isNotNull();
        // borrowedAmount=100000, remaining=80000 → 20% paid
        assertThat(entry.percentPaid()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(result.totalMonthlyPayment()).isEqualByComparingTo("800.00");
    }

    @Test
    void liability_without_debt_row_gets_null_fields() {
        FamilyMember member = new FamilyMember();
        member.setId(1L);

        Account loan = new Account();
        loan.setId(11L);
        loan.setName("Finary loan");
        loan.setType(AccountType.LOAN);
        loan.setCurrentBalance(new BigDecimal("-15000"));
        loan.setCurrency("EUR");
        loan.setColor("#f97316");

        when(accountRepository.findByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(loan));
        when(holdingRepository.findByAccount_Id(11L)).thenReturn(List.of());
        when(priceService.toEur(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(debtRepository.findByAccountIdIn(List.of(11L))).thenReturn(List.of());
        when(historyService.buildHistory(any(), any(Integer.class), any())).thenReturn(List.of());
        when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(1L)).thenReturn(List.of());

        DashboardResponse result = dashboardService.getDashboard(1L, null);

        assertThat(result.liabilities()).hasSize(1);
        DashboardResponse.LiabilityEntry entry = result.liabilities().get(0);
        assertThat(entry.monthlyPayment()).isNull();
        assertThat(entry.percentPaid()).isNull();
        assertThat(result.totalMonthlyPayment()).isNull();
    }
}
```

- [ ] **Step 5: Run tests — expect compile failure**

```bash
cd backend && mvn test -Dtest=DashboardServiceLiabilityTest 2>&1 | tail -20
```

Expected: compile error — `DashboardService` constructor does not yet accept `DebtRepository` and `LoanAmortizationService`.

- [ ] **Step 6: Update `DashboardService` — inject deps + enrich liabilities**

Add `DebtRepository` and `LoanAmortizationService` to the constructor:

```java
private final DebtRepository debtRepository;
private final LoanAmortizationService loanAmortizationService;

public DashboardService(
    AccountRepository accountRepository,
    GoalService goalService,
    GoalRepository goalRepository,
    PriceService priceService,
    AccountHoldingRepository holdingRepository,
    HistoryService historyService,
    DebtRepository debtRepository,
    LoanAmortizationService loanAmortizationService
) {
    this.accountRepository = accountRepository;
    this.goalService = goalService;
    this.goalRepository = goalRepository;
    this.priceService = priceService;
    this.holdingRepository = holdingRepository;
    this.historyService = historyService;
    this.debtRepository = debtRepository;
    this.loanAmortizationService = loanAmortizationService;
}
```

Add the missing imports:
```java
import com.picsou.model.Debt;
import com.picsou.repository.DebtRepository;
```

Replace the `buildDistribution` call for liabilities and the `return` statement with the enrichment logic. Find this block at the end of `getDashboard`:

```java
List<DistributionItem> liabilities = buildDistribution(accounts, totalNetWorth, holdingsByAccount, true);

List<GoalProgressResponse> goals = ...

return new DashboardResponse(totalNetWorth, totalLiabilities, updatedHistory, distribution, liabilities, goals);
```

Replace with:

```java
List<DistributionItem> rawLiabilities = buildDistribution(accounts, totalNetWorth, holdingsByAccount, true);

// Enrich liabilities with loan parameters in one query
List<Long> liabilityIds = rawLiabilities.stream().map(DistributionItem::accountId).toList();
Map<Long, Debt> debtByAccountId = debtRepository.findByAccountIdIn(liabilityIds).stream()
    .collect(java.util.stream.Collectors.toMap(d -> d.getAccount().getId(), d -> d));

BigDecimal totalMonthlyPayment = null;
List<DashboardResponse.LiabilityEntry> liabilities = new ArrayList<>();
for (DistributionItem item : rawLiabilities) {
    Debt debt = debtByAccountId.get(item.accountId());
    BigDecimal monthlyPayment = null;
    Double percentPaid = null;
    if (debt != null) {
        monthlyPayment = loanAmortizationService.resolveMonthlyPayment(debt);
        BigDecimal borrowed = debt.getBorrowedAmount();
        if (borrowed != null && borrowed.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal remaining = item.balanceEur().abs();
            BigDecimal repaid = borrowed.subtract(remaining);
            percentPaid = repaid.divide(borrowed, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
            percentPaid = Math.max(0.0, Math.min(100.0, percentPaid));
        }
        totalMonthlyPayment = (totalMonthlyPayment == null ? BigDecimal.ZERO : totalMonthlyPayment)
            .add(monthlyPayment);
    }
    liabilities.add(new DashboardResponse.LiabilityEntry(
        item.accountId(), item.name(), item.color(), item.balanceEur(),
        item.percentage(), item.accountType(), item.hasHoldings(),
        monthlyPayment, percentPaid
    ));
}

List<GoalProgressResponse> goals = goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
    .map(goalService::toProgressResponse)
    .toList();

return new DashboardResponse(totalNetWorth, totalLiabilities, totalMonthlyPayment,
    updatedHistory, distribution, liabilities, goals);
```

- [ ] **Step 7: Run tests — expect pass**

```bash
cd backend && mvn test -Dtest=DashboardServiceLiabilityTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, 2 tests passing.

- [ ] **Step 8: Run full backend test suite**

```bash
cd backend && mvn test 2>&1 | tail -15
```

Expected: `BUILD SUCCESS`. If any test fails due to the `DashboardResponse` constructor change, update the failing test's `new DashboardResponse(...)` call to include the new `totalMonthlyPayment` parameter (pass `null`).

- [ ] **Step 9: Commit**

```bash
git add backend/src/
git commit -m "feat(dashboard): enrich liability entries with monthlyPayment and percentPaid"
```

---

### Task 2: Frontend — types, i18n, hero label fix

**Files:**
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/i18n/locales/fr.json`
- Modify: `frontend/src/i18n/locales/en.json`
- Modify: `frontend/src/pages/dashboard/DashboardPage.tsx`

**Interfaces:**
- Produces: `DashboardData.liabilities[n].monthlyPayment: number | null` and `.percentPaid: number | null`
- Produces: `DashboardData.totalMonthlyPayment: number | null`
- Produces: i18n key `dashboard.portfolioPerformance` (replaces `dashboard.netWorthChange`)

- [ ] **Step 1: Extend `DashboardData` in `api.ts`**

In `frontend/src/types/api.ts`, find the `DashboardData` interface. Extend the `liabilities` array item type and add `totalMonthlyPayment`:

```ts
export interface DashboardData {
  totalNetWorth: number
  totalLiabilities: number
  totalMonthlyPayment: number | null           // new
  netWorthHistory: { date: string; total: number; invested: number; pnl: number }[]
  distribution: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
  }[]
  liabilities: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
    monthlyPayment: number | null              // new
    percentPaid: number | null                 // new
  }[]
  goalSummaries: GoalProgress[]
}
```

- [ ] **Step 2: Rename i18n key in both locale files**

In `frontend/src/i18n/locales/fr.json`, find:
```json
"netWorthChange": "gain / perte",
```
Replace with:
```json
"portfolioPerformance": "performance portefeuille",
```

In `frontend/src/i18n/locales/en.json`, find the equivalent key and replace:
```json
"portfolioPerformance": "portfolio performance",
```

- [ ] **Step 3: Update `DashboardPage.tsx` to use the new key**

In `frontend/src/pages/dashboard/DashboardPage.tsx`, find the line:
```tsx
<span className="text-sm text-muted-foreground">{t('dashboard.netWorthChange')}</span>
```
Replace with:
```tsx
<span className="text-sm text-muted-foreground">{t('dashboard.portfolioPerformance')}</span>
```

- [ ] **Step 4: Typecheck**

```bash
cd frontend && ./node_modules/.bin/tsc -b 2>&1 | tail -5
```

Expected: no output (zero errors).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/api.ts frontend/src/i18n/ frontend/src/pages/dashboard/DashboardPage.tsx
git commit -m "fix(dashboard): rename netWorthChange → portfolioPerformance label"
```

---

### Task 3: Frontend — `LiabilitiesCard` component

**Files:**
- Create: `frontend/src/components/shared/LiabilitiesCard.tsx`
- Create: `frontend/src/components/shared/LiabilitiesCard.test.tsx`

**Interfaces:**
- Consumes: `DashboardData['liabilities']` (with `monthlyPayment` and `percentPaid` from Task 2)
- Consumes: `totalMonthlyPayment: number | null`
- Produces: `<LiabilitiesCard liabilities={...} totalMonthlyPayment={...} />` — exported named component

- [ ] **Step 1: Write the failing test**

Create `frontend/src/components/shared/LiabilitiesCard.test.tsx`:

```tsx
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LiabilitiesCard } from './LiabilitiesCard'

const baseLoan = {
  accountId: 1,
  name: 'Mortgage BNP',
  color: '#6366f1',
  balanceEur: -118200,
  percentage: 0,
  accountType: 'LOAN',
  hasHoldings: false,
}

describe('LiabilitiesCard', () => {
  it('renders loan name and balance', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: null, percentPaid: null }]}
        totalMonthlyPayment={null}
      />
    )
    expect(screen.getByText('Mortgage BNP')).toBeInTheDocument()
  })

  it('renders progress bar when percentPaid is present', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: 1050, percentPaid: 32 }]}
        totalMonthlyPayment={1050}
      />
    )
    const bar = document.querySelector('[role="progressbar"]')
    expect(bar).not.toBeNull()
  })

  it('shows hint icon when loan has no parameters', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: null, percentPaid: null }]}
        totalMonthlyPayment={null}
      />
    )
    // The ⓘ hint is rendered as an aria-label
    expect(screen.getByLabelText('Parameters not configured')).toBeInTheDocument()
  })

  it('shows monthlyPayment section in header when totalMonthlyPayment is non-null', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: 1050, percentPaid: 32 }]}
        totalMonthlyPayment={1050}
      />
    )
    // The i18n key "dashboard.monthlyPayment" resolves to its key in test env
    expect(screen.getByText(/dashboard\.monthlyPayment|Monthly payment|Mensualité/i)).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run tests — expect failure**

```bash
cd frontend && bunx vitest run src/components/shared/LiabilitiesCard.test.tsx 2>&1 | tail -10
```

Expected: FAIL — `LiabilitiesCard` not found.

- [ ] **Step 3: Implement `LiabilitiesCard.tsx`**

Create `frontend/src/components/shared/LiabilitiesCard.tsx`:

```tsx
import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { DashboardData } from '@/types/api'

interface Props {
  liabilities: DashboardData['liabilities']
  totalMonthlyPayment: number | null
}

export function LiabilitiesCard({ liabilities, totalMonthlyPayment }: Props) {
  const { t } = useTranslation()
  const totalDebt = liabilities.reduce((sum, l) => sum + l.balanceEur, 0)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('dashboard.liabilities')}</CardTitle>
        <CardDescription className="flex flex-wrap gap-x-4 gap-y-1">
          <span>
            {t('dashboard.totalLiabilities')}:{' '}
            <span className="font-medium text-destructive">
              <CurrencyDisplay value={totalDebt} />
            </span>
          </span>
          {totalMonthlyPayment !== null && (
            <span>
              {t('dashboard.monthlyPayment')}:{' '}
              <span className="font-medium text-foreground">
                <CurrencyDisplay value={totalMonthlyPayment} />/mo
              </span>
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {liabilities.map((loan) => (
          <div
            key={loan.accountId}
            className="flex flex-col gap-1.5 rounded-2xl bg-muted/40 px-4 py-3"
          >
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2 min-w-0">
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ background: loan.color }}
                />
                <span className="truncate text-sm font-medium">{loan.name}</span>
              </div>
              <span className="shrink-0 text-sm font-semibold text-destructive">
                <CurrencyDisplay value={loan.balanceEur} />
              </span>
            </div>

            {loan.percentPaid !== null ? (
              <div className="flex items-center gap-2">
                <Progress
                  value={loan.percentPaid}
                  className="h-1.5 flex-1 [&_[data-slot=progress-indicator]]:bg-primary/60"
                />
                <span className="shrink-0 text-xs text-muted-foreground">
                  {Math.round(loan.percentPaid)}%
                  {loan.monthlyPayment !== null && (
                    <> · <CurrencyDisplay value={loan.monthlyPayment} />/mo</>
                  )}
                </span>
              </div>
            ) : (
              <div className="flex items-center gap-1.5">
                <span
                  aria-label="Parameters not configured"
                  className="flex size-3.5 shrink-0 items-center justify-center rounded-full border border-muted-foreground/30 text-[9px] text-muted-foreground/40"
                >
                  i
                </span>
                <span className="text-xs italic text-muted-foreground/50">
                  {t('dashboard.loanParamsUnconfigured')}
                </span>
              </div>
            )}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
```

- [ ] **Step 4: Add i18n keys for the new component**

In `frontend/src/i18n/locales/fr.json`, in the `dashboard` object add:
```json
"liabilities": "Dettes",
"monthlyPayment": "Mensualité totale",
"loanParamsUnconfigured": "Paramètres non renseignés"
```

In `frontend/src/i18n/locales/en.json`:
```json
"liabilities": "Liabilities",
"monthlyPayment": "Monthly payment",
"loanParamsUnconfigured": "Parameters not configured"
```

- [ ] **Step 5: Run tests — expect pass**

```bash
cd frontend && bunx vitest run src/components/shared/LiabilitiesCard.test.tsx 2>&1 | tail -10
```

Expected: all 4 tests pass.

- [ ] **Step 6: Typecheck**

```bash
cd frontend && ./node_modules/.bin/tsc -b 2>&1 | tail -5
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/shared/LiabilitiesCard.tsx \
        frontend/src/components/shared/LiabilitiesCard.test.tsx \
        frontend/src/i18n/
git commit -m "feat(dashboard): add LiabilitiesCard component"
```

---

### Task 4: Frontend — DashboardPage integration + version bump

**Files:**
- Modify: `frontend/src/pages/dashboard/DashboardPage.tsx`
- Modify: `backend/pom.xml` (version bump)
- Modify: `frontend/package.json` (version bump)

**Interfaces:**
- Consumes: `<LiabilitiesCard>` from Task 3
- Consumes: `data.liabilities` and `data.totalMonthlyPayment` from `useDashboard()`

- [ ] **Step 1: Import and insert `LiabilitiesCard` in `DashboardPage`**

In `frontend/src/pages/dashboard/DashboardPage.tsx`:

Add the import at the top with the other shared component imports:
```tsx
import { LiabilitiesCard } from '@/components/shared/LiabilitiesCard'
```

Find the Goals card closing tag followed by `<HoldingsCard />`:
```tsx
      </Card>

      {/* Holdings overview */}
      <HoldingsCard />
```

Replace with:
```tsx
      </Card>

      {/* Liabilities overview */}
      {data.liabilities.length > 0 && (
        <LiabilitiesCard
          liabilities={data.liabilities}
          totalMonthlyPayment={data.totalMonthlyPayment ?? null}
        />
      )}

      {/* Holdings overview */}
      <HoldingsCard />
```

- [ ] **Step 2: Typecheck**

```bash
cd frontend && ./node_modules/.bin/tsc -b 2>&1 | tail -5
```

Expected: no output (zero errors).

- [ ] **Step 3: Bump version to 1.1.4**

In `backend/pom.xml`, find `<version>1.1.3</version>` (around line 17) and change to `<version>1.1.4</version>`.

In `frontend/package.json`, find `"version": "1.1.3"` and change to `"version": "1.1.4"`.

- [ ] **Step 4: Final commit**

```bash
git add frontend/src/pages/dashboard/DashboardPage.tsx backend/pom.xml frontend/package.json
git commit -m "feat(dashboard): integrate LiabilitiesCard; bump 1.1.4"
```

---

### Post-implementation checklist

- [ ] `mvn test` passes in `backend/`
- [ ] `./node_modules/.bin/tsc -b` passes in `frontend/`
- [ ] `bunx vitest run` passes in `frontend/`
- [ ] Dashboard loads without JS errors when no LOAN accounts exist (card hidden)
- [ ] Dashboard loads with a LOAN account that has a Debt row (progress bar visible)
- [ ] Dashboard loads with a Finary-imported LOAN (hint icon visible, no progress bar)
- [ ] Update `docs/features/dashboard-liabilities-card.md`: set "Last updated" to implementation date
