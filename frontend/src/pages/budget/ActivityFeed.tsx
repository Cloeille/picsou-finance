import { useTranslation } from 'react-i18next'
import { Sparkles, TrendingUp, Undo2 } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import { useRecurringActivity, useUndoRecurring } from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { RecurringActivity } from '@/types/api'

/**
 * "What changed" feed — the safety net for silent auto-confirm. It only surfaces *recent*
 * events (auto-confirmed series + price steps); when nothing changed it renders nothing at
 * all, so it never becomes a permanent chore the way a review queue would. Each entry offers
 * a context-aware reversal: a price change is *acknowledged* (keep the new amount, dismiss the
 * alert), a silent auto-confirm is *rejected* (the series goes back to IGNORED). Both map to the
 * single backend `undo` endpoint, which decides the behaviour from the series' own state.
 */
function ActivityRow({ entry }: { entry: RecurringActivity }) {
  const money = useMoney()
  const { t } = useTranslation()
  const undo = useUndoRecurring()

  const isPriceChange = entry.type === 'PRICE_CHANGE'
  const description = isPriceChange && entry.previousAmount != null
    ? t('budget.recurring.activity.priceChangeDesc', {
        from: money.amount(Math.abs(entry.previousAmount)),
        to: money.amount(Math.abs(entry.expectedAmount)),
      })
    : t('budget.recurring.activity.autoConfirmedDesc')

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-border py-3 last:border-0">
      <MerchantAvatar label={entry.label} color={entry.categoryColor} size="sm" />

      <div className="min-w-0 flex-1 basis-40">
        <div className="flex items-center gap-1.5">
          {isPriceChange
            ? <TrendingUp className="size-3.5 shrink-0 text-amber-600 dark:text-amber-400" />
            : <Sparkles className="size-3.5 shrink-0 text-violet-600 dark:text-violet-400" />}
          <p className="truncate font-medium">{entry.label}</p>
        </div>
        <p className="truncate text-xs text-muted-foreground">{description}</p>
      </div>

      {entry.occurredOn && (
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
          {formatDate(entry.occurredOn, getLocale())}
        </span>
      )}

      <Button size="sm" variant="ghost" className="ml-auto shrink-0" disabled={undo.isPending}
        onClick={() => undo.mutate(entry.seriesId)}>
        <Undo2 className="size-4" />
        {isPriceChange ? t('budget.recurring.activity.acknowledge') : t('budget.recurring.undo')}
      </Button>
    </div>
  )
}

/** The activity card. Renders nothing while loading or when the feed is empty. */
export function ActivityFeed() {
  const { t } = useTranslation()
  const { data: activity, isLoading } = useRecurringActivity()

  if (isLoading || !activity || activity.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Sparkles className="size-4 text-violet-600 dark:text-violet-400" />
          {t('budget.recurring.activity.title')}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {activity.map((entry) => (
          <ActivityRow key={`${entry.seriesId}-${entry.type}-${entry.occurredOn ?? ''}`} entry={entry} />
        ))}
      </CardContent>
    </Card>
  )
}
