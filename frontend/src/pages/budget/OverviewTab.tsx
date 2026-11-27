import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { ArrowRight, Tray } from '@phosphor-icons/react'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ErrorState } from '@/components/shared/ErrorState'
import { useMoney } from '@/hooks/use-money'
import {
  useAllocation,
  useBudgets,
  useCashflow,
  useRecurringCalendar,
  useUncategorized,
} from '@/features/budget/hooks'
import { useUpcomingDividends } from '@/features/dividends/hooks'
import { signedAmountClass } from '@/lib/money'
import { cn, formatDate, formatPercent } from '@/lib/utils'
import { ColorDot } from './budget-utils'
import { PeriodNavigator } from './PeriodNavigator'
import { useBudgetPeriod } from './BudgetPeriodContext'

function RecapCard({ label, children, onClick, index = 0 }: {
  label: string
  children: React.ReactNode
  onClick: () => void
  /** Position in the grid — drives the staggered entrance delay. */
  index?: number
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group text-left animate-in fade-in-0 zoom-in-95 duration-300 fill-mode-both"
      style={{ animationDelay: `${index * 75}ms` }}
    >
      <Card className="h-full py-3.5 transition-colors hover:bg-muted/50">
        <CardContent className="flex h-full flex-col justify-start">
          <span className="truncate text-sm text-muted-foreground">{label}</span>
          <div className="mt-1.5">{children}</div>
        </CardContent>
      </Card>
    </button>
  )
}

/**
 * Dividends and SCPI distributions expected over the next 60 days.
 *
 * Deliberately outside the page's loading/error gate (same idea as the to-categorize
 * nudge): a failed or empty forecast must not replace the recap. Hidden on a past
 * cycle, because the figures are always "from today", not from the period being viewed.
 *
 * The total sums only rows the backend marked `countsInBudget`. Locked wrappers and
 * reinvested SCPI stay in the list, with their own badge, and their sum is a muted
 * aside — the card does not re-decide that from `accountType` or `reinvested`.
 */
function CapitalIncomeAhead() {
  const { t } = useTranslation()
  const dividendsQ = useUpcomingDividends(60)
  const rows = dividendsQ.data ?? []
  const budgetTotal = rows.reduce((sum, row) => sum + (row.countsInBudget ? row.amountEur : 0), 0)
  const lockedTotal = rows.reduce((sum, row) => sum + (row.countsInBudget ? 0 : row.amountEur), 0)
  const quiet = !dividendsQ.isLoading && !dividendsQ.isError && rows.length === 0

  return (
    <Card role="region" aria-labelledby="capital-income-heading">
      <CardContent>
        <span id="capital-income-heading" className="text-sm text-muted-foreground">
          {t('budget.overview.capitalIncome')}
        </span>
        {dividendsQ.isLoading ? (
          <Skeleton className="mt-2 h-8 w-32" />
        ) : dividendsQ.isError ? (
          <p className="mt-2 text-sm text-muted-foreground">{t('accounts.dividends.error')}</p>
        ) : quiet ? (
          <p className="mt-2 text-sm text-muted-foreground">{t('budget.overview.capitalIncomeEmpty')}</p>
        ) : (
          <>
            <p className={cn('mt-2 text-2xl font-bold', signedAmountClass(budgetTotal))}>
              <CurrencyDisplay value={budgetTotal} showSign />
            </p>
            <p className="mt-1 text-xs text-muted-foreground">{t('budget.overview.capitalIncomeGross')}</p>
            {lockedTotal > 0 && (
              <p className="mt-1 text-xs text-muted-foreground">
                <span className={signedAmountClass(lockedTotal)}>
                  <CurrencyDisplay value={lockedTotal} showSign />
                </span>
                {' '}
                {t('budget.overview.capitalizedOutside')}
              </p>
            )}
            <ul className="mt-3 space-y-2">
              {rows.slice(0, 3).map((row) => (
                <li key={row.id} className="flex items-center gap-2 text-sm">
                  <span className="shrink-0 tabular-nums text-muted-foreground">{formatDate(row.expectedDate)}</span>
                  <span className="truncate">{row.name}</span>
                  {row.reinvested && (
                    <Badge variant="secondary">{t('budget.overview.reinvested')}</Badge>
                  )}
                  {!row.reinvested && !row.countsInBudget && (
                    <Badge variant="outline">{t('budget.overview.staysInEnvelope')}</Badge>
                  )}
                  <span className="ml-auto shrink-0 tabular-nums">
                    <CurrencyDisplay value={row.amountEur} showSign colorBySign="inflow" />
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** First-load skeleton mirroring the 4-card recap grid so the layout doesn't jump. */
function OverviewSkeleton() {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {Array.from({ length: 4 }).map((_, i) => (
        <Skeleton key={i} className="h-28 w-full rounded-xl" />
      ))}
    </div>
  )
}

export function OverviewTab() {
  const { t } = useTranslation()
  // Recap cards deep-link into the nested budget routes (relative to `/budget`).
  const navigate = useNavigate()
  const money = useMoney()
  const { anchor, setAnchor } = useBudgetPeriod()
  const cashflowQ = useCashflow('CYCLE', anchor)
  const budgetsQ = useBudgets()
  const upcomingQ = useRecurringCalendar(30)
  const allocationQ = useAllocation('CYCLE', anchor)
  const uncategorizedQ = useUncategorized()

  const cashflow = cashflowQ.data
  const budgets = budgetsQ.data
  const upcoming = upcomingQ.data
  const allocation = allocationQ.data
  const uncategorized = uncategorizedQ.data

  // Envelope-usage and upcoming-charges data are always server-computed for the *current*
  // cycle, so they can't follow the period navigator's anchor. When the displayed cycle has
  // already ended (its `to` is before today), hide those cards to avoid mixing past + present.
  const todayIso = new Date().toLocaleDateString('en-CA')
  const viewingPast = !!cashflowQ.data?.to && cashflowQ.data.to < todayIso

  // The four recap cards are the page's core; the to-categorize nudge is a non-blocking extra,
  // so its query is deliberately excluded from the loading/error gate below.
  const coreQueries = [cashflowQ, budgetsQ, upcomingQ, allocationQ]
  const isLoading = coreQueries.some((q) => q.isLoading)
  const isError = coreQueries.some((q) => q.isError)
  const refetchAll = () => coreQueries.forEach((q) => void q.refetch())

  const totalSpent = (budgets ?? []).reduce((s, b) => s + b.spent, 0)
  const totalLimit = (budgets ?? []).reduce((s, b) => s + b.monthlyLimit, 0)
  const overCount = (budgets ?? []).filter((b) => b.overBudget).length
  const upcomingTotal = (upcoming ?? []).reduce((s, o) => s + o.expectedAmount, 0)
  const topEnvelopes = [...(budgets ?? [])].sort((a, b) => b.percent - a.percent).slice(0, 4)

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <PeriodNavigator
          period="CYCLE"
          from={cashflowQ.data?.from}
          to={cashflowQ.data?.to}
          onAnchorChange={setAnchor}
        />
      </div>
      {viewingPast && (
        <p className="text-sm text-muted-foreground">{t('budget.overview.pastPeriodNote')}</p>
      )}
      {isError ? (
        <ErrorState message={t('budget.overview.error')} onRetry={refetchAll} />
      ) : isLoading ? (
        <OverviewSkeleton />
      ) : (
      <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <RecapCard label={t('budget.overview.netThisCycle')} index={0}
          onClick={() => navigate('spending')}>
          <p className={cn('text-2xl font-bold', signedAmountClass(cashflow?.net ?? 0))}>
            <CurrencyDisplay value={cashflow?.net ?? 0} showSign />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.incomeVsExpense', {
              income: money.amount(cashflow ? Math.round(cashflow.income) : 0, 'EUR', { maximumFractionDigits: 0 }),
              expense: money.amount(cashflow ? Math.round(cashflow.expense) : 0, 'EUR', { maximumFractionDigits: 0 }),
            })}
          </p>
        </RecapCard>

        {!viewingPast && (
        <RecapCard label={t('budget.overview.envelopes')} index={1}
          onClick={() => navigate('envelopes')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={totalSpent} /> <span className="text-sm text-muted-foreground">/ <CurrencyDisplay value={totalLimit} /></span>
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {overCount > 0
              ? t('budget.overview.overBudget', { count: overCount })
              : t('budget.overview.onTrack')}
          </p>
        </RecapCard>
        )}

        {!viewingPast && (
        <RecapCard label={t('budget.overview.upcoming30')} index={2}
          onClick={() => navigate('subscriptions')}>
          <p className={cn('text-2xl font-bold', signedAmountClass(upcomingTotal))}>
            <CurrencyDisplay value={upcomingTotal} showSign />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.upcomingCount', { count: upcoming?.length ?? 0 })}
          </p>
        </RecapCard>
        )}

        <RecapCard label={t('budget.overview.investable')} index={3}
          onClick={() => navigate('envelopes')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={allocation?.totalStock ?? 0} />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.contributedThisCycle', {
              amount: money.amount(allocation ? Math.round(allocation.totalContributions) : 0, 'EUR', { maximumFractionDigits: 0 }),
            })}
          </p>
        </RecapCard>
      </div>

      {/* Capital-income forecast — current window only, and never part of the core gate. */}
      {!viewingPast && <CapitalIncomeAhead />}

      {/* To-categorize nudge */}
      {(uncategorized?.length ?? 0) > 0 && (
        <button type="button" onClick={() => navigate('review')} className="block w-full text-left">
          <Card className="border-border/80 bg-card py-3 transition-colors hover:bg-muted/40">
            <CardContent className="flex items-center gap-3">
              <span className="inline-flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground">
                <Tray className="size-4" />
              </span>
              <span className="text-sm font-medium">
                {t('budget.overview.toCategorize', { count: uncategorized!.length })}
              </span>
              <ArrowRight className="ml-auto size-4 text-muted-foreground" />
            </CardContent>
          </Card>
        </button>
      )}

      {/* Top envelopes preview — envelope-budget data, current cycle only (see viewingPast). */}
      {!viewingPast && topEnvelopes.length > 0 && (
        <Card>
          <CardContent>
            <p className="mb-3 text-sm font-medium">{t('budget.overview.topEnvelopes')}</p>
            <div className="space-y-3">
              {topEnvelopes.map((b) => (
                <div key={b.id} className="space-y-1">
                  <div className="flex items-center gap-2 text-sm">
                    <ColorDot color={b.categoryColor} />
                    <span className="truncate">{b.categoryName}</span>
                    <span className="ml-auto tabular-nums text-muted-foreground">{formatPercent(b.percent, { fractionDigits: 0 })}</span>
                  </div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-muted"
                    role="progressbar" aria-label={b.categoryName}
                    aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(b.percent, 100)}
                    aria-valuetext={formatPercent(b.percent, { fractionDigits: 0 })}>
                    <div className={`h-full rounded-full ${
                      b.overBudget ? 'bg-destructive' : b.percent >= 80 ? 'bg-foreground/75' : 'bg-primary'}`}
                      style={{ width: `${Math.min(b.percent, 100)}%` }} />
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
      </>
      )}
    </div>
  )
}
