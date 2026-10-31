import { useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, ReferenceLine, XAxis, YAxis } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { type ChartConfig, ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { localeFromLanguage } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { LoanInstallment } from '@/types/api'

interface LoanAmortizationChartProps {
  schedule: LoanInstallment[]
}

interface ChartPoint {
  date: string
  remaining: number
  isPast: boolean
}

function buildPoints(schedule: LoanInstallment[]): ChartPoint[] {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return schedule.map(inst => ({
    date: inst.date,
    remaining: inst.remainingBalance,
    isPast: new Date(inst.date) <= today,
  }))
}

function findTodayMarker(points: ChartPoint[]): string | null {
  const lastPast = [...points].reverse().find(p => p.isPast)
  return lastPast?.date ?? null
}

function AmortizationTooltip({ active, payload, labels }: {
  active?: boolean
  payload?: Array<{ value: number; payload: ChartPoint }>
  labels: { remaining: string; locale: string; currency: string }
}) {
  const money = useMoney()
  if (!active || !payload?.length) return null
  const item = payload[0]
  const dateStr = new Date(item.payload.date).toLocaleDateString(labels.locale, {
    month: 'long', year: 'numeric',
  })
  return (
    <div className="rounded-xl bg-popover px-3 py-2.5 text-xs text-popover-foreground shadow-lg ring-1 ring-foreground/5 dark:ring-foreground/10">
      <div className="mb-1.5 font-medium capitalize">{dateStr}</div>
      <div className="flex items-center gap-2 py-0.5">
        <div className="h-0.5 w-4 shrink-0 rounded-full" style={{ backgroundColor: 'var(--color-remaining)' }} />
        <span className="text-muted-foreground">{labels.remaining}</span>
        <span className="ml-auto font-mono font-medium tabular-nums">
          {money.amount(item.value, labels.currency)}
        </span>
      </div>
    </div>
  )
}

export function LoanAmortizationChart({ schedule }: LoanAmortizationChartProps) {
  const money = useMoney()
  const { t, i18n } = useTranslation()

  const points = useMemo(() => buildPoints(schedule), [schedule])
  const todayMarker = useMemo(() => findTodayMarker(points), [points])

  const xInterval = useMemo(() => {
    const len = points.length
    if (len <= 8) return 0
    return Math.floor(len / 6)
  }, [points.length])

  const yTickFormatter = useMemo(() => {
    const maxVal = points.length ? Math.max(...points.map(p => p.remaining)) : 0
    if (maxVal >= 1_000_000) return (v: number) => `${(v / 1_000_000).toFixed(1)}M`
    if (maxVal >= 100_000) return (v: number) => `${(v / 1_000).toFixed(0)}k`
    if (maxVal >= 10_000) return (v: number) => `${(v / 1_000).toFixed(1)}k`
    if (maxVal >= 1_000) return (v: number) => `${(v / 1_000).toFixed(2)}k`
    return (v: number) => v.toFixed(0)
  }, [points])

  const chartConfig = useMemo(() => ({
    remaining: {
      label: t('debt.remainingBalance'),
      color: 'var(--chart-1)',
    },
  }) satisfies ChartConfig, [t])

  const labels = useMemo(() => ({
    remaining: t('debt.remainingBalance'),
    locale: localeFromLanguage(i18n.resolvedLanguage ?? i18n.language),
    currency: 'EUR',
  }), [t, i18n.resolvedLanguage, i18n.language])

  const xAxisFormatter = (value: string) =>
    new Date(value).toLocaleDateString(labels.locale, { month: 'short', year: '2-digit' })

  if (points.length === 0) return null

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{t('debt.amortizationChart')}</CardTitle>
      </CardHeader>
      <CardContent>
        <ChartContainer config={chartConfig} className="h-[250px] w-full [&>div>div]:!w-full [&>div>div>svg]:!w-full">
          <AreaChart data={points} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="fillRemaining" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--color-remaining)" stopOpacity={0.3} />
                <stop offset="95%" stopColor="var(--color-remaining)" stopOpacity={0.05} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="date"
              tickLine={false}
              axisLine={false}
              tickMargin={8}
              tickFormatter={xAxisFormatter}
              interval={xInterval}
            />
            <YAxis
              tickLine={false}
              axisLine={false}
              tickMargin={8}
              tickFormatter={money.tick(yTickFormatter)}
              width={45}
              tickCount={5}
            />
            <ChartTooltip content={<AmortizationTooltip labels={labels} />} />
            <Area
              dataKey="remaining"
              type="monotone"
              fill="url(#fillRemaining)"
              stroke="var(--color-remaining)"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4 }}
            />
            {todayMarker && (
              <ReferenceLine
                x={todayMarker}
                stroke="var(--muted-foreground)"
                strokeDasharray="4 4"
                label={{
                  value: t('debt.today'),
                  position: 'top',
                  fill: 'var(--muted-foreground)',
                  fontSize: 11,
                }}
              />
            )}
          </AreaChart>
        </ChartContainer>
      </CardContent>
    </Card>
  )
}
