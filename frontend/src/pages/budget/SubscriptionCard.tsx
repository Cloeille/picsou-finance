import { useTranslation } from 'react-i18next'
import { Check, Sparkles, Trash2, TrendingUp, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { MerchantAvatar } from '@/components/shared/MerchantAvatar'
import {
  useConfirmRecurring,
  useDeleteRecurring,
  useIgnoreRecurring,
} from '@/features/budget/hooks'
import { formatDate, getLocale } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { RecurringSeries } from '@/types/api'
import { CADENCE_LABEL_KEY, RUNTIME_STATUS_META, STATUS_LABEL_KEY } from './budget-meta'

/**
 * One detected/declared recurring series. Beyond the v1 row (avatar, label, cadence, status, amount)
 * it surfaces the v2 signals so the user can see *why* the detector decided what it did: a runtime
 * urgency chip (late / due soon), a "variable amount" tag, a silent-auto-confirm note with the
 * confidence, and a price-change line. The confirm / ignore / delete actions are unchanged.
 */
export function SubscriptionCard({ series }: { series: RecurringSeries }) {
  const money = useMoney()
  const { t } = useTranslation()
  const confirm = useConfirmRecurring()
  const ignore = useIgnoreRecurring()
  const remove = useDeleteRecurring()
  const busy = confirm.isPending || ignore.isPending || remove.isPending

  const statusVariant = series.status === 'CONFIRMED'
    ? 'default' : series.status === 'SUGGESTED' ? 'secondary' : 'outline'
  const runtime = series.runtimeStatus !== 'SCHEDULED' ? RUNTIME_STATUS_META[series.runtimeStatus] : null
  const confidencePct = series.confidence != null ? Math.round(series.confidence * 100) : null
  const showPriceChange = series.priceChangedAt != null && series.previousAmount != null

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-border py-3 last:border-0">
      <MerchantAvatar label={series.label} color={series.categoryColor} size="md" />

      <div className="min-w-0 flex-1 basis-40">
        <div className="flex flex-wrap items-center gap-1.5">
          <p className="truncate font-medium">{series.label}</p>
          <Badge variant={statusVariant}>{t(STATUS_LABEL_KEY[series.status])}</Badge>
          {runtime && (
            <Badge variant="outline" className={runtime.className}>{t(runtime.labelKey)}</Badge>
          )}
          {series.variable && (
            <Badge variant="outline">{t('budget.recurring.variable')}</Badge>
          )}
        </div>
        <p className="truncate text-xs text-muted-foreground">
          {t(CADENCE_LABEL_KEY[series.cadence])}
          {series.nextDueDate && ` · ${formatDate(series.nextDueDate, getLocale())}`}
        </p>
        {(series.autoConfirmed || showPriceChange) && (
          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs">
            {series.autoConfirmed && (
              <span className="inline-flex items-center gap-1 text-muted-foreground">
                <Sparkles className="size-3 shrink-0" />
                {confidencePct != null
                  ? t('budget.recurring.autoConfirmedConfident', { percent: confidencePct })
                  : t('budget.recurring.activity.autoConfirmed')}
              </span>
            )}
            {showPriceChange && (
              <span className="inline-flex items-center gap-1 text-amber-600 dark:text-amber-400">
                <TrendingUp className="size-3 shrink-0" />
                {t('budget.recurring.priceFromTo', {
                  from: money.amount(Math.abs(series.previousAmount!)),
                  to: money.amount(Math.abs(series.expectedAmount)),
                })}
              </span>
            )}
          </div>
        )}
      </div>

      <span className="ml-auto shrink-0 text-right text-sm tabular-nums">
        <CurrencyDisplay value={series.expectedAmount} showSign />
      </span>

      <div className="flex shrink-0 items-center gap-1">
        {series.status !== 'CONFIRMED' && (
          <Button size="icon" variant="ghost" disabled={busy}
            aria-label={t('budget.a11y.confirm', { name: series.label })}
            onClick={() => confirm.mutate(series.id)}>
            <Check className="size-4 text-emerald-600 dark:text-emerald-400" />
          </Button>
        )}
        {series.status !== 'IGNORED' && (
          <Button size="icon" variant="ghost" disabled={busy}
            aria-label={t('budget.a11y.ignore', { name: series.label })}
            onClick={() => ignore.mutate(series.id)}>
            <X className="size-4 text-muted-foreground" />
          </Button>
        )}
        <Button size="icon" variant="ghost" disabled={busy}
          aria-label={t('budget.a11y.delete', { name: series.label })}
          onClick={() => remove.mutate(series.id)}>
          <Trash2 className="size-4" />
        </Button>
      </div>
    </div>
  )
}
