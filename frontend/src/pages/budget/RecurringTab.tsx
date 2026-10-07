import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CalendarClock, Plus, RefreshCw } from 'lucide-react'
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
  DialogDescription,
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
import { getLocale, parseAmount } from '@/lib/utils'
import type { RecurringCadence } from '@/types/api'
import { ColorDot } from './budget-utils'
import { CADENCE_LABEL_KEY } from './budget-meta'
import { SubscriptionCard } from './SubscriptionCard'
import { ActivityFeed } from './ActivityFeed'

const CADENCES: RecurringCadence[] = ['WEEKLY', 'BIWEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY']

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
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{t('budget.recurring.add')}</DialogTitle>
          <DialogDescription>{t('budget.recurring.formHint')}</DialogDescription>
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
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="rec-amount">{t('budget.recurring.amount')}</Label>
              <NumericInput id="rec-amount" value={amount}
                onChange={(e) => setAmount(e.target.value)} placeholder="-13.49" />
            </div>
            <div className="space-y-2">
              <Label htmlFor="rec-cadence">{t('budget.recurring.cadence')}</Label>
              <select id="rec-cadence" value={cadence}
                onChange={(e) => setCadence(e.target.value as RecurringCadence)}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring">
                {CADENCES.map((c) => (
                  <option key={c} value={c}>{t(CADENCE_LABEL_KEY[c])}</option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="rec-due">{t('budget.recurring.nextDue')}</Label>
              <DateInput id="rec-due" value={nextDueDate} onChange={setNextDueDate} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="rec-cat">{t('budget.recurring.category')}</Label>
              <select id="rec-cat" value={categoryId}
                onChange={(e) => setCategoryId(e.target.value === '' ? '' : Number(e.target.value))}
                className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus:border-ring">
                <option value="">{t('budget.recurring.noCategory')}</option>
                {(categories ?? []).filter((c) => !c.archived).map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
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
  return { groups, isLoading, isError, refetch, total: data?.length ?? 0 }
}

export function RecurringTab() {
  const { t } = useTranslation()
  const { data: series, isLoading, isError: seriesError, refetch: refetchSeries } = useRecurring()
  const detect = useDetectRecurring()
  const { groups, isLoading: calLoading, isError: calError, refetch: refetchCal, total } = useGroupedCalendar(90)
  const [formOpen, setFormOpen] = useState(false)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{t('budget.recurring.subtitle')}</p>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => detect.mutate()}
            disabled={detect.isPending}>
            <RefreshCw className={`size-4 ${detect.isPending ? 'animate-spin' : ''}`} />
            {t('budget.recurring.detect')}
          </Button>
          <Button size="sm" onClick={() => setFormOpen(true)}>
            <Plus className="size-4" /> {t('budget.recurring.add')}
          </Button>
        </div>
      </div>

      {/* "What changed" — silent auto-confirms + price steps, with per-item undo. */}
      <ActivityFeed />

      {/* Upcoming agenda */}
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
            <EmptyState icon={<CalendarClock className="size-10" />}
              title={t('budget.recurring.noUpcoming')}
              description={t('budget.recurring.noUpcomingHint')} />
          )}
          {!calLoading && !calError && total > 0 && (
            <div className="space-y-4">
              {[...groups.entries()].map(([ym, occs]) => (
                <div key={ym}>
                  <p className="mb-2 text-xs font-semibold uppercase text-muted-foreground">
                    {new Intl.DateTimeFormat(getLocale(), { month: 'long', year: 'numeric' })
                      .format(new Date(`${ym}-01T00:00:00`))}
                  </p>
                  <div className="space-y-2">
                    {(occs ?? []).map((occ) => {
                      const displayLabel = `${occ.label}${occ.creditCardPayment && occ.rewardPoints != null ? ` · ${occ.rewardPoints.toLocaleString()} ${t('accounts.amex.miles')}` : ''}`
                      return (
                      <div key={`${occ.seriesId}-${occ.dueDate}`}
                        className="flex items-center gap-3 text-sm">
                        <span className="w-10 shrink-0 text-center text-muted-foreground">
                          {new Date(`${occ.dueDate}T00:00:00`).getDate()}
                        </span>
                        <ColorDot color={occ.categoryColor} />
                        <span className="min-w-0 flex-1 truncate" title={displayLabel} aria-label={displayLabel}>{displayLabel}</span>
                        <span className="shrink-0 tabular-nums">
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

      {/* Series management */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('budget.recurring.series')}</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading && <Skeleton className="h-24 w-full rounded-xl" />}
          {!isLoading && seriesError && (
            <ErrorState message={t('budget.recurring.error')} onRetry={() => void refetchSeries()} />
          )}
          {!isLoading && !seriesError && (series?.length ?? 0) === 0 && (
            <p className="py-6 text-center text-sm text-muted-foreground">
              {t('budget.recurring.noSeries')}
            </p>
          )}
          {!isLoading && !seriesError && (series?.length ?? 0) > 0 && (
            <div>
              {series!.map((s) => <SubscriptionCard key={s.id} series={s} />)}
            </div>
          )}
        </CardContent>
      </Card>

      <RecurringForm open={formOpen} onOpenChange={setFormOpen} />
    </div>
  )
}
