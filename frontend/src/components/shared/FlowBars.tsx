import type { TFunction } from 'i18next'
import { useTranslation } from 'react-i18next'
import type { CashflowFlowResponse } from '@/types/api'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { type FlowBar, flowNodeColor, flowNodeLabel, flowSides } from './flow-utils'

/**
 * Mobile (<md) fallback for `CashflowSankey`: the same flow data as two stacked,
 * largest-first proportion-bar lists (money in, money out). A Sankey is unreadable on a
 * narrow phone, so we trade the crossing ribbons for plain bars that still rank each
 * source/sink by share of its side. No width measurement needed — pure CSS widths.
 */

function FlowBarRow({ bar, sideTotal, t }: { bar: FlowBar; sideTotal: number; t: TFunction }) {
  const color = flowNodeColor(bar.node)
  const share = sideTotal > 0 ? bar.value / sideTotal : 0
  const percent = Math.round(share * 100)
  const label = flowNodeLabel(bar.node, t)
  return (
    <div>
      <div className="flex items-center justify-between gap-3 text-sm">
        <span className="flex min-w-0 items-center gap-2">
          <span
            aria-hidden="true"
            className="inline-block size-2.5 shrink-0 rounded-full"
            style={{ backgroundColor: color }}
          />
          <span className="truncate">{label}</span>
        </span>
        <CurrencyDisplay value={bar.value} className="shrink-0 font-medium tabular-nums" />
      </div>
      <div
        className="mt-1 h-2 overflow-hidden rounded-full bg-muted"
        role="progressbar"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={`${percent}%`}
      >
        <div
          className="h-full rounded-full transition-all"
          style={{ width: `${Math.max(share * 100, 2)}%`, backgroundColor: color }}
        />
      </div>
    </div>
  )
}

function FlowBarSection({
  titleKey,
  bars,
  t,
}: {
  titleKey: string
  bars: FlowBar[]
  t: TFunction
}) {
  if (bars.length === 0) return null
  const sideTotal = bars.reduce((acc, b) => acc + b.value, 0)
  return (
    <div className="space-y-3">
      <p className="text-xs font-medium uppercase tracking-wider text-muted-foreground">
        {t(titleKey)}
      </p>
      <div className="space-y-3">
        {bars.map((bar, i) => (
          <FlowBarRow key={`${bar.node.key}-${i}`} bar={bar} sideTotal={sideTotal} t={t} />
        ))}
      </div>
    </div>
  )
}

export function FlowBars({ flow }: { flow: CashflowFlowResponse }) {
  const { t } = useTranslation()
  const { sources, sinks } = flowSides(flow)

  return (
    <div className="space-y-6">
      <FlowBarSection titleKey="budget.flow.inflows" bars={sources} t={t} />
      <FlowBarSection titleKey="budget.flow.outflows" bars={sinks} t={t} />
    </div>
  )
}
