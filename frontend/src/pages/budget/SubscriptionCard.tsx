import { useTranslation } from 'react-i18next'
import { ArrowDownLeft, ArrowUpRight, Check, Trash2, TrendingUp, X } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import {
  useConfirmRecurring,
  useDeleteRecurring,
  useIgnoreRecurring,
} from '@/features/budget/hooks'
import { cn, formatDate, formatPercent, getLocale } from '@/lib/utils'
import { formatBankLabel } from '@/lib/bank-label'
import { useMoney } from '@/hooks/use-money'
import type { RecurringSeries } from '@/types/api'
import { CADENCE_LABEL_KEY, RUNTIME_STATUS_META, STATUS_LABEL_KEY } from './budget-meta'

/**
 * One detected or declared recurring series, rendered in the calm 1.2.1 visual language:
 * monochrome directional tile, cleaned French/bank label, actionable status badges only,
 * tabular amount, and quiet confirm / ignore / delete actions.
 */
export function SubscriptionCard({ series }: { series: RecurringSeries }) {
  const money = useMoney()
  const { t, i18n } = useTranslation()
  const confirm = useConfirmRecurring()
  const ignore = useIgnoreRecurring()
  const remove = useDeleteRecurring()
  const busy = confirm.isPending || ignore.isPending || remove.isPending
  const locale = i18n?.resolvedLanguage ?? i18n?.language ?? getLocale()

  const { title, detail } = formatBankLabel(series.label, locale, (key, opts) => t(key, opts))
  const isIncome = series.expectedAmount > 0
  const showStatusBadge = series.status !== 'CONFIRMED'
  const statusVariant = series.status === 'SUGGESTED' ? 'secondary' : 'outline'
  const runtime = series.runtimeStatus !== 'SCHEDULED' ? RUNTIME_STATUS_META[series.runtimeStatus] : null
  const showPriceChange = series.priceChangedAt != null && series.previousAmount != null
  const confidencePct = series.confidence != null
    ? formatPercent(series.confidence * 100, { fractionDigits: 0 })
    : null

  const metaParts: string[] = [t(CADENCE_LABEL_KEY[series.cadence])]
  if (series.nextDueDate) {
    metaParts.push(
      t('budget.recurring.nextDueShort', {
        date: formatDate(series.nextDueDate, getLocale()),
      }),
    )
  }
  if (detail) {
    metaParts.push(detail)
  }

  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 py-3.5 first:pt-0 last:pb-0">
      <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted/60 text-foreground">
        {isIncome
          ? <ArrowDownLeft className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden="true" />
          : <ArrowUpRight className="size-4 text-muted-foreground" aria-hidden="true" />}
      </div>

      <div className="min-w-0 flex-1 basis-48">
        <div className="flex flex-wrap items-center gap-2">
          <p className="truncate text-sm font-medium text-foreground" title={series.label}>
            {title}
          </p>
          {showStatusBadge && (
            <Badge variant={statusVariant}>{t(STATUS_LABEL_KEY[series.status])}</Badge>
          )}
          {runtime && (
            <Badge variant="outline" className={runtime.className}>
              {t(runtime.labelKey)}
            </Badge>
          )}
          {series.variable && (
            <Badge variant="outline">{t('budget.recurring.variable')}</Badge>
          )}
        </div>

        <p className="mt-0.5 truncate text-sm text-muted-foreground">
          {metaParts.join(' · ')}
        </p>

        {series.autoConfirmed && (
          <p className="mt-0.5 text-sm text-muted-foreground">
            {confidencePct != null
              ? t('budget.recurring.autoConfirmedConfident', { percent: confidencePct })
              : t('budget.recurring.activity.autoConfirmed')}
          </p>
        )}

        {showPriceChange && (
          <p className="mt-1 inline-flex items-center gap-1.5 text-sm text-rose-600 dark:text-rose-400 [word-spacing:normal]">
            <TrendingUp className="size-3.5 shrink-0" aria-hidden="true" />
            <span>
              {t('budget.recurring.priceFromTo', {
                from: money.amount(Math.abs(series.previousAmount!)),
                to: money.amount(Math.abs(series.expectedAmount)),
              })}
            </span>
          </p>
        )}
      </div>

      <div className="ml-auto flex shrink-0 items-center gap-3">
        <span
          className={cn(
            'text-sm font-semibold tabular-nums',
            isIncome ? 'text-emerald-600 dark:text-emerald-400' : 'text-foreground',
          )}
        >
          <CurrencyDisplay value={series.expectedAmount} showSign />
        </span>

        <div className="flex items-center justify-end gap-0.5">
          {series.status !== 'CONFIRMED' && (
            <Button
              size="icon"
              variant="ghost"
              className="size-8"
              disabled={busy}
              aria-label={t('budget.a11y.confirm', { name: series.label })}
              onClick={() => confirm.mutate(series.id)}
            >
              <Check className="size-4 text-emerald-600 dark:text-emerald-400" />
            </Button>
          )}
          {series.status !== 'IGNORED' && (
            <Button
              size="icon"
              variant="ghost"
              className="size-8 text-muted-foreground hover:text-foreground"
              disabled={busy}
              aria-label={t('budget.a11y.ignore', { name: series.label })}
              onClick={() => ignore.mutate(series.id)}
            >
              <X className="size-4" />
            </Button>
          )}
          <Button
            size="icon"
            variant="ghost"
            className="size-8 text-muted-foreground hover:text-destructive"
            disabled={busy}
            aria-label={t('budget.a11y.delete', { name: series.label })}
            onClick={() => remove.mutate(series.id)}
          >
            <Trash2 className="size-4" />
          </Button>
        </div>
      </div>
    </div>
  )
}
