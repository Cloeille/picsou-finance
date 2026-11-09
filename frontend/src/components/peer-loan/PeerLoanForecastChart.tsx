import { CartesianGrid, Line, LineChart, XAxis, YAxis } from 'recharts'
import { useTranslation } from 'react-i18next'
import { ChartContainer, ChartTooltip, type ChartConfig } from '@/components/ui/chart'
import { MoneyChartTooltip } from '@/components/shared/MoneyChartTooltip'
import { AXIS_PROPS } from '@/lib/chart-axis'
import { MONEY_AXIS_WIDTH } from '@/lib/money'
import { useMoney } from '@/hooks/use-money'
import { localeFromLanguage, toDate } from '@/lib/utils'
import type { PeerLoanCurvePoint } from '@/types/api'

interface PeerLoanForecastChartProps {
  data: PeerLoanCurvePoint[]
  /** Without a plan, only the actual capital is drawn. */
  hasPlan: boolean
}

/**
 * The actual remaining capital against the forecast. The dashed line is a plan, not a
 * contract: it never marks anything as paid, and the solid line is the ledger.
 */
export function PeerLoanForecastChart({ data, hasPlan }: PeerLoanForecastChartProps) {
  const { t, i18n } = useTranslation()
  const money = useMoney()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const chartConfig = {
    actual: { label: t('peerLoan.forecast.actual'), color: 'var(--chart-1)' },
    scheduled: { label: t('peerLoan.forecast.scheduled'), color: 'var(--chart-3)' },
  } satisfies ChartConfig

  if (data.length < 2) return null

  return (
    <ChartContainer config={chartConfig} className="h-[220px] w-full">
      <LineChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="date"
          {...AXIS_PROPS}
          tickFormatter={(value) => toDate(String(value)).toLocaleDateString(locale, {
            month: 'short',
            year: '2-digit',
          })}
        />
        <YAxis {...AXIS_PROPS} tickFormatter={money.tick} width={MONEY_AXIS_WIDTH} />
        <ChartTooltip
          content={
            <MoneyChartTooltip
              config={chartConfig}
              labelFormatter={(value) => new Intl.DateTimeFormat(locale, {
                month: 'short',
                year: 'numeric',
              }).format(toDate(String(value)))}
            />
          }
        />
        <Line
          dataKey="actual"
          type="stepAfter"
          stroke="var(--color-actual)"
          strokeWidth={2}
          dot={false}
        />
        {hasPlan && (
          <Line
            dataKey="scheduled"
            type="monotone"
            stroke="var(--color-scheduled)"
            strokeWidth={2}
            strokeDasharray="5 5"
            dot={false}
          />
        )}
      </LineChart>
    </ChartContainer>
  )
}
