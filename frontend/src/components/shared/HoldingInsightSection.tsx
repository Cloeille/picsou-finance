import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Loader2, Columns3, AlignJustify } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import PartitionBar, {
  PartitionBarSegment,
  PartitionBarSegmentTitle,
  PartitionBarSegmentValue,
} from '@/components/ui/partition-bar'
import { useSecurityInsight } from '@/features/accounts/hooks'
import type { WeightedSlice } from '@/types/api'
import { cn, formatDate } from '@/lib/utils'
import { OTHERS_SLICE_COLOR, SLICE_PALETTE } from '@/lib/chart-palette'

// Two ways to render a composition, switchable via the view toggle:
//  - "block": labelled segments inside a single proportional bar (rich on a wide screen).
//  - "line":  a slim colour-only bar + a wrapping legend (legible at any width / on a phone).
type CompositionView = 'block' | 'line'

// Block view: cycle variants so adjacent labelled segments stay visually distinct.
const SEGMENT_VARIANTS = ['default', 'secondary', 'outline', 'muted'] as const

// Line view palette, shared with the portfolio breakdown so the same data reads the same way.
const PALETTE = SLICE_PALETTE
const OTHERS_COLOR = OTHERS_SLICE_COLOR

interface HoldingInsightSectionProps {
  ticker: string | null
  name: string | null
  open: boolean
}

export function HoldingInsightSection({ ticker, name, open }: HoldingInsightSectionProps) {
  const { t } = useTranslation()
  const [view, setView] = useState<CompositionView>('line')
  const { data, isLoading } = useSecurityInsight(ticker, name, open)

  if (!ticker) return null

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-6 border-t">
        <Loader2 className="size-4 animate-spin text-muted-foreground" />
      </div>
    )
  }

  // Demo mode / unknown tickers return an empty object → nothing to show.
  if (!data || !data.assetType) return null

  const composition = data.composition

  return (
    <div className="space-y-4 pt-4 border-t">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t('holdings.insight.title')}</h3>
        <Badge variant="outline">{t(`holdings.insight.assetTypes.${data.assetType}`)}</Badge>
      </div>

      {composition ? (
        <div className="space-y-4">
          <div className="flex justify-end">
            <CompositionViewToggle view={view} onChange={setView} t={t} />
          </div>
          <CompositionBar view={view} title={t('holdings.insight.companies')} slices={composition.companies} t={t} />
          <CompositionBar view={view} title={t('holdings.insight.countries')} slices={composition.countries} t={t} labelNs="holdings.insight.countryNames" />
          <CompositionBar view={view} title={t('holdings.insight.sectors')} slices={composition.sectors} t={t} labelNs="holdings.insight.sectorNames" />
          {(composition.source || composition.asOf) && (
            <p className="text-[10px] text-muted-foreground">
              {composition.source && t('holdings.insight.source', { source: composition.source })}
              {composition.source && composition.asOf && ' · '}
              {composition.asOf && t('holdings.insight.asOf', { date: formatDate(composition.asOf) })}
            </p>
          )}
        </div>
      ) : data.assetType === 'ETF' ? (
        <p className="text-xs text-muted-foreground">{t('holdings.insight.unavailable')}</p>
      ) : null}
    </div>
  )
}

interface CompositionViewToggleProps {
  view: CompositionView
  onChange: (view: CompositionView) => void
  t: TFunction
}

// Segmented control mirroring the chart-mode toggle in HoldingDetailModal.
function CompositionViewToggle({ view, onChange, t }: CompositionViewToggleProps) {
  const options = [
    { value: 'block' as const, label: t('holdings.insight.viewBlock'), Icon: Columns3 },
    { value: 'line' as const, label: t('holdings.insight.viewLine'), Icon: AlignJustify },
  ]
  return (
    <div className="inline-flex items-center rounded-2xl bg-muted p-1">
      {options.map(({ value, label, Icon }) => (
        <button
          key={value}
          type="button"
          onClick={() => onChange(value)}
          aria-pressed={view === value}
          title={label}
          className={cn(
            'inline-flex h-10 min-w-32 items-center justify-center gap-2 rounded-xl px-6 text-sm font-medium transition-[background-color,color]',
            view === value
              ? 'bg-background text-foreground'
              : 'text-muted-foreground hover:text-foreground'
          )}
        >
          <Icon className="size-4" />
          <span>{label}</span>
        </button>
      ))}
    </div>
  )
}

interface CompositionBarProps {
  view: CompositionView
  title: string
  slices: WeightedSlice[]
  t: TFunction
  labelNs?: string
}

function CompositionBar({ view, title, slices, t, labelNs }: CompositionBarProps) {
  if (!slices || slices.length === 0) return null

  const labelOf = (raw: string) => (labelNs ? t(`${labelNs}.${raw}`, raw) : raw)
  const sum = slices.reduce((acc, s) => acc + s.percent, 0)
  const others = Math.max(0, Math.round((100 - sum) * 10) / 10)

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium text-muted-foreground">{title}</p>
      {view === 'block' ? (
        <BlockView slices={slices} others={others} labelOf={labelOf} t={t} />
      ) : (
        <LineView slices={slices} others={others} labelOf={labelOf} t={t} />
      )}
    </div>
  )
}

interface ViewProps {
  slices: WeightedSlice[]
  others: number
  labelOf: (raw: string) => string
  t: TFunction
}

// Labelled segments inside one proportional bar — the original "block" rendering.
function BlockView({ slices, others, labelOf, t }: ViewProps) {
  return (
    <PartitionBar size="md" gap={1}>
      {slices.map((slice, i) => (
        <PartitionBarSegment
          key={`${slice.label}-${i}`}
          num={slice.percent}
          variant={SEGMENT_VARIANTS[i % SEGMENT_VARIANTS.length]}
          alignment="left"
        >
          <PartitionBarSegmentTitle>{labelOf(slice.label)}</PartitionBarSegmentTitle>
          <PartitionBarSegmentValue>{slice.percent.toFixed(1)}%</PartitionBarSegmentValue>
        </PartitionBarSegment>
      ))}
      {others > 0.5 && (
        <PartitionBarSegment num={others} variant="muted" alignment="left">
          <PartitionBarSegmentTitle>{t('holdings.insight.others')}</PartitionBarSegmentTitle>
          <PartitionBarSegmentValue>{others.toFixed(1)}%</PartitionBarSegmentValue>
        </PartitionBarSegment>
      )}
    </PartitionBar>
  )
}

// Slim colour-only bar + wrapping legend — the mobile-friendly "line" rendering.
function LineView({ slices, others, labelOf, t }: ViewProps) {
  const items: { label: string; percent: number; color: string }[] = slices.map((slice, i) => ({
    label: labelOf(slice.label),
    percent: slice.percent,
    color: PALETTE[i % PALETTE.length],
  }))
  if (others > 0.5) {
    items.push({ label: t('holdings.insight.others'), percent: others, color: OTHERS_COLOR })
  }

  return (
    <div className="space-y-2">
      {/* Proportional bar — colour only, stays legible at any width. */}
      <PartitionBar size="sm" gap={0.5} className="min-h-0">
        {items.map((item, i) => (
          <PartitionBarSegment
            key={`${item.label}-${i}`}
            num={item.percent}
            className={cn('min-h-2.5 rounded-sm px-0 py-0', item.color)}
          />
        ))}
      </PartitionBar>

      {/* Legend — wraps to as many columns as fit, so it reads on a phone. */}
      <ul className="flex flex-wrap gap-x-3 gap-y-1">
        {items.map((item, i) => (
          <li key={`${item.label}-${i}`} className="flex min-w-0 items-center gap-1.5 text-xs">
            <span className={cn('size-2 shrink-0 rounded-[2px]', item.color)} aria-hidden />
            <span className="truncate text-foreground">{item.label}</span>
            <span className="shrink-0 tabular-nums text-muted-foreground">{item.percent.toFixed(1)}%</span>
          </li>
        ))}
      </ul>
    </div>
  )
}
