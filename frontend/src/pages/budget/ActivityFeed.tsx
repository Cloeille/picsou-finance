import { useTranslation } from 'react-i18next'
import { Check, Repeat, TrendingUp, Undo2 } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { useRecurringActivity, useUndoRecurring } from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import { formatBankLabel } from '@/lib/bank-label'
import { useMoney } from '@/hooks/use-money'
import type { RecurringActivity } from '@/types/api'

/**
 * Recent detection activity — surfaces auto-confirmed series and price changes with
 * per-item undo/acknowledge. Renders nothing while loading or when empty.
 */
function ActivityRow({ entry }: { entry: RecurringActivity }) {
  const money = useMoney()
  const { t, i18n } = useTranslation()
  const undo = useUndoRecurring()
  const locale = i18n?.resolvedLanguage ?? i18n?.language ?? getLocale()

  const isPriceChange = entry.type === 'PRICE_CHANGE'
  const { title, detail } = formatBankLabel(entry.label, locale, (key, opts) => t(key, opts))
  const description = isPriceChange && entry.previousAmount != null
    ? t('budget.recurring.activity.priceChangeDesc', {
        from: money.amount(Math.abs(entry.previousAmount)),
        to: money.amount(Math.abs(entry.expectedAmount)),
      })
    : t('budget.recurring.activity.autoConfirmedDesc')

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 py-3 first:pt-0 last:pb-0">
      <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted/60 text-foreground">
        {isPriceChange
          ? <TrendingUp className="size-4 text-rose-600 dark:text-rose-400" aria-hidden="true" />
          : <Repeat className="size-4 text-muted-foreground" aria-hidden="true" />}
      </div>

      <div className="min-w-0 flex-1 basis-48">
        <p className="truncate text-sm font-medium text-foreground" title={entry.label}>
          {title}
        </p>
        <p className="truncate text-sm text-muted-foreground">
          {detail ? `${detail} · ${description}` : description}
        </p>
      </div>

      <div className="ml-auto flex shrink-0 items-center gap-3">
        {entry.occurredOn && (
          <span className="text-xs text-muted-foreground tabular-nums">
            {formatDate(entry.occurredOn, getLocale())}
          </span>
        )}
        <Button
          size="sm"
          variant="outline"
          disabled={undo.isPending}
          onClick={() => undo.mutate(entry.seriesId)}
        >
          {isPriceChange ? <Check className="size-3.5" /> : <Undo2 className="size-3.5" />}
          {isPriceChange ? t('budget.recurring.activity.acknowledge') : t('budget.recurring.undo')}
        </Button>
      </div>
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
        <CardTitle className="text-base">
          {t('budget.recurring.activity.title')}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="divide-y divide-border/60">
          {activity.map((entry) => (
            <ActivityRow key={`${entry.seriesId}-${entry.type}-${entry.occurredOn ?? ''}`} entry={entry} />
          ))}
        </div>
      </CardContent>
    </Card>
  )
}
