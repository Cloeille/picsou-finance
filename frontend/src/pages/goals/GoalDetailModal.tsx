import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useGoal } from '@/features/goals/hooks'
import { useHistory } from '@/features/history/hooks'
import { NetWorthChart } from '@/components/shared/NetWorthChart'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { TrendingUp, TrendingDown, Loader2 } from 'lucide-react'
import { type TimeRange } from '@/components/shared/TimeRangeSelector'

interface GoalDetailModalProps {
  goalId: number | null
  onClose: () => void
}

export function GoalDetailModal({ goalId, onClose }: GoalDetailModalProps) {
  const { t } = useTranslation()
  const [range, setRange] = useState<TimeRange>('ALL')

  const { data: goal, isLoading: goalLoading } = useGoal(goalId ?? 0)
  const accountIds = useMemo(() => goal?.accounts.map(a => a.id) ?? [], [goal])
  const { data: history, isLoading: historyLoading } = useHistory(accountIds, 12)

  const open = goalId != null
  const isLoading = goalLoading || historyLoading

  // Baseline = balance at goal creation. We need this so the ideal trajectory
  // is drawn in the same reference frame as the live area (which starts from
  // the linked accounts' pre-goal balance, not from zero). Without it, the
  // dashed target line floats far below the data and the chart looks rosy
  // even when the "behind" badge fires.
  const baseline = useMemo(() => {
    if (!goal || !history?.length) return 0
    const createdMs = new Date(goal.createdAt).getTime()
    if (!Number.isFinite(createdMs)) return 0
    // First snapshot at or after createdAt is the closest proxy for "balance
    // at creation". History is sorted ascending by the backend.
    const firstKept = history.find(p => new Date(p.date).getTime() >= createdMs)
    return firstKept?.total ?? history[0]?.total ?? 0
  }, [goal, history])

  // Captured once at mount -- the "today" marker doesn't need to drift live as
  // the user interacts with the modal. Shared by the projection start and the
  // chart's vertical guide so they always line up.
  const [todayMs] = useState(() => Date.now())

  // "At current pace" forecast: where the user lands at deadline if they keep
  // contributing at the trailing 3-month average. Null when we lack the data
  // to draw it honestly.
  const projection = useMemo(() => {
    // Both overlays are drawn towards a deadline. A recurring plan has none, so there is nothing
    // honest to draw — the four-scenario projection on the Analysis page is its equivalent.
    if (!goal || goal.avgMonthlyContribution == null || goal.deadline == null) return undefined
    const endValue = goal.currentTotal + goal.avgMonthlyContribution * Math.max(goal.monthsLeft, 0)
    return {
      startDate: new Date(todayMs).toISOString(),
      startValue: goal.currentTotal,
      endDate: goal.deadline,
      endValue,
      label: t('goals.atCurrentPace'),
    }
  }, [goal, t, todayMs])

  const statusBadge = goal
    ? (() => {
        if ((goal.monthlyNeeded ?? 0) <= 0) {
          return (
            <Badge className="gap-1">
              <TrendingUp className="size-3" />
              {t('goals.achieved')}
            </Badge>
          )
        }
        if (goal.isOnTrack) {
          return (
            <Badge className="gap-1">
              <TrendingUp className="size-3" />
              {t('goals.onTrack')}
            </Badge>
          )
        }
        return (
          <Badge variant="destructive" className="gap-1">
            <TrendingDown className="size-3" />
            {t('goals.behind')}
          </Badge>
        )
      })()
    : null

  return (
    <Dialog open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <DialogContent className="sm:max-w-[95vw] max-h-[90vh] overflow-y-auto">
        {isLoading || !goal ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="size-6 animate-spin text-muted-foreground" />
          </div>
        ) : (
          <>
            <DialogHeader>
              <div className="flex items-center gap-3">
                <DialogTitle className="text-lg">{goal.name}</DialogTitle>
                {statusBadge}
              </div>
            </DialogHeader>

            <div className="space-y-6 mt-2">
              {/* Progress summary */}
              <div className="flex items-end justify-between">
                <div>
                  <p className="text-xs text-muted-foreground mb-1">{t('goals.currentTotal')}</p>
                  <CurrencyDisplay value={goal.currentTotal} className="text-4xl font-semibold tabular-nums" />
                </div>
                <div className="text-right">
                  <p className="text-xs text-muted-foreground mb-1">
                    {goal.targetAmount == null ? t('goals.monthlyAmount') : t('goals.targetAmount')}
                  </p>
                  <CurrencyDisplay
                    value={goal.targetAmount ?? goal.monthlyAmount ?? 0}
                    className="text-xl font-medium tabular-nums text-muted-foreground"
                  />
                </div>
              </div>

              {/* Chart */}
              {history && history.length > 0 && (
                <NetWorthChart
                  data={history}
                  range={range}
                  onRangeChange={setRange}
                  showInvested={false}
                  target={
                    goal.deadline != null && goal.targetAmount != null
                      ? {
                          startDate: goal.createdAt,
                          startValue: baseline,
                          endDate: goal.deadline,
                          endValue: goal.targetAmount,
                          label: t('goals.targetAmount'),
                        }
                      : undefined
                  }
                  projection={projection}
                  todayMs={todayMs}
                  todayLabel={t('common.today')}
                />
              )}

              {/* Stats grid */}
              <div className="grid grid-cols-4 gap-4 pt-4 border-t">
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('goals.progress')}</p>
                  <p className="text-sm font-semibold">{Math.round(goal.percentComplete ?? 0)}%</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('goals.monthsLeft')}</p>
                  <p className="text-sm font-semibold">{goal.monthsLeft}</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('goals.monthlyNeeded')}</p>
                  <p className="text-sm font-semibold"><CurrencyDisplay value={goal.monthlyNeeded ?? 0} /></p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('goals.avgContribution')}</p>
                  <p className="text-sm font-semibold">
                    {goal.avgMonthlyContribution != null ? (
                      <CurrencyDisplay value={goal.avgMonthlyContribution} />
                    ) : (
                      '\u2013'
                    )}
                  </p>
                </div>
              </div>

              {/* Linked accounts */}
              {goal.accounts.length > 0 && (
                <div className="pt-4 border-t">
                  <p className="mb-2 text-sm font-medium text-muted-foreground">
                    {t('goals.linkedAccounts')}
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {goal.accounts.map((a) => (
                      <div
                        key={a.id}
                        className="flex items-center gap-2 rounded-full bg-muted/50 px-3 py-1.5"
                      >
                        <span
                          className="size-2 rounded-full shrink-0"
                          style={{ background: a.color }}
                        />
                        <span className="text-sm">{a.name}</span>
                        <span className="text-xs text-muted-foreground font-medium tabular-nums">
                          <CurrencyDisplay value={a.currentBalanceEur} />
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
