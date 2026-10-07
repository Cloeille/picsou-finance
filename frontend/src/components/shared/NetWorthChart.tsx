import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, Legend, Line, ReferenceLine, XAxis, YAxis } from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { TimeRangeSelector, type TimeRange } from '@/components/shared/TimeRangeSelector'
import { formatDate, localeFromLanguage } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import { EmptyChartState } from '@/components/shared/EmptyChartState'
import type { IntradayPoint } from '@/features/dashboard/api'

interface TrajectoryOverlay {
  startDate: string  // ISO instant or date
  startValue: number
  endDate: string    // ISO date
  endValue: number
  label: string      // tooltip + legend label
}

interface NetWorthChartProps {
  data: { date: string; total: number; invested?: number; pnl?: number }[]
  intraday?: IntradayPoint[]
  range: TimeRange
  onRangeChange: (range: TimeRange) => void
  // Hide the "Capital invested" dashed line + legend entry. When omitted, the
  // line is shown only if at least one data point carries `invested`.
  showInvested?: boolean
  // Optional ideal-trajectory overlay: linear line from (startDate, startValue)
  // to (endDate, endValue). Clamps outside its span. On ALL range, X axis is
  // stretched to endDate so the user can see the projection beyond today.
  target?: TrajectoryOverlay
  // Optional forecast overlay: same shape as target, but only rendered from
  // startDate onward (null before). Use for "at current pace" projections that
  // should visually continue past the last real data point.
  projection?: TrajectoryOverlay
  // Optional vertical reference line at a given timestamp (ms). Used to anchor
  // the boundary between past data and future projection.
  todayMs?: number
  // Optional label for the today reference line.
  todayLabel?: string
}

function NetWorthTooltip({ active, payload, labels, is24H, showGainLoss }: {
  active?: boolean
  payload?: Array<{
    value: number
    dataKey: string
    payload: { date?: string; timestamp?: string; total: number | null; invested?: number; pnl?: number; target?: number; projection?: number }
  }>
  labels: { total: string; invested: string; target: string; projection: string; gainLoss: string; locale: string; currency: string }
  is24H: boolean
  // Callers that hide investment info (showInvested={false}, e.g. goal charts)
  // must not surface a gain/loss row either.
  showGainLoss: boolean
}) {
  // Above the early return, as hook order demands. The tooltip subscribes for itself rather than
  // reading a formatter passed down, so one that is open when the toggle flips redraws with it.
  const money = useMoney()
  if (!active || !payload?.length) return null

  // Prefer the total item for label/date; fall back to any payload entry so the
  // tooltip still works for synthetic future points where total is null.
  const totalItem = payload.find(p => p.dataKey === 'total' && p.value != null)
  const anchorItem = totalItem ?? payload[0]
  if (!anchorItem) return null

  const total = totalItem ? (totalItem.value as number) : null
  const investedItem = payload.find(p => p.dataKey === 'invested' && p.value != null)
  const hasInvested = investedItem != null
  const invested = hasInvested ? (investedItem.value as number) : 0
  // Gain/loss comes from the backend's debt-neutral pnl field — never recomputed
  // locally, so debt can't be read as an investment loss (issue #18). Intraday
  // points carry no pnl → the row is omitted.
  const rowPnl = anchorItem.payload?.pnl
  const gainLoss = typeof rowPnl === 'number' ? rowPnl : null
  const targetItem = payload.find(p => p.dataKey === 'target' && p.value != null)
  const target = targetItem ? (targetItem.value as number) : null
  const projectionItem = payload.find(p => p.dataKey === 'projection' && p.value != null)
  const projection = projectionItem ? (projectionItem.value as number) : null

  const dateStr = is24H ? anchorItem.payload?.timestamp : anchorItem.payload?.date
  const formattedDate = is24H && dateStr
    ? new Date(dateStr).toLocaleString(labels.locale, { hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })
    : formatDate(dateStr, labels.locale)

  return (
    <div className="rounded-xl bg-popover px-3 py-2.5 text-xs text-popover-foreground shadow-lg ring-1 ring-foreground/5 dark:ring-foreground/10">
      <div className="mb-1.5 font-medium">{formattedDate}</div>
      {total != null && (
        <div className="flex items-center gap-2 py-0.5">
          <div
            className="h-0.5 w-4 shrink-0 rounded-full"
            style={{ backgroundColor: 'var(--color-total)' }}
          />
          <span className="text-muted-foreground">{labels.total}</span>
          <span className="ml-auto font-mono font-medium tabular-nums">
            {money.amount(total, labels.currency)}
          </span>
        </div>
      )}
      {hasInvested && (
        <div className="flex items-center gap-2 py-0.5">
          <div
            className="h-0.5 w-4 shrink-0 border-t-2 border-dashed"
            style={{ borderColor: 'var(--color-invested)' }}
          />
          <span className="text-muted-foreground">{labels.invested}</span>
          <span className="ml-auto font-mono font-medium tabular-nums">
            {money.amount(invested, labels.currency)}
          </span>
        </div>
      )}
      {target != null && (
        <div className="flex items-center gap-2 py-0.5">
          <div
            className="h-0.5 w-4 shrink-0 border-t-2 border-dashed"
            style={{ borderColor: 'var(--color-target)' }}
          />
          <span className="text-muted-foreground">{labels.target}</span>
          <span className="ml-auto font-mono font-medium tabular-nums">
            {money.amount(target, labels.currency)}
          </span>
        </div>
      )}
      {projection != null && (
        <div className="flex items-center gap-2 py-0.5">
          <div
            className="h-0.5 w-4 shrink-0 border-t-2 border-dotted"
            style={{ borderColor: 'var(--color-projection)' }}
          />
          <span className="text-muted-foreground">{labels.projection}</span>
          <span className="ml-auto font-mono font-medium tabular-nums">
            {money.amount(projection, labels.currency)}
          </span>
        </div>
      )}
      {showGainLoss && gainLoss !== null && (
        <>
          <div className="my-1.5 border-t border-border" />
          <div className="flex items-center justify-between gap-3 py-0.5">
            <span className={`font-mono font-medium tabular-nums ${gainLoss >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
              {gainLoss >= 0 ? '+' : ''}{money.amount(gainLoss, labels.currency)}
            </span>
            <span className="text-muted-foreground">{labels.gainLoss}</span>
          </div>
        </>
      )}
    </div>
  )
}

function filterByRange(data: NetWorthChartProps['data'], range: TimeRange) {
  if (!Array.isArray(data)) return []
  if (range === 'ALL') return data
  const now = new Date()
  let from: Date
  switch (range) {
    case '7D': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7); break
    case '1M': from = new Date(now.getFullYear(), now.getMonth() - 1, now.getDate()); break
    case '3M': from = new Date(now.getFullYear(), now.getMonth() - 3, now.getDate()); break
    case 'YTD': from = new Date(now.getFullYear(), 0, 1); break
    case '1Y': from = new Date(now.getFullYear() - 1, now.getMonth(), now.getDate()); break
    default: return data
  }
  return data.filter(p => new Date(p.date) >= from)
}

// Span-aware formatter -- the X axis is now a time scale fed numeric
// timestamps, so we pick the format based on the actual visible span rather
// than the (potentially-misleading) range button. Goals can stretch a short
// "ALL" view across many months once the projection to deadline is drawn,
// for example.
function getXAxisFormatter(range: TimeRange, locale: string, spanMs: number) {
  if (range === '24H') {
    return (value: string | number) =>
      new Date(value).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false })
  }
  const day = 86_400_000
  if (spanMs <= 60 * day) {
    return (value: string | number) =>
      new Date(value).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
  }
  if (spanMs <= 2 * 365 * day) {
    return (value: string | number) =>
      new Date(value).toLocaleDateString(locale, { month: 'short' })
  }
  return (value: string | number) =>
    new Date(value).toLocaleDateString(locale, { month: 'short', year: '2-digit' })
}

export function NetWorthChart({ data, intraday = [], range, onRangeChange, showInvested = true, target, projection, todayMs, todayLabel }: NetWorthChartProps) {
  const money = useMoney()
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const is24H = range === '24H'
  const showDots = range === '24H' || range === '7D'

  const targetAt = useMemo(() => {
    if (!target) return null
    const startMs = new Date(target.startDate).getTime()
    const endMs = new Date(target.endDate).getTime()
    const span = endMs - startMs
    if (!Number.isFinite(span) || span <= 0) return null
    const slope = (target.endValue - target.startValue) / span
    return (iso: string) => {
      const t = new Date(iso).getTime()
      if (!Number.isFinite(t)) return null
      const clamped = Math.min(Math.max(t, startMs), endMs)
      return target.startValue + slope * (clamped - startMs)
    }
  }, [target])

  // Unlike `targetAt`, the projection only exists from its start onward -- it
  // is a forecast, not a frame for historical data. Returns null before the
  // start, linearly interpolates between start and end (clamped on the right
  // so the line doesn't drift past the deadline).
  const projectionAt = useMemo(() => {
    if (!projection) return null
    const startMs = new Date(projection.startDate).getTime()
    const endMs = new Date(projection.endDate).getTime()
    const span = endMs - startMs
    if (!Number.isFinite(span) || span <= 0) return null
    const slope = (projection.endValue - projection.startValue) / span
    return (iso: string) => {
      const t = new Date(iso).getTime()
      if (!Number.isFinite(t) || t < startMs) return null
      const clamped = Math.min(t, endMs)
      return projection.startValue + slope * (clamped - startMs)
    }
  }, [projection])

  const filteredData = useMemo(() => {
    const base = is24H
      ? intraday.map(p => ({
          date: p.timestamp,
          timestamp: p.timestamp,
          dateMs: new Date(p.timestamp).getTime(),
          total: p.total as number | null,
          invested: p.invested,
          // Dashboard intraday points carry no pnl (row omitted); synthetic
          // intraday series (holding modal) may provide one.
          pnl: p.pnl,
        }))
      : filterByRange(data, range).map(p => ({
          ...p,
          dateMs: new Date(p.date).getTime(),
          total: p.total as number | null,
        }))

    // When a target is set, crop history on the left to the trajectory start
    // (goal createdAt). Balance changes before the goal existed aren't
    // "savings progress" -- showing them would make the chart misleading.
    const cropped = (() => {
      if (!target) return base
      const startMs = new Date(target.startDate).getTime()
      if (!Number.isFinite(startMs)) return base
      return base.filter(p => p.dateMs >= startMs)
    })()

    // Decorate each visible point with the interpolated target/projection.
    const decorated = cropped.map(p => ({
      ...p,
      target: targetAt ? (targetAt(p.date) ?? undefined) : undefined,
      projection: projectionAt ? (projectionAt(p.date) ?? undefined) : undefined,
    }))

    // On the ALL range, project the X axis up to the deadline so the user sees
    // the full trajectory. The synthetic point carries only target/projection
    // -- the actual area stops where real data ends. With the time-scale axis,
    // this point is placed at its real date so the projection occupies the
    // right fraction of the chart instead of being squished into the last
    // category.
    if (target && range === 'ALL' && targetAt) {
      const last = decorated[decorated.length - 1]
      const deadlineMs = new Date(target.endDate).getTime()
      const lastMs = last?.dateMs ?? -Infinity
      if (Number.isFinite(deadlineMs) && deadlineMs > lastMs) {
        decorated.push({
          date: target.endDate,
          dateMs: deadlineMs,
          total: null,
          invested: undefined,
          target: target.endValue,
          projection: projectionAt ? (projectionAt(target.endDate) ?? undefined) : undefined,
        } as typeof decorated[number])
      }
    }
    return decorated
  }, [data, intraday, range, is24H, target, targetAt, projectionAt])

  const xDomain = useMemo<[number, number] | undefined>(() => {
    if (filteredData.length === 0) return undefined
    return [filteredData[0].dateMs, filteredData[filteredData.length - 1].dateMs]
  }, [filteredData])

  const yTickFormatter = useMemo(() => {
    const totals: number[] = []
    for (const d of filteredData) {
      if (d.total != null) totals.push(d.total)
      if ('target' in d && typeof d.target === 'number') totals.push(d.target)
      if ('projection' in d && typeof d.projection === 'number') totals.push(d.projection)
    }
    const maxVal = totals.length ? Math.max(...totals) : 0
    if (maxVal >= 1_000_000) return (v: number) => `${(v / 1_000_000).toFixed(1)}M`
    if (maxVal >= 100_000) return (v: number) => `${(v / 1_000).toFixed(0)}k`
    if (maxVal >= 10_000) return (v: number) => `${(v / 1_000).toFixed(1)}k`
    if (maxVal >= 1_000) return (v: number) => `${(v / 1_000).toFixed(2)}k`
    if (maxVal >= 100) return (v: number) => v.toFixed(0)
    return (v: number) => v.toFixed(2)
  }, [filteredData])

  const chartConfig = useMemo(() => ({
    total: {
      label: t('dashboard.netWorth'),
      color: 'var(--chart-1)',
    },
    invested: {
      label: t('dashboard.invested'),
      color: 'var(--chart-5)',
    },
    target: {
      label: target?.label ?? '',
      color: 'var(--chart-3)',
    },
    // Forecast colour intentionally neutral -- the whole chart-* palette is
    // a single green hue, so a chart-* variant would compete with the target
    // for "ideal" semantics. Muted-foreground reads as informational/forecast.
    projection: {
      label: projection?.label ?? '',
      color: 'var(--muted-foreground)',
    },
  }) satisfies ChartConfig, [t, target?.label, projection?.label])

  const labels = useMemo(() => ({
    total: t('dashboard.netWorth'),
    invested: t('dashboard.invested'),
    target: target?.label ?? '',
    projection: projection?.label ?? '',
    gainLoss: t('dashboard.gainLoss'),
    locale,
    currency: 'EUR',
  }), [t, locale, target?.label, projection?.label])

  const xAxisFormatter = useMemo(() => {
    const spanMs = xDomain ? xDomain[1] - xDomain[0] : 0
    return getXAxisFormatter(range, locale, spanMs)
  }, [range, locale, xDomain])

  const isEmpty24H = is24H && filteredData.length === 0

  // The dashed "Capital invested" line is only drawn when the caller opts in
  // AND the data actually carries an `invested` value -- prevents legend lies.
  const hasInvestedSeries = showInvested && filteredData.some(d => d.invested != null)
  const hasTargetSeries = target != null && filteredData.some(d => 'target' in d && typeof d.target === 'number')
  const hasProjectionSeries = projection != null && filteredData.some(d => 'projection' in d && typeof d.projection === 'number')
  // Show the "today" guide only when we actually have future trajectory beyond
  // the last real data point -- otherwise the guide adds noise without helping
  // the user separate "past" from "future" visually.
  const showTodayGuide = todayMs != null && Number.isFinite(todayMs) && (hasTargetSeries || hasProjectionSeries) &&
    xDomain != null && todayMs >= xDomain[0] && todayMs <= xDomain[1]

  return (
    <div>
      <div className="flex justify-end mb-3">
        <TimeRangeSelector value={range} onChange={onRangeChange} />
      </div>
      {isEmpty24H ? (
        <EmptyChartState />
      ) : (
      <ChartContainer config={chartConfig} className="h-[250px] w-full [&>div>div]:!w-full [&>div>div>svg]:!w-full">
        <AreaChart data={filteredData} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="fillTotal" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="var(--color-total)" stopOpacity={0.32} />
            <stop offset="95%" stopColor="var(--color-total)" stopOpacity={0.04} />
          </linearGradient>
          {/* Subtle horizontal fade applied to the projection band so its right
              edge melts into the chart instead of stopping mid-air. */}
          <linearGradient id="fillProjection" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="var(--color-projection)" stopOpacity={0.14} />
            <stop offset="100%" stopColor="var(--color-projection)" stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis
          type="number"
          dataKey="dateMs"
          domain={xDomain ?? ['dataMin', 'dataMax']}
          scale="time"
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={xAxisFormatter}
        />
        <YAxis
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          tickFormatter={money.tick(yTickFormatter)}
          width={45}
          tickCount={5}
        />
        <ChartTooltip content={<NetWorthTooltip labels={labels} is24H={is24H} showGainLoss={showInvested} />} />
        <Area
          dataKey="total"
          type="monotone"
          fill="url(#fillTotal)"
          stroke="var(--color-total)"
          strokeWidth={2}
          dot={showDots ? { r: 3, fill: 'var(--color-total)', strokeWidth: 0 } : false}
          activeDot={{ r: 4 }}
        />
        {hasProjectionSeries && (
          // Render BEFORE target so target sits visually on top. The faint
          // fill bridges the visual gap between today's data and the deadline,
          // so the chart doesn't appear to end abruptly.
          <Area
            dataKey="projection"
            type="linear"
            fill="url(#fillProjection)"
            stroke="var(--color-projection)"
            strokeWidth={1.5}
            strokeDasharray="2 4"
            dot={false}
            activeDot={{ r: 3, fill: 'var(--color-projection)', strokeWidth: 0 }}
            connectNulls
          />
        )}
        {hasInvestedSeries && (
          <Line
            dataKey="invested"
            type="monotone"
            stroke="var(--color-invested)"
            strokeWidth={2}
            strokeDasharray="6 4"
            dot={false}
            activeDot={false}
          />
        )}
        {hasTargetSeries && (
          <Line
            dataKey="target"
            type="linear"
            stroke="var(--color-target)"
            strokeWidth={2}
            strokeDasharray="4 4"
            dot={false}
            activeDot={false}
            connectNulls
          />
        )}
        {showTodayGuide && (
          <ReferenceLine
            x={todayMs}
            stroke="var(--muted-foreground)"
            strokeOpacity={0.35}
            strokeDasharray="2 4"
            label={todayLabel
              ? { value: todayLabel, position: 'insideTopLeft', fill: 'var(--muted-foreground)', fontSize: 10, dy: 4, dx: 4 }
              : undefined}
          />
        )}
        <Legend content={() => (
          <div className="flex flex-wrap items-center justify-center gap-x-5 gap-y-1 pt-2">
            <div className="flex items-center gap-1.5 text-xs">
              <div
                className="h-0.5 w-4 rounded-full"
                style={{ backgroundColor: 'var(--color-total)' }}
              />
              <span className="text-muted-foreground">{labels.total}</span>
            </div>
            {hasInvestedSeries && (
              <div className="flex items-center gap-1.5 text-xs">
                <div
                  className="h-0.5 w-4 border-t-2 border-dashed"
                  style={{ borderColor: 'var(--color-invested)' }}
                />
                <span className="text-muted-foreground">{labels.invested}</span>
              </div>
            )}
            {hasTargetSeries && (
              <div className="flex items-center gap-1.5 text-xs">
                <div
                  className="h-0.5 w-4 border-t-2 border-dashed"
                  style={{ borderColor: 'var(--color-target)' }}
                />
                <span className="text-muted-foreground">{labels.target}</span>
              </div>
            )}
            {hasProjectionSeries && (
              <div className="flex items-center gap-1.5 text-xs">
                <div
                  className="h-0.5 w-4 border-t-2 border-dotted"
                  style={{ borderColor: 'var(--color-projection)' }}
                />
                <span className="text-muted-foreground">{labels.projection}</span>
              </div>
            )}
          </div>
        )} />
      </AreaChart>
    </ChartContainer>
      )}
    </div>
  )
}
