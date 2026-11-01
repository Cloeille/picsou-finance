import { Area, AreaChart, CartesianGrid, ReferenceLine, XAxis, YAxis } from 'recharts'
import { useTranslation } from 'react-i18next'
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from '@/components/ui/chart'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { localeFromLanguage } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import { MONEY_AXIS_WIDTH } from '@/lib/money'
import { usePropertyValuations } from '@/features/accounts/hooks'
import { AXIS_PROPS } from '@/lib/chart-axis'

interface PropertyValuationChartProps {
  accountId: number
  /** Purchase price plus fees, drawn as the break-even line. */
  costBasis: number
}

const chartConfig = {
  value: {
    label: 'Estimate',
    color: 'var(--chart-1)',
  },
} satisfies ChartConfig

/**
 * Estimated value over time against what the property actually cost.
 *
 * <p>Needs at least two estimates to say anything — with a single point there is no trend,
 * only a dot, so the chart stays hidden until the monthly job has run more than once.
 */
export function PropertyValuationChart({ accountId, costBasis }: PropertyValuationChartProps) {
  const money = useMoney()
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const { data = [] } = usePropertyValuations(accountId)

  if (data.length < 2) return null

  // The API returns newest first; a chart reads left to right.
  const points = [...data]
    .sort((a, b) => a.valuedAt.localeCompare(b.valuedAt))
    .map(v => ({ date: v.valuedAt, value: v.estimatedValue }))

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{t('property.chart.title')}</CardTitle>
      </CardHeader>
      <CardContent>
        <ChartContainer config={chartConfig} className="h-[220px] w-full">
          <AreaChart data={points} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="fillPropertyValue" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--color-value)" stopOpacity={0.3} />
                <stop offset="95%" stopColor="var(--color-value)" stopOpacity={0.05} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="date"
              {...AXIS_PROPS}
              tickFormatter={(value) =>
                new Date(value).toLocaleDateString(locale, { month: 'short', year: '2-digit' })}
            />
            <YAxis
              {...AXIS_PROPS}
              // Compact on the axis: full currency labels collide at this width, and the
              // tooltip already gives the exact figure.
              tickFormatter={money.tick}
              width={MONEY_AXIS_WIDTH}
            />
            <ChartTooltip
              content={<ChartTooltipContent formatter={(value) => money.amount(value as number)} />}
            />
            <Area
              dataKey="value"
              type="monotone"
              fill="url(#fillPropertyValue)"
              stroke="var(--color-value)"
              strokeWidth={2}
            />
            {costBasis > 0 && (
              <ReferenceLine
                y={costBasis}
                strokeDasharray="4 4"
                stroke="var(--muted-foreground)"
                label={{ value: t('property.chart.costBasis'), position: 'insideTopLeft', fontSize: 11 }}
              />
            )}
          </AreaChart>
        </ChartContainer>
      </CardContent>
    </Card>
  )
}
