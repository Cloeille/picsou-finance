import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, CalendarClock, Inbox, PiggyBank, TrendingUp, Wallet } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ErrorState } from '@/components/shared/ErrorState'
import {
  useAllocation,
  useBudgets,
  useCashflow,
  useRecurringCalendar,
  useUncategorized,
} from '@/features/budget/hooks'
import { ColorDot } from './budget-utils'
import { PeriodNavigator } from './PeriodNavigator'
import { useBudgetPeriod } from './BudgetPeriodContext'

function RecapCard({ icon: Icon, label, children, onClick, index = 0 }: {
  icon: LucideIcon
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
      <Card className="h-full transition-colors hover:bg-muted/50">
        <CardContent className="pt-6">
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Icon className="size-4" />
            <span>{label}</span>
            <ArrowRight className="ml-auto size-4 opacity-0 transition-opacity group-hover:opacity-100" />
          </div>
          <div className="mt-2">{children}</div>
        </CardContent>
      </Card>
    </button>
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
        <p className="text-xs text-muted-foreground">{t('budget.overview.pastPeriodNote')}</p>
      )}
      {isError ? (
        <ErrorState message={t('budget.overview.error')} onRetry={refetchAll} />
      ) : isLoading ? (
        <OverviewSkeleton />
      ) : (
      <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <RecapCard icon={TrendingUp} label={t('budget.overview.netThisCycle')} index={0}
          onClick={() => navigate('spending')}>
          <p className={`text-2xl font-bold ${
            (cashflow?.net ?? 0) >= 0
              ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'}`}>
            <CurrencyDisplay value={cashflow?.net ?? 0} showSign />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.incomeVsExpense', {
              income: cashflow ? Math.round(cashflow.income) : 0,
              expense: cashflow ? Math.round(cashflow.expense) : 0,
            })}
          </p>
        </RecapCard>

        {!viewingPast && (
        <RecapCard icon={Wallet} label={t('budget.overview.envelopes')} index={1}
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
        <RecapCard icon={CalendarClock} label={t('budget.overview.upcoming30')} index={2}
          onClick={() => navigate('subscriptions')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={upcomingTotal} showSign />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.upcomingCount', { count: upcoming?.length ?? 0 })}
          </p>
        </RecapCard>
        )}

        <RecapCard icon={PiggyBank} label={t('budget.overview.investable')} index={3}
          onClick={() => navigate('envelopes')}>
          <p className="text-2xl font-bold">
            <CurrencyDisplay value={allocation?.totalStock ?? 0} />
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t('budget.overview.contributedThisCycle', {
              amount: allocation ? Math.round(allocation.totalContributions) : 0,
            })}
          </p>
        </RecapCard>
      </div>

      {/* To-categorize nudge */}
      {(uncategorized?.length ?? 0) > 0 && (
        <button type="button" onClick={() => navigate('review')} className="block w-full text-left">
          <Card className="border-amber-500/40 bg-amber-500/5 transition-colors hover:bg-amber-500/10">
            <CardContent className="flex items-center gap-3 py-4">
              <Inbox className="size-5 text-amber-600 dark:text-amber-400" />
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
          <CardContent className="pt-6">
            <p className="mb-3 text-sm font-medium">{t('budget.overview.topEnvelopes')}</p>
            <div className="space-y-3">
              {topEnvelopes.map((b) => (
                <div key={b.id} className="space-y-1">
                  <div className="flex items-center gap-2 text-sm">
                    <ColorDot color={b.categoryColor} />
                    <span className="truncate">{b.categoryName}</span>
                    <span className="ml-auto tabular-nums text-muted-foreground">{b.percent}%</span>
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-md bg-muted"
                    role="progressbar" aria-label={b.categoryName}
                    aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(b.percent, 100)}
                    aria-valuetext={`${b.percent}%`}>
                    <div className={`h-full rounded-md ${
                      b.overBudget ? 'bg-destructive' : b.percent >= 80 ? 'bg-amber-500' : 'bg-primary'}`}
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
