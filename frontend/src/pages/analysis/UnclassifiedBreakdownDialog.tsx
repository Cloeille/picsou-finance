import { useId, useRef, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowsClockwise, Tag } from '@phosphor-icons/react'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useRefreshSecurityProfiles } from '@/features/analysis/hooks'
import { cn, formatPercent } from '@/lib/utils'
import type { AxisUnclassifiedItem, Diversification } from '@/types/api'
import {
  axisUnclassifiedItems,
  axisUnclassifiedValue,
  type DiversificationAxis,
} from './unclassified'

export function UnclassifiedBreakdownDialog({
  open,
  axis,
  data,
  onAxisChange,
  onClassify,
  onClose,
}: {
  open: boolean
  axis: DiversificationAxis
  data: Diversification
  onAxisChange: (axis: DiversificationAxis) => void
  onClassify: (item: AxisUnclassifiedItem) => void
  onClose: () => void
}) {
  const { t } = useTranslation()
  const refresh = useRefreshSecurityProfiles()
  const idBase = useId()
  const tabRefs = useRef<Partial<Record<DiversificationAxis, HTMLButtonElement | null>>>({})

  const items = axisUnclassifiedItems(data, axis)
  const unclassifiedValue = axisUnclassifiedValue(data, axis)
  const anyNoProfile = items.some((item) => item.reason === 'NO_PROFILE')

  const tabs: Array<{ id: DiversificationAxis; label: string; count: number }> = [
    {
      id: 'sectors',
      label: t('analysis.diversification.sectors'),
      count: axisUnclassifiedItems(data, 'sectors').length,
    },
    {
      id: 'countries',
      label: t('analysis.diversification.countries'),
      count: axisUnclassifiedItems(data, 'countries').length,
    },
  ]

  function handleTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight') {
      nextIndex = (index + 1) % tabs.length
    } else if (event.key === 'ArrowLeft') {
      nextIndex = (index - 1 + tabs.length) % tabs.length
    } else if (event.key === 'Home') {
      nextIndex = 0
    } else if (event.key === 'End') {
      nextIndex = tabs.length - 1
    }
    if (nextIndex === null) return
    event.preventDefault()
    const nextAxis = tabs[nextIndex].id
    onAxisChange(nextAxis)
    tabRefs.current[nextAxis]?.focus()
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t('analysis.diversification.unclassifiedDialogTitle')}</DialogTitle>
          <DialogDescription>
            {t('analysis.diversification.unclassifiedDialogDescription')}
          </DialogDescription>
        </DialogHeader>

        <div
          role="tablist"
          aria-label={t('analysis.diversification.unclassifiedDialogTitle')}
          className="flex w-full items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
        >
          {tabs.map(({ id, label, count }, index) => {
            const active = axis === id
            return (
              <button
                key={id}
                ref={(node) => {
                  tabRefs.current[id] = node
                }}
                id={`${idBase}-tab-${id}`}
                role="tab"
                type="button"
                tabIndex={active ? 0 : -1}
                aria-selected={active}
                aria-controls={`${idBase}-panel-${id}`}
                onClick={() => onAxisChange(id)}
                onKeyDown={(event) => handleTabKeyDown(event, index)}
                className={cn(
                  'inline-flex h-8 flex-1 items-center justify-center gap-1.5 rounded-md px-3 text-sm font-medium whitespace-nowrap transition-[background-color,color]',
                  active
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                <span>{label}</span>
                {count > 0 && (
                  <span
                    className={cn(
                      'text-xs tabular-nums',
                      active ? 'text-primary-foreground/80' : 'text-muted-foreground',
                    )}
                  >
                    ({count})
                  </span>
                )}
              </button>
            )
          })}
        </div>

        <div
          role="tabpanel"
          id={`${idBase}-panel-${axis}`}
          aria-labelledby={`${idBase}-tab-${axis}`}
          className="space-y-4"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap items-baseline gap-2 text-sm text-muted-foreground">
              <CurrencyDisplay
                value={unclassifiedValue}
                className="text-sm font-semibold tabular-nums text-foreground"
              />
              <span>{t('analysis.diversification.unclassified')}</span>
            </div>

            {anyNoProfile && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={refresh.isPending}
                onClick={() =>
                  refresh.mutate(undefined, {
                    onSuccess: (result) =>
                      toast.success(
                        result.alreadyRunning
                          ? t('analysis.classification.refreshAlready')
                          : t('analysis.classification.refreshQueued', {
                              count: result.queuedTickers,
                            }),
                      ),
                  })
                }
              >
                <ArrowsClockwise
                  className={cn('size-4', refresh.isPending && 'animate-spin')}
                  aria-hidden="true"
                />
                {t('analysis.classification.refresh')}
              </Button>
            )}
          </div>

          {items.length === 0 ? (
            <p className="rounded-lg border border-border/60 bg-muted/30 px-4 py-6 text-center text-sm text-muted-foreground">
              {t('analysis.diversification.unclassifiedDialogEmpty')}
            </p>
          ) : (
            <ul className="space-y-2.5">
              {items.map((item) => {
                const showIsin = Boolean(item.isin && item.isin !== item.ticker)
                const isIncomplete = item.reason === 'INCOMPLETE_COMPOSITION'
                const canClassify = item.accountId != null && !isIncomplete
                const pct = formatPercent(item.unclassifiedPercent, {
                  fractionDigits: item.unclassifiedPercent === 100 ? 0 : 1,
                })
                return (
                  <li
                    key={item.ticker}
                    className="flex flex-col gap-3 rounded-lg border border-border/60 bg-muted/40 p-3.5 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div className="min-w-0 flex-1 space-y-1.5">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-foreground">
                          {item.name ?? item.ticker}
                        </p>
                        <div className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
                          <span>{item.ticker}</span>
                          {showIsin && (
                            <>
                              <span aria-hidden="true">·</span>
                              <span>{item.isin}</span>
                            </>
                          )}
                          {item.accountName && (
                            <>
                              <span aria-hidden="true">·</span>
                              <span className="truncate">{item.accountName}</span>
                            </>
                          )}
                        </div>
                      </div>

                      <div>
                        <Badge variant="secondary">
                          {t(`analysis.diversification.unclassifiedReasons.${item.reason}`)}
                        </Badge>
                      </div>

                      {isIncomplete && (
                        <p className="text-sm text-muted-foreground">
                          {t('analysis.diversification.incompleteCompositionHint')}
                        </p>
                      )}
                    </div>

                    <div className="flex items-center justify-between gap-3 sm:justify-end">
                      <div className="text-left tabular-nums sm:text-right">
                        <CurrencyDisplay
                          value={item.unclassifiedValueEur}
                          className="block text-sm font-semibold text-foreground"
                        />
                        <span className="text-xs text-muted-foreground">
                          {t('analysis.diversification.unclassifiedShare', { percent: pct })}
                        </span>
                      </div>

                      {canClassify && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => onClassify(item)}
                          aria-label={t('analysis.classification.editAria', {
                            ticker: item.ticker,
                          })}
                        >
                          <Tag className="size-4" aria-hidden="true" />
                          {t('analysis.classification.action')}
                        </Button>
                      )}
                    </div>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      </DialogContent>
    </Dialog>
  )
}
