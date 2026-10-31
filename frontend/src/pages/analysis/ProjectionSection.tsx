import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { AllocationTrajectory } from './AllocationTrajectory'
import {
  ChartContainer,
  ChartTooltip,
  type ChartConfig,
} from '@/components/ui/chart'
import { useProjection, useProjectionDateLabel } from '@/features/analysis/hooks'
import { useMoney } from '@/hooks/use-money'
import { MoneyChartTooltip } from '@/components/shared/MoneyChartTooltip'
import { cn } from '@/lib/utils'
import type { ProjectionScenario } from '@/types/api'

const HORIZONS = [10, 20, 30] as const

/**
 * The theme's chart tokens are a single-hue ramp, not five distinct hues — which suits ordered
 * data exactly. Spread across it darkest-to-lightest so "more optimistic" reads as "brighter",
 * skipping chart-5 because it disappears against a dark background at a 2px stroke.
 */
const SCENARIO_COLORS: Record<ProjectionScenario['key'], string> = {
  PESSIMISTIC: 'var(--chart-4)',
  CAUTIOUS: 'var(--chart-3)',
  REFERENCE: 'var(--chart-2)',
  OPTIMISTIC: 'var(--chart-1)',
}

/** Contributions are drawn beneath every scenario, so they read as the floor the gain sits on. */
const CONTRIBUTED_COLOR = 'var(--color-muted-foreground)'

/** The series the legend can toggle: contributions first, then the scenarios in payload order. */
const CONTRIBUTED_KEY = 'contributed'

/**
 * The next round number at or above a value — 1, 2, 2.5 or 5 times a power of ten.
 *
 * <p>Needed because the Y domain is pinned rather than derived from what is on screen (see below),
 * and a hard-coded maximum of, say, 1 284 617 would put its ticks at 256 923 intervals.
 */
function niceCeiling(value: number): number {
  if (value <= 0) return 1
  const magnitude = 10 ** Math.floor(Math.log10(value))
  const scaled = value / magnitude
  const step = [1, 2, 2.5, 5, 10].find((candidate) => scaled <= candidate) ?? 10
  return step * magnitude
}

export function ProjectionSection() {
  const { t } = useTranslation()
  const dateLabel = useProjectionDateLabel()
  const [years, setYears] = useState<number>(20)
  // Two questions, one card: how much, and in what. Tabs rather than two cards because they
  // share a horizon — switching view must not lose it.
  const [view, setView] = useState<'wealth' | 'allocation'>('wealth')
  // Everything visible until the reader says otherwise. Hidden rather than shown, so a scenario
  // added later to the payload appears without anyone remembering to opt it in.
  const [hidden, setHidden] = useState<Set<string>>(() => new Set())
  const money = useMoney()
  const projection = useProjection(years)

  const config = useMemo<ChartConfig>(() => {
    const entries = (projection.data?.scenarios ?? []).map((s) => [
      s.key,
      {
        // The rate comes from the payload, never restated here: a label that disagrees with the
        // curve that produced it is worse than no label.
        label: t(`analysis.projection.scenarios.${s.key}`, {
          value: s.annualPercent.toFixed(1),
        }),
        color: SCENARIO_COLORS[s.key],
      },
    ])
    return Object.fromEntries(entries) as ChartConfig
  }, [projection.data, t])

  // Recharts wants one row per x value with a column per series.
  const rows = useMemo(() => {
    const scenarios = projection.data?.scenarios ?? []
    const first = scenarios[0]
    if (!first) return []
    return first.points.map((point, i) => {
      // The same for every scenario — it is capital in, not a return — so it is read off the
      // first and drawn once. The gap above it is the gain, which is the figure this chart was
      // computing all along and never showed.
      const row: Record<string, string | number> = {
        date: point.date,
        contributed: point.contributedEur,
      }
      for (const scenario of scenarios) {
        row[scenario.key] = scenario.points[i]?.valueEur ?? 0
      }
      return row
    })
  }, [projection.data])

  /**
   * Pinned to the full set of series, not to the visible ones.
   *
   * <p>Letting the axis breathe when a curve is hidden makes every remaining curve jump to a new
   * scale — so the act of hiding the optimistic line appears to move the pessimistic one, which
   * is the opposite of what the reader clicked for.
   */
  const yMax = useMemo(() => {
    let max = 0
    for (const row of rows) {
      for (const [key, value] of Object.entries(row)) {
        if (key !== 'date' && typeof value === 'number') max = Math.max(max, value)
      }
    }
    return niceCeiling(max)
  }, [rows])

  const legend = useMemo(
    () => [
      { key: CONTRIBUTED_KEY, label: t('analysis.projection.contributed'), color: CONTRIBUTED_COLOR },
      ...(projection.data?.scenarios ?? []).map((scenario) => ({
        key: scenario.key,
        label: t(`analysis.projection.scenarios.${scenario.key}`, {
          value: scenario.annualPercent.toFixed(1),
        }),
        color: SCENARIO_COLORS[scenario.key],
      })),
    ],
    [projection.data, t],
  )

  const toggle = (key: string) =>
    setHidden((previous) => {
      const next = new Set(previous)
      if (!next.delete(key)) next.add(key)
      return next
    })

  const hasSomethingToProject =
    (projection.data?.baseValueEur ?? 0) > 0 || (projection.data?.monthlyInflowEur ?? 0) > 0

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <CardTitle>{t('analysis.projection.title')}</CardTitle>
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex gap-1">
            {(['wealth', 'allocation'] as const).map((key) => (
              <Button
                key={key}
                variant={view === key ? 'default' : 'outline'}
                size="sm"
                aria-pressed={view === key}
                onClick={() => setView(key)}
              >
                {t(`analysis.projection.views.${key}`)}
              </Button>
            ))}
          </div>
          <div className="flex gap-1">
          {HORIZONS.map((horizon) => (
            <Button
              key={horizon}
              variant={horizon === years ? 'default' : 'outline'}
              size="sm"
              onClick={() => setYears(horizon)}
            >
              {t('analysis.projection.years', { count: horizon })}
            </Button>
          ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {!projection.data ? null : !hasSomethingToProject ? (
          <p className="text-sm text-muted-foreground">{t('analysis.projection.nothingToProject')}</p>
        ) : (
          <>
            {/* The base is stated because it is not the net worth on the dashboard: property,
                loans and alternative assets are out, and a reader has to know that. */}
            <p className="mb-4 text-sm text-muted-foreground">
              {t('analysis.projection.basis')}{' '}
              <CurrencyDisplay value={projection.data.baseValueEur} className="text-foreground" />
              {projection.data.monthlyInflowEur > 0 && (
                <>
                  {' · '}
                  {t('analysis.projection.monthly')}{' '}
                  <CurrencyDisplay
                    value={projection.data.monthlyInflowEur}
                    className="text-foreground"
                  />
                </>
              )}
            </p>

            {view === 'allocation' ? (
              <AllocationTrajectory allocation={projection.data.allocation} />
            ) : (
              <>
            <ChartContainer config={config} className={cn('h-[320px] w-full')}>
              <AreaChart data={rows} margin={{ left: 4, right: 8, top: 8, bottom: 0 }}>
                <defs>
                  {Object.keys(config).map((key) => (
                    <linearGradient key={key} id={`fill-${key}`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={`var(--color-${key})`} stopOpacity={0.25} />
                      <stop offset="100%" stopColor={`var(--color-${key})`} stopOpacity={0.02} />
                    </linearGradient>
                  ))}
                </defs>
                <CartesianGrid vertical={false} strokeDasharray="3 3" />
                <XAxis
                  dataKey="date"
                  tickLine={false}
                  axisLine={false}
                  tickMargin={8}
                  tickFormatter={(value: string) => value.slice(0, 4)}
                />
                <YAxis
                  tickLine={false}
                  axisLine={false}
                  width={70}
                  domain={[0, yMax]}
                  // Ticks over decades run to seven figures; the full currency format would not fit.
                  tickFormatter={(value: number) => money.compact(value, { maximumFractionDigits: 1 })}
                />
                <ChartTooltip
                  content={
                    <MoneyChartTooltip
                      config={config}
                      indicator="line"
                      labelFormatter={(value) => dateLabel(String(value))}
                    />
                  }
                />
                {!hidden.has(CONTRIBUTED_KEY) && (
                  <Area
                    type="monotone"
                    dataKey="contributed"
                    stroke={CONTRIBUTED_COLOR}
                    fill={CONTRIBUTED_COLOR}
                    fillOpacity={0.12}
                    strokeWidth={1}
                    strokeDasharray="4 3"
                    dot={false}
                    name={t('analysis.projection.contributed')}
                  />
                )}
                {Object.keys(config)
                  .filter((key) => !hidden.has(key))
                  .map((key) => (
                    <Area
                      key={key}
                      type="monotone"
                      dataKey={key}
                      stroke={`var(--color-${key})`}
                      fill={`url(#fill-${key})`}
                      strokeWidth={2}
                      dot={false}
                    />
                  ))}
              </AreaChart>
            </ChartContainer>

            {/* Rendered here rather than through Recharts' <Legend>, which sorts alphabetically
                and so scrambles a series whose whole meaning is its order. Each entry is also the
                control for its curve: four scenarios plus contributions is more than a 320px
                chart can separate, and comparing two of them means removing the other three. */}
            <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1">
              {legend.map((entry) => {
                const shown = !hidden.has(entry.key)
                return (
                  <li key={entry.key}>
                    <button
                      type="button"
                      aria-pressed={shown}
                      onClick={() => toggle(entry.key)}
                      className={cn(
                        'flex items-center gap-1.5 text-xs transition-opacity hover:opacity-100',
                        shown ? 'text-muted-foreground' : 'text-muted-foreground/50 line-through',
                      )}
                    >
                      <span
                        className={cn('size-2 shrink-0 rounded-full', !shown && 'opacity-40')}
                        style={{ backgroundColor: entry.color }}
                        aria-hidden="true"
                      />
                      {entry.label}
                    </button>
                  </li>
                )
              })}
            </ul>
            <p className="mt-2 text-xs text-muted-foreground">
              {t('analysis.projection.blendNote')}
            </p>
              </>
            )}

            <p className="mt-3 text-xs text-muted-foreground">
              {t('analysis.projection.disclaimer')}
            </p>
          </>
        )}
      </CardContent>
    </Card>
  )
}
