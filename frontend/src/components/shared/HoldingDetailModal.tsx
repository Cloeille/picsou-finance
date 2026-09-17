import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import { usePriceHistory, type PortfolioLine } from '@/features/accounts/hooks'
import { NetWorthChart } from '@/components/shared/NetWorthChart'
import { EmptyChartState } from '@/components/shared/EmptyChartState'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { HoldingInsightSection } from '@/components/shared/HoldingInsightSection'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { TrendingUp, TrendingDown, Loader2, Tags } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { HoldingClassificationModal } from '@/pages/analysis/HoldingClassificationModal'
import { type TimeRange } from '@/components/shared/TimeRangeSelector'
import { formatDate } from '@/lib/utils'
import { accountTypeLabelKey } from '@/lib/constants'

type ChartMode = 'holding' | 'price'

interface HoldingDetailModalProps {
  line: PortfolioLine | null
  onClose: () => void
}

export function HoldingDetailModal({ line, onClose }: HoldingDetailModalProps) {
  const money = useMoney()
  const { t } = useTranslation()
  const [range, setRange] = useState<TimeRange>('1Y')
  const [mode, setMode] = useState<ChartMode>('price')
  const [classifying, setClassifying] = useState(false)

  const months = range === 'ALL' ? 1200 : range === '3M' ? 3 : range === '1M' || range === '7D' ? 1 : range === 'YTD' ? new Date().getMonth() + 1 : 12
  const { data: rawHistory, isLoading } = usePriceHistory(line?.ticker ?? null, months, range)

  const is24H = range === '24H'

  // In "holding" mode we draw a horizontal dashed line at the current cost
  // basis -- per-ticker history isn't derivable cheaply (no dated lots) so a
  // static horizontal reference is the honest representation.
  // In "price" mode "invested" is meaningless (unit price has no cost basis)
  // and must be omitted so NetWorthChart hides the line + legend.
  const investedRef = mode === 'holding' && line?.costBasisEur != null ? line.costBasisEur : undefined

  const history = useMemo(() => {
    if (!rawHistory) return []
    return rawHistory.map(p => {
      const total = mode === 'holding' && line ? p.priceEur * line.quantity : p.priceEur
      return {
        date: p.date,
        total,
        // For a pure holding, value − cost basis IS its debt-free investment
        // pnl — emit it so the chart tooltip keeps its gain/loss row (the
        // tooltip reads `pnl` and never recomputes total − invested).
        ...(investedRef !== undefined ? { invested: investedRef, pnl: total - investedRef } : {}),
      }
    })
  }, [rawHistory, mode, line, investedRef])

  const intraday = useMemo(() => {
    if (!is24H || !rawHistory) return []
    return rawHistory.map(p => {
      const total = mode === 'holding' && line ? p.priceEur * line.quantity : p.priceEur
      return {
        timestamp: p.date,
        total,
        // Same as the history mapping above: value − cost basis is the holding's
        // debt-free pnl; the tooltip reads `pnl` and never recomputes it.
        ...(investedRef !== undefined ? { invested: investedRef, pnl: total - investedRef } : {}),
      }
    })
  }, [rawHistory, mode, line, is24H, investedRef])

  const priceChange = useMemo(() => {
    if (!rawHistory || rawHistory.length < 2) return null
    const first = rawHistory[0].priceEur
    const last = rawHistory[rawHistory.length - 1].priceEur
    if (first === 0) return null
    const diff = last - first
    const pct = (diff / first) * 100
    return { diff, pct, positive: diff >= 0 }
  }, [rawHistory])

  const open = line != null
  const pnlPositive = (line?.pnlEur ?? 0) >= 0

  return (
    <>
      <Dialog open={open} onOpenChange={(isOpen) => { if (!isOpen) onClose() }}>
      <DialogContent className="sm:max-w-[95vw] max-h-[90vh] overflow-y-auto">
        {isLoading || !line ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="size-6 animate-spin text-muted-foreground" />
          </div>
        ) : (
          <>
            <DialogHeader>
              <div className="flex items-center gap-3">
                <DialogTitle className="text-lg">{line.name}</DialogTitle>
                {line.ticker && line.accountId !== null && (
                  <Badge variant="outline" className="font-mono text-xs">
                    {line.ticker}
                  </Badge>
                )}
                {line.pnlPercent != null && (
                  <Badge
                    className={line.pnlPercent >= 0 ? 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 gap-1' : 'bg-red-500/10 text-red-600 dark:text-red-400 gap-1'}
                  >
                    {line.pnlPercent >= 0 ? <TrendingUp className="size-3" /> : <TrendingDown className="size-3" />}
                    {line.pnlPercent >= 0 ? '+' : ''}{line.pnlPercent.toFixed(1)}%
                  </Badge>
                )}
              </div>
            </DialogHeader>

            <div className="space-y-6 mt-2">
              {/* Price summary */}
              <div className="flex items-end justify-between">
                <div>
                  <p className="text-xs text-muted-foreground mb-1">
                    {mode === 'holding' ? t('holdings.totalValue') : t('holdings.unitPrice')}
                  </p>
                  <CurrencyDisplay
                    value={mode === 'holding' ? line.valueEur : (line.valueEur / line.quantity)}
                    className="text-4xl font-semibold tabular-nums"
                  />
                </div>
                {mode === 'holding' && line.pnlEur != null ? (
                  <div className="text-right">
                    <p className="text-xs text-muted-foreground mb-1">{t('holdings.pnl')}</p>
                    <span className={`text-xl font-medium tabular-nums ${pnlPositive ? 'text-emerald-500' : 'text-red-500'}`}>
                      {pnlPositive ? '+' : ''}<CurrencyDisplay value={line.pnlEur} />
                    </span>
                  </div>
                ) : mode === 'price' && priceChange && (is24H ? intraday.length > 0 : history.length > 0) ? (
                  <div className="text-right">
                    <p className="text-xs text-muted-foreground mb-1">{t('holdings.evolution')}</p>
                    <span className={`text-xl font-medium tabular-nums ${priceChange.positive ? 'text-emerald-500' : 'text-red-500'}`}>
                      {priceChange.positive ? '+' : ''}{priceChange.pct.toFixed(1)}%
                    </span>
                  </div>
                ) : null}
              </div>

              {/* Chart with mode toggle */}
              <div className="space-y-3">
                <div className="inline-flex items-center rounded-2xl bg-muted p-1">
                  {([
                    { value: 'price' as ChartMode, label: t('holdings.assetPrice') },
                    { value: 'holding' as ChartMode, label: t('holdings.myPosition') },
                  ]).map(opt => (
                    <button
                      key={opt.value}
                      onClick={() => setMode(opt.value)}
                      className={`inline-flex h-10 min-w-32 items-center justify-center rounded-xl px-6 text-sm font-medium transition-[background-color,color] ${
                        mode === opt.value
                          ? 'bg-background text-foreground'
                          : 'text-muted-foreground hover:text-foreground'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>

                {(history.length > 0 || intraday.length > 0) ? (
                  <NetWorthChart
                    data={history}
                    intraday={intraday}
                    range={range}
                    onRangeChange={setRange}
                    showInvested={mode === 'holding'}
                  />
                ) : is24H ? (
                  <EmptyChartState />
                ) : null}
              </div>

              {/* Stats grid */}
              <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-4 pt-4 border-t">
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('holdings.quantity')}</p>
                  <p className="text-sm font-semibold tabular-nums">{money.quantity(line.quantity.toLocaleString())}</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('holdings.capitalInvested')}</p>
                  {line.costBasisEur != null ? (
                    <>
                      <CurrencyDisplay value={line.costBasisEur} className="text-sm font-semibold tabular-nums" />
                      {line.averageBuyIn != null && (
                        <p className="text-[10px] text-muted-foreground tabular-nums mt-0.5">
                          {line.averageBuyIn.toFixed(2)} {t('holdings.perShare')}
                        </p>
                      )}
                    </>
                  ) : (
                    <p className="text-sm font-semibold">{'\u2013'}</p>
                  )}
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('holdings.account')}</p>
                  <p className="text-sm font-semibold truncate">{line.accountName}</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('holdings.lastUpdated')}</p>
                  <p className="text-sm font-semibold">
                    {formatDate(line.priceUpdatedAt)}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-0.5">{t('holdings.type')}</p>
                  <Badge variant="outline">
                    {t(accountTypeLabelKey(line.accountType))}
                  </Badge>
                </div>
              </div>

              {/* Asset-type & ETF composition insight */}
              <HoldingInsightSection ticker={line.ticker} name={line.name} open={open} />

              {/* The second way into the editor: correct a security where you are already
                  looking at it, rather than only from the coverage list on the Analysis page. */}
              {line.ticker && (
                <div className="flex justify-end">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setClassifying(true)}
                  >
                    <Tags className="size-4" aria-hidden="true" />
                    {t('analysis.classification.action')}
                  </Button>
                </div>
              )}
            </div>
          </>
        )}
      </DialogContent>
      </Dialog>

      {/* Sibling, not nested: two Dialogs in one subtree fight over the focus trap. */}
      <HoldingClassificationModal
        open={classifying}
        accountId={line?.accountId ?? null}
        ticker={line?.ticker ?? null}
        name={line?.name}
        onClose={() => setClassifying(false)}
      />
    </>
  )
}
