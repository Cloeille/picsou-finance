import { useMemo, useState } from 'react'
import { NativeSelect } from '@/components/shared/NativeSelect'
import { useTranslation } from 'react-i18next'
import { ArrowDownLeft, ArrowUpRight, CalendarClock, Plus, RefreshCw, Repeat } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NumericInput } from '@/components/shared/NumericInput'
import { DateInput } from '@/components/shared/DateInput'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  useCategories,
  useCreateRecurring,
  useDetectRecurring,
  useRecurring,
  useRecurringCalendar,
} from '@/features/budget/hooks'
import { cn, formatNumber, getLocale, parseAmount, capitalizeFirst } from '@/lib/utils'
import { formatBankLabel } from '@/lib/bank-label'
import type { RecurringCadence, RecurringSeries } from '@/types/api'
import { CADENCE_LABEL_KEY } from './budget-meta'
import { SubscriptionCard } from './SubscriptionCard'
import { ActivityFeed } from './ActivityFeed'

const CADENCES: RecurringCadence[] = ['WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY']

const MONTHLY_FACTOR: Record<RecurringCadence, number> = {
  WEEKLY: 52 / 12,
  BIWEEKLY: 26 / 12,
  MONTHLY: 1,
  QUARTERLY: 1 / 3,
  YEARLY: 1 / 12,
}

function toMonthlyAmount(series: RecurringSeries): number {
  return series.expectedAmount * (MONTHLY_FACTOR[series.cadence] ?? 1)
}

function RecurringForm({ open, onOpenChange }: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const { data: categories } = useCategories()
  const createRecurring = useCreateRecurring()

  const [label, setLabel] = useState('')
  const [counterparty, setCounterparty] = useState('')
  const [amount, setAmount] = useState('')
  const [cadence, setCadence] = useState<RecurringCadence>('MONTHLY')
  const [nextDueDate, setNextDueDate] = useState('')
  const [categoryId, setCategoryId] = useState<number | ''>('')

  function submit() {
    if (label.trim() === '' || amount.trim() === '') return
    createRecurring.mutate({
      label: label.trim(),
      counterparty: counterparty.trim() || undefined,
      expectedAmount: parseAmount(amount),
      cadence,
      nextDueDate: nextDueDate || undefined,
      categoryId: categoryId === '' ? undefined : Number(categoryId),
    }, { onSuccess: () => onOpenChange(false) })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{t('budget.recurring.add')}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="rec-label">{t('budget.recurring.label')}</Label>
            <Input id="rec-label" value={label} onChange={(e) => setLabel(e.target.value)}
              placeholder="Netflix" />
          </div>
          <div className="space-y-2">
            <Label htmlFor="rec-cp">{t('budget.recurring.counterparty')}</Label>
            <Input id="rec-cp" value={counterparty} onChange={(e) => setCounterparty(e.target.value)}
              placeholder="NETFLIX.COM" />
          </div>
          <div className="grid grid-cols-2 items-end gap-4">
            <div className="space-y-2">
              <Label htmlFor="rec-amount">{t('budget.recurring.amount')}</Label>
              <NumericInput id="rec-amount" value={amount}
                onChange={(e) => setAmount(e.target.value)} placeholder="-13.49" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="rec-cadence">{t('budget.recurring.cadence')}</Label>
              <NativeSelect id="rec-cadence" value={cadence}
                onChange={(e) => setCadence(e.target.value as RecurringCadence)}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring">
                {CADENCES.map((c) => (
                  <option key={c} value={c}>{t(CADENCE_LABEL_KEY[c])}</option>
                ))}
              </NativeSelect>
            </div>
          </div>
          <div className="grid grid-cols-2 items-end gap-4">
            <div className="space-y-2">
              <Label htmlFor="rec-due">{t('budget.recurring.nextDue')}</Label>
              <DateInput id="rec-due" value={nextDueDate} onChange={setNextDueDate} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="rec-cat">{t('budget.recurring.category')}</Label>
              <NativeSelect id="rec-cat" value={categoryId}
                onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring">
                <option value="">{t('budget.recurring.noCategory')}</option>
                {(categories ?? []).filter((c) => !c.archived).map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </NativeSelect>
            </div>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}
            disabled={createRecurring.isPending}>
            {t('common.cancel')}
          </Button>
          <Button onClick={submit}
            disabled={createRecurring.isPending || label.trim() === '' || amount.trim() === ''}>
            {createRecurring.isPending ? t('common.loading') : t('common.save')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** Group upcoming occurrences by `yyyy-MM` for a compact agenda. */
function useGroupedCalendar(horizonDays: number) {
  const { data, isLoading, isError, refetch } = useRecurringCalendar(horizonDays)
  const groups = new Map<string, typeof data>()
  for (const occ of data ?? []) {
    const ym = occ.dueDate.slice(0, 7)
    const arr = groups.get(ym) ?? []
    arr.push(occ)
    groups.set(ym, arr)
  }
  return { data: data ?? [], groups, isLoading, isError, refetch, total: data?.length ?? 0 }
}

export function RecurringTab() {
  const { t, i18n } = useTranslation()
  const locale = i18n?.resolvedLanguage ?? i18n?.language ?? getLocale()
  const { data: series, isLoading, isError: seriesError, refetch: refetchSeries } = useRecurring()
  const detect = useDetectRecurring()
  const {
    data: calendarItems,
    groups,
    isLoading: calLoading,
    isError: calError,
    refetch: refetchCal,
    total,
  } = useGroupedCalendar(90)
  const [formOpen, setFormOpen] = useState(false)

  const summary = useMemo(() => {
    const confirmed = (series ?? []).filter((s) => s.status === 'CONFIRMED')
    const expenses = confirmed.filter((s) => s.expectedAmount < 0)
    const income = confirmed.filter((s) => s.expectedAmount > 0)
    const monthlyExpenses = expenses.reduce((sum, s) => sum + Math.abs(toMonthlyAmount(s)), 0)
    const monthlyIncome = income.reduce((sum, s) => sum + toMonthlyAmount(s), 0)

    const cutoff = new Date()
    cutoff.setDate(cutoff.getDate() + 30)
    const cutoffIso = cutoff.toISOString().slice(0, 10)
    const next30 = calendarItems.filter((item) => item.dueDate <= cutoffIso)
    const next30Net = next30.reduce((sum, item) => sum + item.expectedAmount, 0)

    return {
      hasSummary: confirmed.length > 0 || next30.length > 0,
      monthlyExpenses,
      expenseCount: expenses.length,
      monthlyIncome,
      incomeCount: income.length,
      next30Net,
      next30Count: next30.length,
    }
  }, [series, calendarItems])

  return (
    <div className="space-y-4">
      {/* Compact top-aligned KPI summary strip matching OverviewTab */}
      {summary.hasSummary && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Card className="py-3.5">
            <CardContent className="flex h-full flex-col justify-start gap-1.5">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <ArrowUpRight className="size-4 shrink-0" aria-hidden="true" />
                <span>{t('budget.recurring.kpi.expenses')}</span>
              </div>
              <div className="flex items-baseline gap-1.5 tabular-nums">
                <CurrencyDisplay value={-summary.monthlyExpenses} className="text-xl font-bold text-foreground" />
                <span className="text-xs text-muted-foreground">{t('budget.recurring.kpi.perMonth')}</span>
              </div>
              <p className="text-sm text-muted-foreground">
                {t('budget.recurring.kpi.activeCount', { count: summary.expenseCount })}
              </p>
            </CardContent>
          </Card>

          <Card className="py-3.5">
            <CardContent className="flex h-full flex-col justify-start gap-1.5">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <ArrowDownLeft className="size-4 shrink-0" aria-hidden="true" />
                <span>{t('budget.recurring.kpi.income')}</span>
              </div>
              <div className="flex items-baseline gap-1.5 tabular-nums">
                <CurrencyDisplay
                  value={summary.monthlyIncome}
                  showSign
                  className="text-xl font-bold text-emerald-600 dark:text-emerald-400"
                />
                <span className="text-xs text-muted-foreground">{t('budget.recurring.kpi.perMonth')}</span>
              </div>
              <p className="text-sm text-muted-foreground">
                {t('budget.recurring.kpi.activeCount', { count: summary.incomeCount })}
              </p>
            </CardContent>
          </Card>

          <Card className="py-3.5">
            <CardContent className="flex h-full flex-col justify-start gap-1.5">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <CalendarClock className="size-4 shrink-0" aria-hidden="true" />
                <span>{t('budget.recurring.kpi.upcoming30d')}</span>
              </div>
              <CurrencyDisplay
                value={summary.next30Net}
                showSign
                className={cn(
                  'text-xl font-bold tabular-nums',
                  summary.next30Net > 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground',
                )}
              />
              <p className="text-sm text-muted-foreground">
                {t('budget.recurring.kpi.dueCount', { count: summary.next30Count })}
              </p>
            </CardContent>
          </Card>
        </div>
      )}

      {/* Primary card: active & suggested recurring series, with integrated actions */}
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-base">{t('budget.recurring.series')}</CardTitle>
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => detect.mutate()}
              disabled={detect.isPending}
            >
              <RefreshCw className={`size-4 ${detect.isPending ? 'animate-spin' : ''}`} />
              {t('budget.recurring.detect')}
            </Button>
            <Button size="sm" onClick={() => setFormOpen(true)}>
              <Plus className="size-4" />
              {t('budget.recurring.add')}
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {isLoading && <Skeleton className="h-24 w-full rounded-xl" />}
          {!isLoading && seriesError && (
            <ErrorState message={t('budget.recurring.error')} onRetry={() => void refetchSeries()} />
          )}
          {!isLoading && !seriesError && (series?.length ?? 0) === 0 && (
            <EmptyState
              className="py-8"
              icon={<Repeat className="size-8" />}
              title={t('budget.recurring.noSeries')}
              description={t('budget.recurring.noUpcomingHint')}
            />
          )}
          {!isLoading && !seriesError && (series?.length ?? 0) > 0 && (
            <div className="divide-y divide-border/60">
              {series!.map((s) => <SubscriptionCard key={s.id} series={s} />)}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Upcoming schedule */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('budget.recurring.upcoming')}</CardTitle>
        </CardHeader>
        <CardContent>
          {calLoading && <Skeleton className="h-24 w-full rounded-xl" />}
          {!calLoading && calError && (
            <ErrorState message={t('budget.recurring.error')} onRetry={() => void refetchCal()} />
          )}
          {!calLoading && !calError && total === 0 && (
            <EmptyState
              className="py-8"
              icon={<CalendarClock className="size-8" />}
              title={t('budget.recurring.noUpcoming')}
              description={t('budget.recurring.noUpcomingHint')}
            />
          )}
          {!calLoading && !calError && total > 0 && (
            <div className="space-y-5">
              {[...groups.entries()].map(([ym, occs]) => (
                <div key={ym}>
                  <p className="mb-2.5 text-sm font-semibold text-foreground">
                    {capitalizeFirst(
                      new Intl.DateTimeFormat(getLocale(), { month: 'long', year: 'numeric' }).format(
                        new Date(`${ym}-01T00:00:00`),
                      ),
                      getLocale(),
                    )}
                  </p>
                  <div className="divide-y divide-border/60">
                    {(occs ?? []).map((occ) => {
                      const { title, detail } = formatBankLabel(occ.label, locale, (key, opts) => t(key, opts))
                      const rewardSuffix =
                        occ.creditCardPayment && occ.rewardPoints != null
                          ? ` · ${formatNumber(occ.rewardPoints)} ${t('accounts.amex.miles')}`
                          : ''
                      const fullLabel = `${occ.label}${rewardSuffix}`
                      const visibleTitle = `${title}${rewardSuffix}`
                      const isIncome = occ.expectedAmount > 0
                      return (
                        <div
                          key={`${occ.seriesId}-${occ.dueDate}`}
                          className="flex items-center gap-3 py-2.5 first:pt-0 last:pb-0 text-sm"
                        >
                          <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted/60 text-xs font-semibold text-foreground tabular-nums">
                            {new Date(`${occ.dueDate}T00:00:00`).getDate()}
                          </span>
                          <div className="min-w-0 flex-1">
                            <p
                              className="truncate font-medium text-foreground"
                              title={fullLabel}
                              aria-label={fullLabel}
                            >
                              {visibleTitle}
                            </p>
                            {detail && (
                              <p className="truncate text-xs text-muted-foreground">{detail}</p>
                            )}
                          </div>
                          <span
                            className={cn(
                              'shrink-0 font-semibold tabular-nums',
                              isIncome ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground',
                            )}
                          >
                            <CurrencyDisplay value={occ.expectedAmount} showSign />
                          </span>
                        </div>
                      )
                    })}
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Recent automatic detections & price changes (placed unobtrusively below main content) */}
      <ActivityFeed />

      <RecurringForm open={formOpen} onOpenChange={setFormOpen} />
    </div>
  )
}
