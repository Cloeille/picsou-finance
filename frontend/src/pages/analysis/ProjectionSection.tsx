import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { X } from '@phosphor-icons/react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { NumericInput } from '@/components/shared/NumericInput'
import { AllocationTrajectory } from './AllocationTrajectory'
import {
  ChartContainer,
  ChartTooltip,
  type ChartConfig,
} from '@/components/ui/chart'
import { useProjection, useProjectionDateLabel } from '@/features/analysis/hooks'
import { useMoney } from '@/hooks/use-money'
import { MONEY_AXIS_WIDTH, signedAmountClass } from '@/lib/money'
import { MoneyChartTooltip } from '@/components/shared/MoneyChartTooltip'
import { cn, formatPercent } from '@/lib/utils'
import type { ProjectionScenario } from '@/types/api'
import { AXIS_PROPS, AXIS_PROPS_FLUSH } from '@/lib/chart-axis'
import { parseCustomRate, projectCustomRate } from './custom-projection'

const HORIZONS = [10, 20, 30] as const

const SCENARIO_COLORS: Record<ProjectionScenario['key'], string> = {
  PESSIMISTIC: 'var(--chart-4)',
  CAUTIOUS: 'var(--chart-3)',
  REFERENCE: 'var(--chart-2)',
  OPTIMISTIC: 'var(--chart-1)',
}

const CUSTOM_KEY = 'CUSTOM'
const CUSTOM_COLOR = 'var(--primary)'
const CONTRIBUTED_COLOR = 'var(--color-muted-foreground)'
const CONTRIBUTED_KEY = 'contributed'

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
  const [view, setView] = useState<'wealth' | 'allocation'>('wealth')
  const [hidden, setHidden] = useState<Set<string>>(() => new Set())
  const [customRateRaw, setCustomRateRaw] = useState('')
  const money = useMoney()
  const projection = useProjection(years)

  const customRate = useMemo(() => parseCustomRate(customRateRaw), [customRateRaw])

  const customRateFormatted = useMemo(() => {
    if (customRate == null) return null
    return formatPercent(customRate, { fractionDigits: Number.isInteger(customRate) ? 0 : 1 })
  }, [customRate])

  const customPoints = useMemo(() => {
    const first = projection.data?.scenarios?.[0]
    if (!first || customRate == null) return null
    return projectCustomRate(first.points, customRate)
  }, [projection.data, customRate])

  const customFinal = useMemo(() => {
    if (!customPoints || customPoints.length === 0) return null
    const last = customPoints[customPoints.length - 1]
    return {
      valueEur: last.valueEur,
      gainEur: Math.round((last.valueEur - last.contributedEur) * 100) / 100,
    }
  }, [customPoints])

  const config = useMemo<ChartConfig>(() => {
    const entries: Array<[string, { label: string; color: string }]> = (
      projection.data?.scenarios ?? []
    ).map((s) => [
      s.key,
      {
        label: t(`analysis.projection.scenarios.${s.key}`, {
          value: formatPercent(s.annualPercent),
        }),
        color: SCENARIO_COLORS[s.key],
      },
    ])
    if (customRateFormatted != null) {
      entries.push([
        CUSTOM_KEY,
        {
          label: t('analysis.projection.scenarios.CUSTOM', {
            value: customRateFormatted,
          }),
          color: CUSTOM_COLOR,
        },
      ])
    }
    return Object.fromEntries(entries) as ChartConfig
  }, [projection.data, customRateFormatted, t])

  const rows = useMemo(() => {
    const scenarios = projection.data?.scenarios ?? []
    const first = scenarios[0]
    if (!first) return []
    return first.points.map((point, i) => {
      const row: Record<string, string | number> = {
        date: point.date,
        contributed: point.contributedEur,
      }
      for (const scenario of scenarios) {
        row[scenario.key] = scenario.points[i]?.valueEur ?? 0
      }
      if (customPoints) {
        row[CUSTOM_KEY] = customPoints[i]?.valueEur ?? 0
      }
      return row
    })
  }, [projection.data, customPoints])

  const yMax = useMemo(() => {
    let visibleMax = 0
    let allMax = 0
    for (const row of rows) {
      for (const [key, value] of Object.entries(row)) {
        if (key === 'date' || typeof value !== 'number') continue
        allMax = Math.max(allMax, value)
        if (!hidden.has(key)) visibleMax = Math.max(visibleMax, value)
      }
    }
    return niceCeiling(visibleMax > 0 ? visibleMax : allMax)
  }, [rows, hidden])

  const referenceFinal = useMemo(() => {
    const scenarios = projection.data?.scenarios ?? []
    const ref = scenarios.find((s) => s.key === 'REFERENCE') ?? scenarios[0]
    const last = ref?.points[ref.points.length - 1]
    return last ? { valueEur: last.valueEur, contributedEur: last.contributedEur } : null
  }, [projection.data])

  const legend = useMemo(
    () => [
      { key: CONTRIBUTED_KEY, label: t('analysis.projection.contributed'), color: CONTRIBUTED_COLOR },
      ...(projection.data?.scenarios ?? []).map((scenario) => ({
        key: scenario.key,
        label: t(`analysis.projection.scenarios.${scenario.key}`, {
          value: formatPercent(scenario.annualPercent),
        }),
        color: SCENARIO_COLORS[scenario.key],
      })),
      ...(customRateFormatted != null
        ? [
            {
              key: CUSTOM_KEY,
              label: t('analysis.projection.scenarios.CUSTOM', {
                value: customRateFormatted,
              }),
              color: CUSTOM_COLOR,
            },
          ]
        : []),
    ],
    [projection.data, customRateFormatted, t],
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
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <CardTitle>{t('analysis.projection.title')}</CardTitle>
        <div className="flex flex-wrap items-center gap-2">
          <div className="inline-flex items-center gap-1 rounded-lg bg-muted p-1">
            {(['wealth', 'allocation'] as const).map((key) => (
              <button
                key={key}
                type="button"
                aria-pressed={view === key}
                onClick={() => setView(key)}
                className={cn(
                  'inline-flex h-8 items-center justify-center rounded-md px-3 text-xs font-medium transition-colors',
                  view === key
                    ? 'bg-background text-foreground shadow-xs'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {t(`analysis.projection.views.${key}`)}
              </button>
            ))}
          </div>
          <div className="inline-flex items-center gap-1 rounded-lg bg-muted p-1">
            {HORIZONS.map((horizon) => (
              <button
                key={horizon}
                type="button"
                aria-pressed={horizon === years}
                onClick={() => setYears(horizon)}
                className={cn(
                  'inline-flex h-8 items-center justify-center rounded-md px-3 text-xs font-medium tabular-nums transition-colors',
                  horizon === years
                    ? 'bg-background text-foreground shadow-xs'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {t('analysis.projection.years', { count: horizon })}
              </button>
            ))}
          </div>
        </div>
      </CardHeader>
      <CardContent>
        {!projection.data ? null : !hasSomethingToProject ? (
          <p className="text-sm text-muted-foreground">{t('analysis.projection.nothingToProject')}</p>
        ) : (
          <>
            <div className="mb-5 grid gap-3 sm:grid-cols-3">
              <div className="rounded-xl bg-muted/40 px-4 py-3">
                <p className="text-xs text-muted-foreground">{t('analysis.projection.basis')}</p>
                <p className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                  <CurrencyDisplay value={projection.data.baseValueEur} />
                </p>
              </div>
              <div className="rounded-xl bg-muted/40 px-4 py-3">
                <p className="text-xs text-muted-foreground">{t('analysis.projection.monthly')}</p>
                <p className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                  <CurrencyDisplay value={projection.data.monthlyInflowEur} />
                </p>
              </div>
              {referenceFinal && (
                <div className="rounded-xl bg-muted/40 px-4 py-3">
                  <p className="text-xs text-muted-foreground">
                    {t('analysis.projection.referenceAt', { count: years })}
                  </p>
                  <p className="mt-1 text-lg font-semibold tabular-nums text-foreground">
                    <CurrencyDisplay value={referenceFinal.valueEur} />
                  </p>
                </div>
              )}
            </div>

            {view === 'allocation' ? (
              <AllocationTrajectory allocation={projection.data.allocation} />
            ) : (
              <>
                <div className="mb-5 flex flex-wrap items-center justify-between gap-x-4 gap-y-3 rounded-xl border border-border/60 bg-muted/25 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-2.5">
                    <Label
                      htmlFor="projection-custom-rate"
                      className="text-sm font-medium text-foreground"
                    >
                      {t('analysis.projection.customRateLabel')}
                    </Label>
                    <div className="relative w-24">
                      <NumericInput
                        id="projection-custom-rate"
                        value={customRateRaw}
                        onChange={(e) => setCustomRateRaw(e.target.value)}
                        placeholder="12"
                        aria-label={t('analysis.projection.customRateLabel')}
                        className="h-8 bg-background pr-7 text-sm tabular-nums"
                      />
                      <span
                        className="pointer-events-none absolute inset-y-0 right-2.5 flex items-center text-xs text-muted-foreground"
                        aria-hidden="true"
                      >
                        %
                      </span>
                    </div>
                    {customRateRaw !== '' && (
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        className="size-8 text-muted-foreground hover:text-foreground"
                        aria-label={t('analysis.projection.customRateClear')}
                        onClick={() => setCustomRateRaw('')}
                      >
                        <X className="size-4" />
                      </Button>
                    )}
                  </div>

                  {customFinal && customRateFormatted != null ? (
                    <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 text-sm tabular-nums">
                      <span className="text-muted-foreground">
                        {t('analysis.projection.customRateResult', {
                          count: years,
                          value: customRateFormatted,
                        })}
                      </span>
                      <span className="text-base font-semibold text-foreground">
                        <CurrencyDisplay value={customFinal.valueEur} />
                      </span>
                      {customFinal.gainEur !== 0 && (
                        <span className={cn('text-sm font-medium', signedAmountClass(customFinal.gainEur))}>
                          (<CurrencyDisplay value={customFinal.gainEur} showSign />{' '}
                          {t('analysis.projection.customRateGains')})
                        </span>
                      )}
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      {t('analysis.projection.customRateHint')}
                    </p>
                  )}
                </div>

                <ChartContainer config={config} className={cn('aspect-auto h-[320px] w-full')}>
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
                      {...AXIS_PROPS}
                      tickFormatter={(value: string) => value.slice(0, 4)}
                    />
                    <YAxis
                      {...AXIS_PROPS_FLUSH}
                      width={MONEY_AXIS_WIDTH}
                      domain={[0, yMax]}
                      tickFormatter={money.tick}
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

                <ul className="mt-4 flex flex-wrap gap-x-4 gap-y-1.5">
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
              </>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
